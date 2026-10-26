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
`entrypoints/AppEntryPoints.kt` or `ExtensionEntryPoints.kt` — and one per request a member makes of the membership
(`OnJoinConfirmed`, `OnLeaveConfirmed`, `OnSettingsChanged`, `OnRenameRequested`, `OnInviteForOtherEvent`), in
`MembershipEntryPoints.kt`. Read top to bottom, those files are what the app does on every trigger.

```kotlin
class OnSilentPush(runtime: Runtime, private val prelude: Prelude, …) : BackgroundEntryPoint<PushMessage>(runtime) {
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

- **Pick the base by the interface the delivery comes through:** each base is named for the one it implements —
  `InboundEntryPoint` (`Inbound`, runs on the delivering thread and persists before it returns — its `run` is an
  extension of the `Delivery`, whose `detached { }` starts work the delivery does not await),
  `BackgroundEntryPoint` (`Background`), `GrantedEntryPoint` (`Granted`), `AnsweredEntryPoint` (`Answered`, with the
  answer a failure gives).
- **`run` orders and never decides.** Each step makes one call; what the step does is that call's. A `when` over a
  fact a feature answered selects between steps — with a subject, no `else`, as an expression — and nothing more.
  A decision is a feature's. The `entrypoints` detekt tier holds the claim (cyclomatic and cognitive 4).
- **The base gives every trigger the same treatment by construction:** the `Trigger` (the class name), the
  containment, the background-time hold (begun on the delivering thread, before the callback returns — the OS may
  suspend the process the moment it does), and the expiry.
- Work several entry points share is a plain class beside them (`Prelude`, `ForegroundWork`, `WakeWork`, `Joining`,
  `Leaving`, `EndOfWake`), written in steps too. Work started from inside the process for a trigger that is not the platform's
  (a staged download, a transition's arm) is a small class whose `requested()` starts it
  `detached` under that name (`OnDownloadStaged`, `OnArm`). Started inside an entry point's work,
  it is that work's; started outside any, it is its own.
- A person's tap is an entry point too: `OnTap`, on the UI port, hands the intent to the status host's intent table,
  and every intent that launches — and every command it runs — is the tap's work. A fire-and-forget command starts
  through `Runtime.alongside`: detached, with no background-time hold of its own (the app is in front). A request a
  tap hands to an entry point of its own — a join, a leave, a settings change, a rename, a switch — is that entry
  point's delivery, with its own `done`; the tap's ends with the hand-off.
- State whose change starts work is **followed**, not polled: `Observation.follow(flow, entryPoint)` hands each
  emission to an entry point as a delivery, and the next waits for that run (`OnSelectionChanged`,
  `OnNetworkReturned`).
- **What the membership reads decides which steps run, through one builder**:
  `membership(branch, Cached | Refreshed) { active { … } deleted { … } notJoined { … } }` reads the membership — as
  held, or after bringing an active one up to the server's word — and runs the steps of the branch it is in, each
  branch handed what it proves (the active membership, the deleted one, the evidence that none is held). A membership
  that cannot be read runs nothing; an omitted branch does nothing; a block that names none is refused
  (`EntryPointShape`).
- **A use case's outcome carries what the member is told.** A refusal is one case of a sealed group whose `answer` is
  the request's answer (`EnrollOutcome.NotEnrolled.answer: JoinCommit`), so an entry point branches only on whether the
  operation went ahead, and the mapping is the use case's — unit-tested with it.
- A member's request is answered on its **status** (section 13): the entry point's first step is
  `ensure("answer") { status.settle(id, …) }`, so a run that ends early still answers, and the step that knows the
  outcome writes it with `done`.
- `entrypoints/` sees the ports' vocabulary for its arguments and calls no port. Its edges to `feature/` and
  `services/` are **migrating**: phase 4 moves the work underneath sequences and use cases.

---

## 3. Runs, steps and expiry

`Runtime` (`scheduling/`) is built once per process by its composition and handed to every entry point. It owns the
process scope (supervised, on the composition lane, each task's thread labelled with its trigger), the
background-time holds, and the expiry. Nothing else launches.

**Steps** (`on.steps { }`, inside an entry point or a sequence). `steps` is a member of the run's `Run` — the runtime
hands one to an entry point's `run(arg, on)`, a sequence's `pass(on)` and detached work (`{ on -> … }`), and a helper
the steps call takes it as `on: Run` — so steps written outside a run do not compile. (Not a Kotlin context parameter:
detekt's parser cannot read one.) `Runtime.outliving` needs no run: it is the process scope's.

| step | means |
|---|---|
| `required { }` | the run cannot go on without it: a failure ends the run |
| `isolated("name") { }` | a failure is logged as an `Error` line and the run goes on |
| `concurrently { child("a") { }; child("b") { } }` | children run at once, each isolated; the step ends when all have |
| `detached { }` | work this run does not await: it runs in the process scope under its own background-time hold, labelled with this run's trigger — and its entry point's `done` waits for it |
| `run(sequence)` | a pass of a single-flight `Sequence`, answering `Pass.Completed(result)` or `Pass.Cut` |
| `ensure("name") { }` | runs when the other steps are done **however they ended** — failed, or cancelled because time is up — before the run answers the OS (the heartbeat's re-arm) |

`runtime.outliving { }` is for work the platform cannot stop once started (a photo-library import): it runs in the process
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

### Entry events

`Runtime` tells what happens to each entry point's work, in every build, through the `EntryEvents` port and a `Log`
line beside each (`Info`; a `done` that failed or expired is a `Warn`):

| event | when | line |
|---|---|---|
| `delivered` | the operating system handed the delivery over — told before its hold is begun | `entry` (and `about`: a tap's intent) |
| `answered` | the operating system got its answer: the completion, or the delivering call's return | `entry`, `after` |
| `done` | the delivery's run **and every run it started without awaiting, transitively** have ended | `entry`, `after`, `outcome` (`COMPLETED`, `EXPIRED`, `FAILED` — the worst of its runs) |

`done`, not `answered`, means "finished": a silent push and a session's relaunch answer after their prelude and then
run the wake detached, and an upload's finish answers at once and tops up detached. The work of a delivery is every
run started inside it — `detached` steps, a `Delivery`'s `detached`, `Runtime.detached(name)` and `alongside` called
from inside it, `outliving` work (which runs on after its caller is cancelled), and what a tap's intents start — carried
in the coroutine context. Work started for no delivery (`OnArm` from a grant change) is its own: a `done` with nothing
before it. A sequence's pass runs in the process scope, never in a waiter's: what it detaches is its own, named by the
pass's trigger, and a waiter's `done` covers the pass only while it waits for it.

What `done` does not see yet: work the old zones still launch on `Runtime.scope` — a leave's backend notice, the
album gather, a reconfigure's download reconcile, the permission and push subscriptions. Phase 4 moves them under
entry points, and their work joins its entry's `done` then.

A shipped build's adapter keeps nothing (`NoEntryEvents`): the log line is the diagnostic. A rig build and the JVM feed
the control channel's event stream (`docs/testing.md`, "How a test waits"). The iOS upload extension, its own process
on a device, keeps nothing either: its events reach only its own log (`ext-debug.log`).

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
and each rethrowing a `CancellationException` — a cancellation is the caller's scope ending, never a failure — and
passing on an `InvalidProgramState`, which ends the process (§15):

1. the `Runtime` entry: a run (`run failed`), an inline delivery (`delivery failed`), and an answered one, which
   answers its declared failure value — the delivering thread is an Objective-C boundary any Kotlin throwable
   aborts the process across, so there nothing escapes, cancellation included, and an `InvalidProgramState` ends the
   process there, on the delivering thread, with its report (`endProcess`);
2. `isolated` (and each `concurrently` child);
3. the process scope's `CoroutineExceptionHandler`, for work nobody awaits (`unawaited work failed`).

`SingleFlight` turns a pass's end into a value for its waiters — answer, failure or cancellation (a cut) — and is
the fourth `catch` in the layer. `CatchSitesTest` licenses exactly these files, and holds every other catch-all in
production — the old zones' `runCatchingCancellable`, `bestEffort`, `contained` and broad or typed
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
  wire or in a store is §9's.
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

## 8. The data layer

`:domain:data` holds the **repositories over the ports and the backend's protocol over the transport** — what the
backend's routes say and how a store keeps a value. Today it holds trust (`docs/architecture.md`, "Trust"): the gateway,
the credential stores, the device identity, the attestation handshake, the version gate — and the membership: the
`ledger.db` store, the membership row's repository, the leaves owed, the check times, the event's read and rename, the
enrolment and the leave told to the backend. Its edges are the domain, the format, the ports, the generated wire and
the inbound log — never an old zone, never `application`.

- **Data translates and never decides.** Its only branch is an exhaustive `when` over a fact or a domain verdict,
  with a subject and no `else`, **used as an expression** — its value is the answer, so no case can quietly do nothing
  (`DataShape`). No `if`. Loops, safe calls and typed catches of a library's exceptions pass; the decision a branch
  stands for is a domain verdict (`RenewalVerdict`, `CredentialVerdict`, `VersionVerdict`).
- **Nothing throws** (§15). A store answers what it read (`TokenRead`, `KeyIdRead`, `DeviceIdRead`, `MembershipRead`:
  the value, none, or `Unavailable` — never mistaken for absence) and what it wrote (`WriteOutcome`, `StoreWrite`). A
  statement runs through the `Databases` port's `OpenDatabase.execute`, whose adapter catches its platform's SQL
  exception and answers `DbResult.Failed`. An id column reads as a `Stored` — the id it spells, or the text when it
  spells none — since a device's rows were also written by earlier builds and copied in by migrations; each reader says
  what a row that holds none means (the membership is unreadable, a leave fails), as an exhaustive `when`.
- **A write is decided for one membership.** Every change after an entry names the membership it was decided for and
  changes the row only while it is still that one (compare-and-set); a change of several records — a leave, a share
  change — is ONE transaction, so it lands whole or not at all.
- **A process holds the kind it may hold, by type.** Where two processes may do different things with one concept,
  they get two types, never a flag: `MintingDeviceIdentity` and `StoredDeviceIdentity`, `AttestingCredential` and
  `DropOnlyCredential`. The app-only kinds carry `@AppProcessOnly` on their constructors (§10).
- **The claim: ≤ 3 cyclomatic, ≤ 3 cognitive** per function (`config/detekt/data.yml`). A `when` whose every entry is
  one expression counts once (`_base.yml`): it selects over a fact's cases. A function that needs more is deciding.
- **The old zones reach it one way while their concepts move** (MIGRATING). A shim that keeps an old zone's shape for
  callers a later phase owns — the throwing `requireDeviceId()` over the sealed device-id read — lives in the old zone,
  never in the layer, and is listed in `PENDING_CLEANUPS.md` with the phases that delete its callers.

Its suites run over the ports' mocks in `:test:data` (no core module may name a mock), credited to it; its pure
translations are tested in its own `commonTest`.

---

## 9. Formats live at the boundary

A value's text — on the wire, in a store, in a JSON file — is the boundary's, never the domain's, and it is written
**once per format**:

| format | the one place | |
|---|---|---|
| a timestamp's text (`yyyy-MM-dd'T'HH:mm:ss'Z'`) | `TimestampText` (`:domain:format`) | `format`, `parse`, `parseOrNull`, and an asset's `captureDate`/`captureText` |
| a value in a JSON format | `AsString` serializers (`model/DomainSerializers.kt`) | each format opts in with `@file:UseSerializers(…)`; a missing one is a compile error |
| a value in a database column | a `ColumnAdapter` per type (`data/store/DomainColumnAdapters.kt`) | wired once per database; the `.sq` column names its type (`TEXT AS EventId`) |

`:domain:format` holds the formats that zones on both sides of the data layer need: a dependency on it hands over
nothing but formats — no DTO, no port, no store. The rest moves to `data` with the services in phase 4. A column's Kotlin type lives in the `CREATE TABLE` of a migration,
and a shipped migration is never edited (`MigrationPinTest`): giving a column a type is a new migration that rebuilds
its table (`ledger.db` 15, `downloads.db` 9), changing no byte a device stores. A column that holds a sentinel no
value can be (`ledgerRow.eventId`'s parked `''`) stays text, converted where it is read.

---

## 10. Opt-in capabilities

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

## 11. Evidence types

A verdict that gates a later step answers an **evidence type** the step requires: the step cannot be called without
the verdict having said yes. Each evidence constructor carries its **own** `@RequiresOptIn(ERROR)` marker, opted into
at exactly **one** call site (never module-wide), and listed by the pin (§10). Evidence is minted fresh for each use —
never stored, never `@Serializable`, never a `data class`, never in API Swift can see — and a value class keeps it
free. Each concept's evidence arrives with its phase; the membership's are `Enrolled` (the server admitted this
device), `ConfirmedNotJoined` (the store answered with no membership), `LeftMembership` (the leave's transaction landed)
and `ConfirmedDeletion` (the event's photos are deleted, by the server's word or the membership's own deadline).

---

## 12. Use cases are leaves

A use case (`VerbNoun`, one operation) never calls another: entry points orchestrate, use cases do one thing. The
compiler holds it — `invoke` carries an `EntryPointCall` opt-in only an entry point takes, pinned where it is taken
(`OptInPinsTest`) — and so there is no shared `UseCase` interface, since a call through it would skip the marker. The
first is trust's `RefreshAttestation`, the step every wake's `Prelude` runs; the membership's are
`application.membership`'s (`EnrollDevice`, `EnterMembership`, `LeaveEvent`, `RefreshMembership`, `ReconfigureEvent`,
`RenameEvent`, `DeliverPendingLeaves`, `ClaimStateCheck`). A use case answers an `XOutcome`;
`:domain:application` claims 3 / 3, measured on it (`config/detekt/application.yml`).

---

## 13. Queries and requests

What the screen **asks** is a query, in `:domain:query`: a read over the data layer, never a write
(`QueryReadOnlyTest`) — the membership (`MembershipQuery`), an invite's event (`JoinDetailsQuery`), the server's word
on the joined membership when another invite opens (`SwitchLookup`). A query answers a value the caller waits for.

What a member **asks the app to do** is a request: the tap crosses the `UserCommands` door at once with a
`RequestId`, an entry point runs it, and its outcome is answered on that kind of request's **status**
(`RequestStatus`: `Idle` · `InFlight(id)` · `Done(id, value)`, `MembershipStatuses`). Statuses are keyed by the
request's id, so a screen acts only on the answer to its own request — an earlier attempt's answer never greets a
later one, and nothing has to be reset. The work writes through a `RequestStatusSink`: `begun` at the door, `done`
where the outcome is known, and `settle` — which answers only a request still running — as the entry point's `ensure`
step, so a run cut short still answers. There is no fifth entry-point base: a request is a `Background` delivery like
any other, and its status is a read-model.

At the door a request is `handedOff` (`compose/UserCommandsComposition.kt`): the third lane-declaring form beside
`awaitingOnCoreLane` and `detachedOnCoreLane` (`CommandLaneTest`), since the entry point's runtime runs the work on
the composition lane and nothing but the hand-off runs on the tapping thread.

---

## 14. Secret types and rendering

A value that must never reach the crash channel is a **secret type** whose `toString` is masked: an event's key, a
bearer token (`BearerToken`, trust's, equal by its secret), and an invite link with only its key masked (the rest of
the link stays readable). Each arrives with its concept. **Device, event and asset ids render in full** (`privacy-security`: crash reports may carry device,
event and photo ids — never an event key), so `EventId`, `DeviceId` and `AssetId` render as the id itself.

A secret whose bytes the core must handle — the event key (`EventKey`, encryption's) — guards them twice more. Reading
its bytes is an opt-in (`@KeyMaterial`), and so is making one from bytes (`@MakesEventKey`); each marker's sites are
pinned (`OptInPinsTest`), so who touches key material is a compile-checked list: its text form, its id's derivation
and the cipher. It holds its bytes as a `List<Byte>` (the domain holds no `ByteArray`) and is equal by them.

### Result naming

One suffix per layer, strictly: a port's `XResult` · a read's `XRead` · a gateway's `XReply` · a use case's
`XOutcome` · a domain decision's `XVerdict`. A case that means the same thing has the same name in each hierarchy —
`Ok(value)`, `Absent`, `Unreachable`, `Refused(reason)`, `Unavailable` — duplicated per hierarchy, since a sealed
subclass cannot cross modules.

---

## 15. Invalid program state

`InvalidProgramState` (`:domain:domain`, `program/`) is **the one exception the core raises**. It is thrown only where
something *guarantees* the state can never happen, and it is **fatal**: if it is ever thrown, one of our guarantees is
wrong, and running on might not be safe.

```kotlin
// The gallery services' one encoding of a platform id: every character it emits is canonical.
return AssetId.parse(encoded.toString()) ?: throw InvalidProgramState("the encoding emits only canonical characters")
```

- **Its `because` names the guarantee, never the symptom:** a port's contract (`randomBytes` answers the count asked),
  a type, a check just before. It is a string literal with no `$` in it (detekt `InvalidProgramStateBecause`), so it
  carries no key, no id, no value into the crash report, and every site groups as one crash.
- **It is not an expected failure.** A state an input from outside can reach — a server's malformed reply, a stored
  row an earlier build wrote, an OS-held tag — is never impossible: it is a value (`XRead`, `XVerdict`, `Parsed`, an id
  column's `Stored`). Its supertype is `Error`, not `Exception`, so it cannot be mistaken for one.
- **Nothing catches it.** A catch of `Exception` passes it on; a catch of `Throwable` catches it first in the same `try`
  and rethrows it (`CatchGateTest`) — `runCatchingCancellable`, `isolated`, a run's containment and a pass's outcome
  all do. Where it would otherwise leave Kotlin for the platform, the process ends: the runtime's synchronous
  boundaries (an inline or answered delivery) and its scope's handler hand it to the coroutines library's last resort
  (`endProcess`), a root's scope rethrows it (`uncaughtInRootScope`), and on iOS `objcBoundary` runs Kotlin/Native's
  unhandled path itself (`endProcess` in `objc/`). A Kotlin throwable that unwinds into Objective-C or Swift frames
  terminates **without** Kotlin/Native's unhandled-exception hook, and the crash report says only that something threw
  — measured on the simulator, 2026-10-25; through the last resort the report carries the guarantee and the stack.
- **It ends with a crash report**, in both iOS processes and on Android: the crash reporter writes it as the process
  dies and delivers it on the next start, through the same scrub and bounds as every event. `CrashReporterContract`'s
  `AFTER_A_PROCESS_DEATH` clauses prove it on the simulator and the emulator.
- **No test can reach it**, so the coverage zero gate excuses exactly its throw and the one arm that alone leads there,
  read from the bytecode (`docs/architecture.md`, "Coverage"); the rest of its line is judged as ever.
- **The layers throw nothing else** (detekt `OnlyInvalidProgramStateThrows`, over `domain`, `format`, `data`,
  `application`, `query`, `entrypoints`, `scheduling` and `inbound`): no other `throw` than a rethrow, no `require`,
  `check`, `error`, `requireNotNull`, `checkNotNull`, `TODO` or `!!`, and none of the standard library's reads that throw
  on an empty or missing value (`first`, `last`, `single`, `reduce`, `getValue`, `getOrThrow`). A conversion that throws
  on bad text (`String.toInt()`) is review's: syntax cannot tell it from one that cannot. The old zones join as phase 4
  moves them; until then their throwing reads live in the old zones (`use`, `held`, `requireDeviceId` —
  `PENDING_CLEANUPS.md`).

---

## 16. What holds these rules

| rule | gate |
|---|---|
| An entry point holds no `if`; a `when` only over a subject, every case named; a `membership { }` block names a branch | detekt `EntryPointShape` (`:domain:entrypoints`) |
| A query only reads | `QueryReadOnlyTest` |
| Every user command declares its lane: awaited, detached or handed to an entry point | `CommandLaneTest` |
| Data holds no `if`; a `when` only over a subject, every case named, used as an expression | detekt `DataShape` (`:domain:data`) |
| Only an entry point calls a use case | `EntryPointCall` + `OptInPinsTest` |
| A step is one call | detekt `OneCallPerStep` (`:domain:entrypoints`) |
| Only the runtime starts coroutines | detekt `CoroutinesInScheduling` (`:domain:inbound`, `:domain:entrypoints`, `:domain:domain`, `:domain:data`, `:domain:application`; the old zones join in phase 4) |
| Behaviour crosses as an interface, never a function-typed field, `fun interface` or alias | detekt `NoFunctionTypes` (`:domain:inbound`, `:domain:scheduling`, `:domain:entrypoints`, `:domain:domain`, `:domain:data`, `:domain:application`) |
| The entry points' decisions and the runtime's size stay small | the `entrypoints` and `scheduling` tiers (`config/detekt/entrypoints.yml`, `scheduling.yml`) — claims, not debt |
| An entry point names no port | `EntryPointsCallNoPortTest` |
| Only the runtime's sites catch everything; the old zones' catch-alls only shrink | `CatchSitesTest` (+ `CatchGateTest`: cancellation is kept) |
| The project's markers, and the delicate library ones, are opted into only where pinned; no build script opts a module in | `OptInPinsTest` |
| The domain is values and verdicts: no suspension, state, behaviour as a value, world or top-level function | detekt `DomainPurity` (`:domain:domain`) |
| A domain verdict stays small | the `domain` tier (`config/detekt/domain.yml`) — a claim, measured |
| Data and a use case stay small | the `data` and `application` tiers (`config/detekt/data.yml`, `application.yml`) — claims |
| Only the app process mints an identity or attests | `@AppProcessOnly` on `MintingDeviceIdentity` and `AttestingCredential` + `OptInPinsTest` |
| The walk memo stays in the app process | `@AppProcessOnly` + `OptInPinsTest` |
| A format is written once, at the boundary | `TimestampText`, the `AsString` serializers, the column adapters — review; a missing serializer or adapter is a compile error |
| A handler bundle is built and registered only by a composition | `ListenDoorTest` |
| No OS completion handler is stored in a field | `OsHandlerContainmentTest` |
| A background entry point never names the membership's teardown | `BackgroundTeardownTest` |
| `inbound`, `scheduling`, `entrypoints`, `domain`, `data` and `application` have no uncovered line | `coverageZero` (the entry points credited by the integration surface, data and application by `:test:data`) |
| The layers throw only an `InvalidProgramState`; no `require`, `check`, `!!` or throwing read | detekt `OnlyInvalidProgramStateThrows` (`domain`, `format`, `data`, `application`, `query`, `entrypoints`, `scheduling`, `inbound`) |
| An `InvalidProgramState`'s `because` is a literal | detekt `InvalidProgramStateBecause` (every production source set) |
| Nothing catches an `InvalidProgramState` | `CatchGateTest` |
| An `InvalidProgramState` ends the process with a crash report, delivered on the next start | `CrashReporterContract` (`AFTER_A_PROCESS_DEATH`, on `IOS_SIM_KEXE` and `ANDROID_EMU`) |
| `steps { }` is written only inside a run | the compiler: it is a member of the `Run` the runtime hands an entry point's run, a sequence's pass and detached work |

