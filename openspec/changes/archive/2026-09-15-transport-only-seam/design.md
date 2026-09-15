## Context

`BackgroundTransfer` (`:domain` `ports/`) is implemented by three transports — `IosUrlSessionUploadPlatform`
(`:adapter:ios:app-only`), `IosPhotoKitUploadPlatform` and the simulator's `SimulatorUploadJobQueue`
(`:adapter:ios:ext-safe`, chosen by the `uploadJobQueue` expect/actual) — by the world's
`FakeBackgroundTransfer`, by `SelectionScopedTransfer` (`feature/upload`, the partial-grant read discipline),
and by two test doubles (`UploadCycleTest.FakePlatform`, `SelectionScopedTransferTest.RecordingDelegate`).

Measured on `90fd599c` (after M1 #271 and M9 #272):

- **Library reads.** All three transports implement `discoverResources` and `resourcesFor` as a one-line
  forward to the same `IosDiscovery`, wrapped in `log.invocation("platform.discoverResources" | …)`.
  `IosDiscovery` also carries `buildRequest`, which the transports use to build the upload `NSURLRequest`.
- **Ledger use.** Each transport holds the whole `LedgerStore`:
  - URLSession: `markTerminal` in the delegate's `recordTerminal`; `requestedKeys` + `markTerminal(FAILED)` in
    `reconcileStranded`, run at the start of every `drainTerminals`.
  - PhotoKit: `markTerminal` in the drain; `entryForDestination` in `resolveKey`, used by the drain **and** by
    `fetch(.retry)`.
  - Simulator: `markTerminal(job.key, …)` in its drain.
  - World fake: `markTerminal` in its drain.
- **A retry defect.** PhotoKit `retryJob` re-finds its system job with `jobWithKey`, comparing
  `destination.URL.lastPathComponent` to the ledger key. The v2 byte route is
  `/files/devices/<deviceId>/<assetId>/<role>?filename=…`, so that segment is the role and never equals a key:
  every retry logs `retryJob: no live .retry job for … — it settled underneath us` and applies nothing. The
  job's retry is then spent by the OS and the drain re-creates it, so uploads still land, one attempt later.
  (Inferred from the code; see Open Questions.)

Constraints this design is bound by:

- **Durable at report time** (`changes/archive/2026-08-26-fix-lost-upload-acks`). A background-`URLSession`
  completion is delivered once, and the write must land before the non-suspending ObjC callback returns.
- **"Commands cross one door"** (`module-architecture`): *adapter outbound callbacks SHALL be declared on the
  port and satisfied only by compose-built lambdas whose body is a single flow command call*. Every flow entry
  (`Foreground.run`, `SilentPush.run`, …) is `suspend`. No gate enforces this clause today.
- **The module graph cannot withhold `LedgerStore` from a transport.** It lives in `:domain:ports`, which a
  transport must depend on to implement `BackgroundTransfer`; `:adapter:ios:ext-safe` also declares
  `api(project(":domain:feature"))` (for `AlbumMapSource`), inherited by `:adapter:ios:app-only`.
- **The OS job queue cannot enumerate in-flight jobs.** The Photos klib declares exactly two
  `PHAssetResourceUploadJobAction` values: `Acknowledge = 1`, `Retry = 2`.

## Goals / Non-Goals

**Goals:**

- `BackgroundTransfer` is the transfer lifecycle: `createJob · retryJob · fetchRetryJobs · drainTerminals ·
  remainingCapacity · liveKeys`.
- Library reads are one port, bound once per root.
- No transport holds `LedgerStore`; what a transport may touch in the ledger is visible in its constructor and
  held by a gate.
- The stranded-row recovery decision lives in `feature/upload`, where `commonTest` reaches it.
- The PhotoKit free retry is applied.
- Everything else is behaviour-preserving: no log text, cursor, ledger state or acknowledgement changes meaning.

**Non-Goals:**

- Moving *when* the stranded check runs (phase M2), scoping `clearRequested` (M3), or running both backends
  (M8a/M8b).
- Recomposing the ledger key from the destination URL. The probe in `device-speaks-v2` measured it viable, but
  that change's D2 chose the recorded destination column deliberately; reversing it is a ledger-identity
  decision, not a seam refactor.
- The existing `onTerminal` / `onEventsFinished` constructor lambdas in `UrlSessionUploadController`, which
  reach the pump without a flow. They already sit outside the door clause; this change neither fixes nor
  widens that.
- `UploadCycle`'s adjudication, the retry/acknowledge contract, `SelectionScoped*`'s read discipline, and the
  download side.

## Decisions

### D1 — One library-read port, `UploadDiscovery`, implemented by `IosDiscovery`

`interface UploadDiscovery { suspend fun discover(sinceToken: ByteArray?, policy: SelectionPolicy): Discovery;
suspend fun resourcesFor(keys: Set<String>): List<Resource> }`, with `Discovery` moved beside it.
`IosDiscovery` declares the supertype; each root binds its existing instance into `UploadPorts.discovery`, and
`uploadCore` hands `SelectionScopedDiscovery(ports.discovery, ports.selectionScope)` to `UploadCycle`.

- *One port, not two.* Both reads are PhotoKit identifier fetches over the same `candidatesFrom`, and the
  selection-scope wrapper narrows both by one rule; two ports would give that rule two things to wrap.
- *Not beside `CandidateSource`.* That port's KDoc argues a cursor does not belong there — the status total
  needs a count of the current set, a change feed yields changes — and it is shared with the status total and
  the join preview.
- *The name.* It completes an existing family (`DiscoveryStore` is its cursor, `Discovery` its result) and
  stays true on a platform without change tokens, unlike `LibraryChangeFeed`. `UploadSource` / `ResourceSource`
  collide with the `*Source` read-model family.

### D2 — The upload-request builder leaves `IosDiscovery`

`buildRequest(url, request)` becomes a top-level function in `:adapter:ios:ext-safe`'s upload package, byte
for byte (HTTP/3 opt-out included). After D1 it was the only reason a transport held `IosDiscovery`.

### D3 — The discovery log lines keep their text

`IosDiscovery` wraps its two methods in the same `log.invocation("platform.discoverResources", result = …)`
and `"platform.resourcesFor"` calls the transports made, under the logger its root passes — the same logger
the transport was given. The `platform.` prefix now names a class that is not the transport; a rename would
break every grep of existing device logs, and no log line changes meaning here.

*Rejected:* emitting them from `SelectionScopedDiscovery`, which would log a walk for a cycle that read the
snapshot.

### D4 — No core-owned terminal sink

The handoff's D14 proposed a sink the core implements and the adapter calls. Rejected:

- It **is** an adapter outbound callback wired to a feature — the exact scenario "Commands cross one door"
  corrects.
- Routing it through a flow is impossible for the terminal write: flow commands are `suspend`, the delegate
  cannot suspend, and the write must land before it returns.
- The handoff's reason for rejecting "keep `LedgerStore`" was that it would stay a convention rather than a
  compiler-enforced boundary. A sink does not change that (see Context): any boundary here is a narrowed type
  plus a gate.

*Also rejected:* a compose-built factory for such a sink, and a carve-out in the door clause. Both keep a
callback into the core that nothing needs once D6 turns the only decision-carrying call into a query.

### D5 — `TransferRecord`: the narrow ledger surface a transport receives

```kotlin
interface TransferRecord {
    fun markTerminal(key: String, outcome: TerminalOutcome): Boolean
    suspend fun entryForDestination(destinationPath: String): LedgerEntry?
}
interface LedgerStore : TransferRecord { … }   // both members move up, signatures unchanged
```

It adds no logic and decides nothing; it **restricts**. A transport can no longer reach `recordUnlessSettled`,
`resetTo`, `clearRequested`, `rowsNeedingJob`, `aggregates` or any other store operation. Every existing call
site is byte-identical — only the declared parameter type changes. `SqlDelightLedgerStore` and both
`InMemoryLedgerStore`s satisfy it by extension.

Stated plainly, because "transport only" hides it: **a transport still records** the terminal fact the
platform hands it. Durability at report time forces a synchronous write inside the callback, and the only
write that is not a callback into the core is a storage port.

*Rejected:* keeping `LedgerStore` and gating which of its methods a transport calls. Same enforcement, but a
reader of a constructor learns nothing, and the world fake and test doubles keep a twenty-member dependency.

### D6 — `liveKeys()` is a query; the stranded rule moves into `UploadCycle`

`BackgroundTransfer.liveKeys(): Set<String>?`:

- **URLSession** answers `liveTaskKeys()` — the same `getAllTasks` set its cap and cancellation read.
- **PhotoKit, the simulator substitute and the world fake** answer `null`: a durable OS queue has no stranded
  population, and the null idiom is `remainingCapacity`'s ("null is an answer, not a failure").

`UploadCycle.recreateRetrySpent` calls `platform.drainTerminals()`, then — before iterating the returned
jobs — runs the stranded pass:

```
live = platform.liveKeys() ?: skip
for key in strandedKeys(pending = ledger.requestedKeys(), live = live):
    if ledger.markStranded(key)  log.i "reconcile: stranded REQUESTED $key (no live task) — recorded FAILED to re-upload"
    else                         log.i "reconcile: stranded $key settled underneath this pass — left as it stands"
```

- **Same position.** Today the pass runs inside `drainTerminals` before it returns. On both real tiers the
  relative order of the pass and the returned-job loop is unobservable — URLSession returns no jobs and
  PhotoKit reports no live set — so "right after the call" preserves it.
- **Same reach.** `recreateRetrySpent` also runs on a direction-declined cycle, exactly as `drainTerminals`
  does today, and is not reached by a skipped cycle.
- **`LedgerWriter` gains two delegations**, `requestedKeys()` and `markStranded(key): Boolean =
  backend.markTerminal(key, TerminalOutcome.FAILED)`. A further record operation belongs on the writer
  (`sync-ledger`); nothing is added to `LedgerStore`.
- **`strandedKeys` moves** from `UrlSessionOutcome.kt` to `feature/upload` with its four
  `UrlSessionOutcomeTest` cases, which become `commonTest`. A new `UploadCycleTest` case covers the loop,
  including the guard declining a row that settled mid-pass.

It is a read the core asks for, not a call from the adapter, so it touches no door. M2 inherits the shape and
changes only where `liveKeys()` is asked.

### D7 — The PhotoKit retry finds its job by the drain's route

`retryJob(job, request)` walks the `.retry` set and picks the system job whose destination, classified by the
existing `classifyPhotoKitJob`, resolves through the existing `resolveKey` (recorded destination path, then the
v1 last-segment fallback) to `job.key`. `jobWithKey`'s last-segment comparison is deleted. The selection is
extracted as a pure function beside the other per-job decisions in `PhotoKitJobMapping.kt` so
`PhotoKitJobMappingTest` pins it (a v2 destination whose last segment is `primary` matches its key; a
non-matching set yields none). The "no live .retry job" line stays for a job that genuinely left the set.

This is the one behaviour change: `retryWithDestination(:)` begins to be applied on the v2 route.

### D8 — The target-bound factory and the simulator substitute

`expect fun uploadJobQueue(log: Logger, record: TransferRecord): BackgroundTransfer` — neither actual needs
`IosDiscovery` after D1/D2. The substitute's private `resourceForKey` stays: it recovers the live
`PHAssetResource` a device job object carries, which is not discovery, and routing it through
`UploadDiscovery` would re-introduce the dependency D1 removes, swap its role match for a filename match, and
give the unscoped discovery a second binding. Its KDoc gains that sentence. `UploadJobSubsystemBindingTest`
reads only the device token and the simulator-fatal selectors, so it is unaffected.

### D9 — The world splits along the same line

`FakeBackgroundTransfer` keeps the job buckets, levers and inspection, takes a `TransferRecord`, answers
`liveKeys() = null`, and loses `discoverResources` / `resourcesFor`. A new `FakeUploadDiscovery` in
`:test:world` takes both reads with `discoverCalls`, `resolvedKeys`, `resolvedKeyCount`, `forceFull` and the
token counter. `World` exposes it as `discovery`, and integration tests that assert discovery observability
address `w.discovery.*`, so each test names the seam it watches.

`null` keeps the world behaving exactly as it does today: its fake has no stranded pass now, and
`LostUploadAckIntegrationTest`'s dropped-transfer case passes by a route that does not use one.

### D10 — A gate keeps `LedgerStore` out of transports

`:test:architecture` scans `adapter/` production sources for files declaring a `BackgroundTransfer`
implementation or `uploadJobQueue`, strips comments, and fails on any `LedgerStore` reference or on an empty
scope (`architecture-guards`, "The transport-ledger gate"). It is what holds D5 once the next edit comes.

### D11 — One change, one PR, two commits

- **Commit (a)** — D1, D2, D3: `UploadDiscovery`, `SelectionScopedDiscovery`, the request builder, the world's
  discovery fake. Builds and passes on its own.
- **Commit (b)** — D5–D10: `TransferRecord`, `liveKeys`, the stranded pass, the retry fix, the factory, the gate.

(b) does not depend on (a). The PR carries one label, `bug`, for the retry fix.

## Risks / Trade-offs

- **[The reconcile lines change logger tag]** → They move from the adapter's logger to the cycle's
  (`UploadPorts.log`). Text and severity are identical; a device-log grep by message still matches. Recorded
  here so a grep by tag is not surprised.
- **[The retry path has not run on the v2 route]** → Applying `retryWithDestination(:)` exercises code that
  has been dead since the v2 byte route shipped. Its request is rebuilt by the same provider that builds every
  first attempt, and a failure still falls through to the drain's re-create. Verify on a device build before
  merge (tasks).
- **[The inferred defect may not be what devices show]** → Checked before the fix (task 0.1): all 26
  diagnostic dumps in Bugsink (2026-07-31 → 2026-08-25) predate both the warning line (added 2026-08-26) and
  the v2 byte route (2026-08-28), so the field neither confirms nor refutes it. The six OS-driven dumps that
  carry extension log content show exactly one retry pass (2026-08-14, build 605, v1 keys), which did reach
  its jobs — consistent with the diagnosis, since a v1 destination's last segment *was* the key. The defect
  therefore rests on the code reading plus the device verification in task 4.2; a dump from an iOS ≥26.1
  full-grant device on a post-2026-08-28 build would settle it in the field.
- **[Stale line references]** → The handoff and early investigation cited `2f55dba2`; this design and the
  tasks cite `90fd599c`.
- **[M2 is designed against the pre-change seam]** → It inherits `liveKeys()` and only moves the call site;
  the design session is told via the handoff report.

## Migration Plan

No schema, wire or persisted-state change. Both commits are source-only; rollback is a revert. A build carrying
(b) on a device holding in-flight PhotoKit jobs needs nothing: jobs created by the previous build are
resolved by the unchanged destination route, and retry matching uses that same route.

## Open Questions

- **Should the world fake report its pending bucket from `liveKeys()`?** It genuinely can, and
  `LostUploadAckIntegrationTest` would then exercise the real stranded pass it is named for. Deferred: it
  changes world behaviour for every test holding a `REQUESTED` row without a fake job, which is not this
  change's to decide.
- **The `platform.` log prefix.** Kept for grep continuity (D3); a rename is a separate, trivial decision.
