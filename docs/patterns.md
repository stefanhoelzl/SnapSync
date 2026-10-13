# Writing code in the core's layers

How work enters the core, how it runs, and how it is written — the patterns `:domain:inbound`,
`:domain:scheduling`, `:domain:entrypoints` and `:domain:domain` hold, and every new trigger and every value the
domain gains follows. Where each layer sits and what
it may reference is `docs/architecture.md` ("The core and its zones"); this file is how to write in them.

```
platform ──delivery──▶ event port ──inbound kind──▶ entry point ──steps──▶ features · services · sequences
 (adapter)              (ports/)     (inbound/)      (entrypoints/,          (single flight, scheduling/)
                                                      on scheduling/'s base)
```

---

## 1. The inbound kinds

Every delivery an event port makes is typed by one of four kinds (`:domain:inbound`). The kind is what the platform
promises about the delivery, so it decides how the core may answer it.

| kind | the platform | the core | examples |
|---|---|---|---|
| `Inbound<A, R>` | delivers **once, synchronously**, on its own thread, and may take an answer `R` | persists what the delivery means **before it returns** — there is no second delivery | a transfer's end, an import's placeholder and settlement, a push token, a metric report, a lifecycle notice, a link, a UI intent |
| `Background<A>` | hands a `Completion` with the delivery and waits for it | starts the work and returns at once; the work answers the completion when it ends. Its time is the process's own background time, held for the run | a silent push, a session relaunch, the app coming to the front, the rig's reset |
| `Granted<A>` | the delivery **is** a grant of time, which it may withdraw | as `Background`, but holds no time of its own, and answers the `Running` the adapter expires when the grant is withdrawn | a scheduled wake (`BGTask`, a WorkManager worker) |
| `Answered<A, R>` | calls synchronously and needs the answer on its own thread, after work that suspends | blocks the delivering thread until the answer is known; any failure answers a declared value | the upload extension's `process()` |

`Completion` (answer once) and `Running` (expire) are the two handles the kinds carry. Each kind is a plain
interface, never a `fun interface`: no lambda converts to one, so behaviour cannot be passed around in its place.

**Why four.** The design named three; `Granted` was split from `Background` when the port grid showed that only a
wake's adapter can ever expire a delivered run — a push, a relaunch or a foreground runs on background time the
core holds itself, and that hold's end is what expires it. With one kind, every `Background` port carried a
`Running` cell no adapter could produce (`ClauseCoversTest` refused it). Now the type says which deliveries can be
withdrawn.

**Only the runtime implements a kind.** `@SubclassOptInRequired(InboundImplementation::class)`: the entry-point
bases in `scheduling/` opt in, and the host's two UI handlers at their pinned site; everything else subclasses a
base. So every delivery reaches the core through code that labels, times and contains it.

**Only a composition wires one.** A handler bundle's constructor, `EventPort.inbound(…)`, `Observation.follow` and
`Log.install` are `@CompositionOnly`. The app's ports are wired in one function, `AppCore.inbound(host)`
(`compose/AppInbound.kt`), which the host calls once the host can be assembled; the process's in `snapSyncProcess`;
the extension's in `snapSyncExtension`.

---

## 2. Entry points

One entry point per operating-system trigger, **named for it** (`OnSilentPush`, `OnScheduledWake`), in
`entrypoints/AppEntryPoints.kt` or `ExtensionEntryPoints.kt`. Read top to bottom, those two files are what the app
does on every trigger.

```kotlin
class OnSilentPush(runtime: Runtime, private val prelude: Prelude, …) : EntryPoint<PushMessage>(runtime) {
    override suspend fun run(arg: PushMessage) = steps {
        required { prelude.run() }
        isolated("receive") { receiver.onSilentPush(arg.payload) }
        when (guard.joinsTail(arg.payload)) {
            true -> detached { wake.full(WakeKind.SILENT_PUSH) }
            false -> Unit
        }
    }
}
```

- **Pick the base by the kind:** `EntryPoint` (`Background`), `GrantedEntryPoint` (`Granted`), `InlineEntryPoint`
  (`Inbound`, runs on the delivering thread and persists before it returns), `AnsweredEntryPoint` (`Answered`, with
  the answer a failure gives).
- **`run` orders and never decides.** Each step makes one call; what the step does is that call's. A `when` over a
  fact a feature answered selects between steps — with a subject, no `else`, as an expression — and nothing more.
  A decision is a feature's. The `entrypoints` detekt tier holds the claim (cyclomatic and cognitive 4).
- **The base gives every trigger the same treatment by construction:** the `Trigger` (the class name), the
  containment, the background-time hold (begun on the delivering thread, before the callback returns — the OS may
  suspend the process the moment it does), and the expiry.
- Work several entry points share is a plain class beside them (`Prelude`, `ForegroundWork`, `WakeWork`,
  `Provision`), written in steps too. Work started from inside the process for a trigger that is not the platform's
  (a staged download, a restored key, a transition's arm) is a small class whose `requested()` starts it
  `detached` under that name (`OnDownloadStaged`, `OnKeyRestored`, `OnArm`).
- State whose change starts work is **followed**, not polled: `Observation.follow(flow, entryPoint)` hands each
  emission to an entry point as a delivery, and the next waits for that run (`OnSelectionChanged`,
  `OnNetworkReturned`).
- `entrypoints/` sees the ports' vocabulary for its arguments and calls no port. Its edges to `feature/` and
  `services/` are **migrating**: phase 4 moves the work underneath sequences and use cases.

---

## 3. Runs, steps and expiry

`Runtime` (`scheduling/`) is built once per process by its composition and handed to every entry point. It owns the
process scope (supervised, on the composition lane, each task's thread labelled with its trigger), the
background-time holds, and the expiry. Nothing else launches.

**Steps** (`steps { }`, inside an entry point or a sequence):

| step | means |
|---|---|
| `required { }` | the run cannot go on without it: a failure ends the run |
| `isolated("name") { }` | a failure is logged as an `Error` line and the run goes on |
| `concurrently { child("a") { }; child("b") { } }` | children run at once, each isolated; the step ends when all have |
| `detached { }` | work this run does not await: it runs in the process scope under its own background-time hold, labelled with this run's trigger |
| `run(sequence)` | a pass of a single-flight `Sequence`, answering `Pass.Completed(result)` or `Pass.Cut` |
| `ensure("name") { }` | runs when the other steps are done **however they ended** — failed, or cancelled because time is up — before the run answers the OS (the heartbeat's re-arm) |

`outliving { }` is for work the platform cannot stop once started (a photo-library import): it runs in the process
scope, and if its caller stops waiting it runs on to its end, whose own report records what it did.

**Expiry.** Time is up when the OS says so: a `Granted` delivery's `Running.expire()`, or the end of a held
background time. The run is **cancelled**; its cancellation is given at most `EXPIRY_GRACE` to unwind, and then the
completion is answered and the hold ended **regardless** — too early and the process is suspended holding a lock
the other process shares, too late and the watchdog kills it. `EXPIRY_GRACE` is 2 s, measured on the SE2
(2026-10-13): unwinds took 2–287 ms, and the slowest non-cancellable step a cancellation can wait out — a top-up's job
creation, export included — took ~1.3 s; twice that, capped at 2 s. Every expired run logs `expired run unwound
after=…`, the evidence for re-measuring. A completion is answered exactly once.

**Atomic pairs.** Cancellation stops work at its next suspension, so two steps that must happen together are one
`withContext(NonCancellable)` unit, short and with nothing slow inside. Today: a top-up's job creation and its
ledger row `REQUESTED` — a job the platform created is recorded before the pass can end, or the next top-up would
create it again (both uploaders create through the one cycle, so the app session's transfer and its row are the same
pair); the top-up checks cancellation **between** creations. A manifest publish and its record are a pair too: a
snapshot the backend took is recorded before the pass ends. A walk is cancelled outright and records nothing; an
import runs on, `outliving` its pass, and its own settlement (`OnImportSettled`) records what it did.

---

## 4. Sequences and single flight

Work several triggers may ask for at once is a `Sequence` (`ImportReceived`, `TopUp`, `UploadOwn`), run single-flight
by its key across the process:

- At most one pass runs per key. A request while one runs **joins**: exactly one more pass runs once the running
  one ends, however many joined, and every joiner gets that pass's result. The pass's trigger names every joiner
  (`[OnHeartbeat+OnSilentPush]`).
- A pass runs in the process scope, never in a waiter's. A waiter whose time is up **withdraws** without cancelling
  a pass others still wait for; a pass is cancelled only once every waiter has gone, and a waiter gets its own
  cancellation, never another's.
- `yieldsToJoin`: a join cancels the running pass and the next starts at once, answering the running pass's waiters
  too — and, through a chain of yields, every waiter of every pass that gave way. For a pass that can wait on
  something that may never report (an import stalled on a library transaction). Its steps must make that safe: what
  a cancelled pass leaves, the next pass picks up.
- A pass that ends without a result answers `Pass.Cut`. A reader treats a cut as "work may remain" (the re-arm
  reads it as `PROCESSING`).

---

## 5. Containment

A run's failure is caught in exactly three places, each logging an `Error` line (which reaches the crash channel),
and each rethrowing a `CancellationException` — a cancellation is the caller's scope ending, never a failure:

1. the `Runtime` entry: a run (`run failed`), an inline delivery (`delivery failed`), and an answered one, which
   answers its declared failure value — the delivering thread is an Objective-C boundary any Kotlin throwable
   aborts the process across, so there nothing escapes, cancellation included;
2. `isolated` (and each `concurrently` child);
3. the process scope's `CoroutineExceptionHandler`, for work nobody awaits (`unawaited work failed`).

`SingleFlight` turns a pass's end into a value for its waiters — answer, failure or cancellation (a cut) — and is
the fourth `catch` in the layer. `CatchSitesTest` licenses exactly these files, and holds every other catch-all in
production — the old zones' `runCatchingCancellable`, `bestEffort`, `contained`, `orNullLogged` and broad or typed
`catch` clauses (an adapter's typed catch excepted: turning a platform throw into a value is its job) — to a baseline,
counted per file, that may only shrink. Phase 4 empties it as it moves the old zones under entry points and
sequences, and deletes the helpers with their last use. `CatchGateTest` holds the other half: every site still
there keeps cancellation.

---

## 6. Logging

New code writes the **structured log**, `Log` (`:domain:inbound`):

```kotlin
private val log = Log("UploadCycle")
log.i("top-up created jobs", "created" of n, "truncated" of truncated)
```

- An **event** is a short fixed phrase; the values ride as **fields** (`"name" of value`), rendered by the writer,
  never interpolated into the event.
- A line carries the `Trigger` of the work that wrote it, read from the coroutine context — which is why the calls
  suspend. Non-suspend code writes through `log.unlabelled`.
- The text sinks render `[OnSilentPush] [Info/UploadCycle] top-up created jobs created=3 truncated=false`; the crash
  channel keeps the trigger as its `entry_point` tag.

The old zones' Kermit lines keep their prefix through a **bridge**: the runtime's lane writes each task's trigger
into a per-thread slot, the `ThreadLabel` port (a `ThreadLocal` on the JVM and Android, a thread-local global on iOS),
and the Kermit writer reads it. Each old line moves to `Log` when phase 4 moves its code. Both writers are installed
once per process by `snapSyncProcess` (`ProcessLogWriters.install`, which calls `Log.install`).

---

## 7. The domain

`:domain:domain` holds the **values and verdicts** the rest of the core speaks: the identities (`EventId`,
`DeviceId`, `AssetId`), the one point in time (`Timestamp`), the dates each play one role (`EventStart`, `EventEnd`,
`OpenUntil`, `DeletesAt`, `CaptureCutoff`, `CaptureCeiling`), an asset's capture fact (`CaptureDate`) and the rules
over them (`CaptureRange`). Packages are named for the concept: `time`, `event`, `sharing`, `asset`, `device`.

- **It depends on nothing** — no project module, no library. Ports, coroutines and formats do not resolve there.
- **A verdict lives on its type.** No top-level function: `CaptureRange.of(…)`, `EventId.parse(…)`,
  `AssetId.isCanonical(…)`. That is also what lets a private constructor mean something: the only way in is the
  type's own.
- **Parse, don't validate.** A value that exists obeys its rule: an `EventId` is a canonical UUID, a `CaptureRange`
  is inclusive, at most `EventWindow.MAX` long and overlapping its event. A verdict that can fail answers an
  `XVerdict` (`CaptureRangeVerdict.Ok(range)` or the reason), never a nullable fault.
- **No format.** A `Timestamp` holds an instant and no text; a role holds a `Timestamp`. How a value is spelled on the
  wire or in a store is §8's.
- **A nullable or sentinel input that several rules re-check is a missing type.** `CaptureDate` is the worked
  example: `Dated(at) | Undated` replaced `creationDate: CaptureDate?`, so each capture rule matches both cases by name
  instead of remembering a null check. The claim below holds because of this — every rule bound for the layer that
  measured above 4 was re-checking such an input.
- **The claim: ≤ 4 cyclomatic, ≤ 4 cognitive** per function (`config/detekt/domain.yml`), the entry points' claim. A
  verdict is a `when` over a fact's cases; one that needs more is two verdicts, or a type that splits the cases.

`DomainPurity` holds what the standard library still allows: no `suspend`, no `var` or `lateinit` property, no
mutable collection, array or `StringBuilder` held as a property, no function-typed parameter, no top-level function
or extension property, no `Clock.System`, `TimeSource.Monotonic`, `Random`, `Uuid.random` or console. A local `var`,
`by lazy`, and a `buildList` a function returns all pass. It reads syntax only: a mutable type behind an inferred type
it does not name, and the world read inside a call, are review's.

---

## 8. Formats live at the boundary

A value's text — on the wire, in a store, in a JSON file — is the boundary's, never the domain's, and it is written
**once per format**:

| format | the one place | |
|---|---|---|
| a timestamp's text (`yyyy-MM-dd'T'HH:mm:ss'Z'`) | `TimestampText` (`model/`) | `format`, `parse`, `parseOrNull`, and an asset's `captureDate`/`captureText` |
| a value in a JSON format | `AsString` serializers (`model/DomainSerializers.kt`) | each format opts in with `@file:UseSerializers(…)`; a missing one is a compile error |
| a value in a database column | a `ColumnAdapter` per type (`services/databases/DomainColumnAdapters.kt`) | wired once per database; the `.sq` column names its type (`TEXT AS EventId`) |

Each moves to `data` with the services in phase 4. A column's Kotlin type lives in the `CREATE TABLE` of a migration,
and a shipped migration is never edited (`MigrationPinTest`): giving a column a type is a new migration that rebuilds
its table (`ledger.db` 15, `downloads.db` 9), changing no byte a device stores. A column that holds a sentinel no
value can be (`ledgerRow.eventId`'s parked `''`) stays text, converted where it is read.

---

## 9. Opt-in capabilities

A `@RequiresOptIn(level = ERROR)` marker turns "only X may do Y" into a compile error everywhere else — stronger than
a text scan, because the compiler resolves names: an anonymous object, a fully qualified call or a type alias cannot
slip past it. Its hole is that anyone may write `@OptIn(X::class)`, so **every opt-in that is a decision is pinned**
(`OptInPinsTest`), exact in both directions:

- **The project's markers**, found rather than listed: `@InboundImplementation`, `@CompositionOnly` (§1) and
  `@AppProcessOnly` — the walk memo, which the upload extension's 32 MB process must never construct or reach: its
  constructor carries the marker, `appUploadDiscovery` carries it on, and the app's uploader opts in once.
- **The library markers the library itself calls delicate**: `DelicateCoroutinesApi`, `InternalCoroutinesApi`,
  `ExperimentalForInheritanceCoroutinesApi`, `DelicateCryptographyApi` — the sites where someone accepted a risk.
  The markers that only say "this is cinterop" or "not yet stable" are not pinned.
- **No build script opts a module into either kind**: a module-wide opt-in would silence the pin for the whole module.

Opt into a marker where its decision is made, and carry it on (annotate the declaration with the marker itself) where
a caller should make it instead. Test equipment — test source sets, the rig, the test modules, the mocks — opts in
freely: it plays the core or the platform.

---

## 10. Evidence types

A verdict that gates a later step answers an **evidence type** the step requires: the step cannot be called without
the verdict having said yes. Each evidence constructor carries its **own** `@RequiresOptIn(ERROR)` marker, opted into
at exactly **one** call site (never module-wide), and listed by the pin (§9). Evidence is minted fresh for each use —
never stored, never `@Serializable`, never a `data class`, never in API Swift can see — and a value class keeps it
free. The mechanism is in place; each concept's evidence arrives with its phase.

---

## 11. Use cases are leaves

A use case (`VerbNoun`, one operation) never calls another: entry points orchestrate, use cases do one thing. The
compiler will hold it — `invoke` carries an `EntryPointCall` opt-in only an entry point takes — and so there is no
shared `UseCase` interface, since a call through it would skip the marker. The marker arrives with the first use
case.

---

## 12. Secret types and rendering

A value that must never reach the crash channel is a **secret type** whose `toString` is masked: an event's key, a
bearer token, and an invite link with only its key masked (the rest of the link stays readable). Each arrives with
its concept. **Device, event and asset ids render in full** (`privacy-security`: crash reports may carry device,
event and photo ids — never an event key), so `EventId`, `DeviceId` and `AssetId` render as the id itself.

### Result naming

One suffix per layer, strictly: a port's `XResult` · a read's `XRead` · a gateway's `XReply` · a use case's
`XOutcome` · a domain decision's `XVerdict`. A case that means the same thing has the same name in each hierarchy —
`Ok(value)`, `Absent`, `Unreachable`, `Refused(reason)`, `Unavailable` — duplicated per hierarchy, since a sealed
subclass cannot cross modules.

---

## 13. What holds these rules

| rule | gate |
|---|---|
| An entry point holds no `if`; a `when` only over a subject, every case named | detekt `EntryPointShape` (`:domain:entrypoints`) |
| A step is one call | detekt `OneCallPerStep` (`:domain:entrypoints`) |
| Only the runtime starts coroutines | detekt `CoroutinesInScheduling` (`:domain:inbound`, `:domain:entrypoints`, `:domain:domain`; the old zones join in phase 4) |
| Behaviour crosses as an interface, never a function-typed field, `fun interface` or alias | detekt `NoFunctionTypes` (`:domain:inbound`, `:domain:scheduling`, `:domain:entrypoints`, `:domain:domain`) |
| The entry points' decisions and the runtime's size stay small | the `entrypoints` and `scheduling` tiers (`config/detekt/entrypoints.yml`, `scheduling.yml`) — claims, not debt |
| An entry point names no port | `EntryPointsCallNoPortTest` |
| Only the runtime's sites catch everything; the old zones' catch-alls only shrink | `CatchSitesTest` (+ `CatchGateTest`: cancellation is kept) |
| The project's markers, and the delicate library ones, are opted into only where pinned; no build script opts a module in | `OptInPinsTest` |
| The domain is values and verdicts: no suspension, state, behaviour as a value, world or top-level function | detekt `DomainPurity` (`:domain:domain`) |
| A domain verdict stays small | the `domain` tier (`config/detekt/domain.yml`) — a claim, measured |
| The walk memo stays in the app process | `@AppProcessOnly` + `OptInPinsTest` |
| A format is written once, at the boundary | `TimestampText`, the `AsString` serializers, the column adapters — review; a missing serializer or adapter is a compile error |
| A handler bundle is built and registered only by a composition | `ListenDoorTest` |
| No OS completion handler is stored in a field | `OsHandlerContainmentTest` |
| A background entry point never names the membership's teardown | `BackgroundTeardownTest` |
| `inbound`, `scheduling` and `entrypoints` have no uncovered line | `coverageZero` (the entry points credited by the integration surface) |

