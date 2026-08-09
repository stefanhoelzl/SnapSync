## Context

`OsReceipt` (capability `ios-app-shell`, landed 2026-08-05) already carries three of the app's five
OS-supplied completion handlers correctly: the silent push, the upload heartbeat `BGTask`, and the
download backstop `BGTask` each construct one at the Kotlin edge and release it after their work. The two
that do not are the two background-`URLSession` handlers, and they are the two whose handover and whose
drain signal are separated by a gap, which is why each became a bare field.

Three diagnostic dumps from one field device — iPhone11,2 / iOS 18.7.9 / build 573 / `upload_tier:
url_session`, covering 2026-08-05 → 08-08 (Bugsink `SNAPSYNC-9/10/11`) — supply the measurements this
design rests on:

- **The upload handler is released against running work, structurally.** At `17:38:47.629` the handler is
  stored; a cycle starts at `.670`; the drain signal arrives at `.675`; `pump.onSessionEvents` enters and
  exits at `.708` **in 0 ms**, releasing the handler; the cycle is still running at `.725`. Across all
  three dumps `pump.onSessionEvents` exited in 0 ms 27 times, 1 ms twice, 2 ms once — **30 of 30** — while
  the same `drive()` reached 6 072 ms under `pump.onBackgroundTask`. The mechanism is not luck: each
  delivered completion fires `onTerminal → scope.launch { pump.onUploadCompleted() }`, so a drain is
  always already in flight when `didFinishEvents` arrives, and `drive()`'s single-flight admission
  discards the caller.
- **The same discard drops a heartbeat re-arm.** At `23:08:21` a `BGProcessingTask` fired,
  `pump.onBackgroundTask` exited in 2 ms with no `runCycle` line between entry and exit (so it coalesced
  rather than running a `SKIPPED` cycle), and no `scheduler.scheduleNext` followed. The heartbeat is
  one-shot; its re-arm is documented as unconditional. One observed drop in seven fires; the mechanism is
  certain from source. Recovery is `onForeground`, whose re-arm is unconditional — so the symptom is
  "background uploads stop until I open the app".
- **The composition lane was not busy at handover.** Ten upload wakes: handover → adopt 5–12 ms, adopt →
  drain 46–59 ms, wake → work complete ≈ 85 ms against a 20 000 ms deadline, and **zero** receipt
  expiries in four days.

Two vendor facts constrain the shape. `URLSessionDelegate.urlSessionDidFinishEvents(forBackgroundURLSession:)`
states: *"Because the provided completion handler is part of UIKit, you must call it on your main thread."*
Both handlers are currently released on the composition lane. The same Discussion also says the drain is
when *"it is now safe to invoke the previously stored completion handler **or to begin any internal updates
that may result in invoking the completion handler**"* — so holding the handler through post-drain work is
inside Apple's documented model, not a departure from it.

## Goals / Non-Goals

**Goals:**

- Both background-`URLSession` handlers are bounded from the moment the OS hands them over.
- Neither handler is released while the work its wake triggered is still running.
- A second handover before the first drain cannot orphan the earlier handler.
- Both are released on the main thread, as UIKit requires.
- A coalesced pump trigger keeps its obligations: it awaits the drain and re-arms.
- The download handler's adoption is visible in a diagnostic dump.

**Non-Goals:**

- Changing how the three already-correct handlers (silent push, both `BGTask`s) are carried.
- Removing `drive()`'s single-flight admission. Coalescing is correct; discarding the caller is not.
- Making the composition lane un-blockable, or starting a receipt's clock before its coroutine is
  dispatched. See D7.
- Porting `quiesce` from `parked/settle-imports-by-transaction`. `awaitOutstandingImports` is used exactly
  as it exists; nothing changes about what enters its list.
- Bounding a single unit of work. That remains the import deadline's job (`photo-download`).

## Decisions

**D1 — One type holds every OS handler, and it is not a field.** `BackgroundEventsReceipts` lives in
`:domain` `ports/` beside `OsReceipt` — same cross-cutting shape, same injected seam, and `ports/` may
reference coroutines (both zone gates restrict only project-internal references). Two instances, one per
session, each constructed with its entry point, its deadline, and the work its drain feeds. Rejected:
leaving the state machine in `UrlSessionUploadController`, because `:app:*` Kotlin is wiring-only and
untested by rule, so the deferred lifecycle and the second-handover policy could not be covered; and
duplicating it per site, which would place half the behaviour in the untested shell.

**D1a — The bound is applied to the await, not to the receipt** (found in implementation, not design).
`OsReceipt`'s deadline releases the handler and then deliberately leaves its block running — correct when
the block is work in flight, wrong when it is a pure wait for a signal. Applied naively, a session that
never drained released its handler on time and left one coroutine parked on a deferred for the process's
life, one per wake. So `adopt` passes `OsReceipt` an infinite deadline and bounds the await itself, where
cancelling abandons nothing: the work a drain feeds runs in `drained()`'s coroutine, not the waiter's.

**D2 — The bound starts at the handover, not at the drain.** `adopt(handler)` launches the receipt
immediately; `drained()` completes what the receipt is waiting for. The previous shape created the receipt
inside the drain callback, so the deadline began only once the session reported — the one interval it was
supposed to bound was outside it. Rejected: keeping the receipt at the drain and adding a separate
handover timer, which is two bounds where one suffices.

**D3 — A window, not a slot.** `adopt` joins the current window; `drained()` closes it, installs a fresh
one, runs the work, and releases every handler in the closed window. A handler adopted after a drain waits
for the next one, which is correct — its wake's events have not been delivered yet.

*This is the one decision with no forcing proof, and it is recorded as such.* No Apple document and no
measurement says a second `handleEventsForBackgroundURLSession` can arrive before the first drains; Apple's
own sample stores a single handler and would overwrite it. We represent the case because a field cannot,
and because the cost is one deferred rather than one reference — not because it has been observed.
Expiry trigger: a field dump showing two adopts inside one window, or an Apple doc stating the delivery
is serialized, either of which settles it.

**D4 — A coalesced trigger awaits the whole drain loop and re-arms on its shared result.** The in-flight
drain publishes a `CompletableDeferred<CycleResult>`; a coalescing caller sets `retrigger`, awaits it
outside the lock, then evaluates `shouldSchedule` with **its own** flags against the drain's final result.
This is what makes the receipt's hold mean something and what restores the dropped heartbeat re-arm — one
`return` statement was responsible for both.

Rejected: awaiting exactly one cycle past the retrigger, which needs a per-cycle generation counter and
answers the wrong question (the caller wants its work drained, not a cycle run). Rejected: having the
coalesced caller re-arm on `alwaysScheduleNext` alone, which would re-arm a `SKIPPED` membership forever.

**Safety of the whole-loop await.** It deadlocks if a trigger is re-entered synchronously from inside the
drain. In production nothing does: every trigger arrives from an OS callback on another coroutine, and
`onCycleComplete` — the only lambda `drive()` invokes inside its loop — is
`{ app.ledgerCounts.refresh() }`, a ledger read that publishes to a `StateFlow` and has no route back to
the pump. The only re-entrant callers are two existing tests, which move to concurrent triggering (D8).

**D5 — The release hops to the main lane; the wait does not.** UIKit's requirement is about the *release*.
The receipt continues to wait wherever it was launched, and only the `release()` invocation is dispatched
onto an injected lane. `OsReceipt` gains an optional release lane defaulting to "here", so the three
already-correct call sites are untouched. `SnapSyncRoot` supplies `Dispatchers.Main` for the upload
controller (it is already the one app-process file allowlisted by `MainLaneContainmentTest` to name the
lane), and the download side takes it from the existing `AppPorts.uiLane` seam. Rejected: making every
receipt release on main, which would extend a requirement Apple states only for this handler to two
handlers it does not cover.

**D6 — The guard confines, it does not ban.** Apple's recipe *is* store-then-invoke, so a blanket
prohibition on storing an OS handler forbids the platform's documented pattern and forces the one
legitimate holder to dodge its own gate. Instead, in the shape `KeychainContainmentTest` uses for
`SecItem*`: the type-shaped mutable field fails the build **anywhere except** `BackgroundEventsReceipts`,
whose allowlist entry states why it is there. The rule matches `var`/`lateinit var` whose type is a
nullary `Unit`-returning function, nullable or not — verified to match exactly the three fields this
change removes and nothing else, so the wider form is free and closes the "store it non-null instead"
route. `val` is deliberately not matched: `OsReceipt`'s own `release` parameter is one.

Rejected: the earlier name-based rule, which matched fields whose name contained `ompletion`/`nComplete`
and therefore passed `IosUrlSessionUploadPlatform.onBackgroundEventsFinished` — the exact forbidden type,
in a scanned root — purely on its name. Rejected: excluding the adapter directory instead of converting
that field, which would leave the scope carve-out doing the guard's work.

**Stated residue, because a guard that implies it has none is the failure being fixed.** The rule catches
*storing*, not *releasing early*: a new entry point that calls its raw handler inline stores nothing and
passes. Non-nullary and non-`Unit` handler shapes are not matched. A handler stored inside a collection or
behind a type alias is not matched. If one of those bites, the rule is widened — never an exception added.

**D7 — The lane-stall hypothesis is recorded as refuted, not designed against.** A receipt launched onto
the single-threaded composition lane cannot start its deadline until it is dispatched, so a blocked lane
could in principle void the bound (the lane exists precisely because platform calls block it — build 521,
`assetsd` wedged for a whole watchdog allowance). The field data does not support it: 10/10 wakes completed
in ~85 ms and no receipt has ever expired. It is stated as a known caveat with an expiry trigger — the
first dump showing a receipt expiry line, or a wake-to-adopt gap above ~1 s — and nothing in this design
is justified by it.

**D8 — The re-entrant pump tests move to concurrent triggering, and must gain assertions.**
`coalescesConcurrentTriggersIntoOneRerun`, `onSilentPush_coalesces_with_an_in_flight_cycle` **and
`refreshesStatusAfterEachCycle`** each call a trigger from inside `runCycle`, which the whole-loop await
turns into a deadlock. (This design first said two; implementation found the third, which hung for the
full `runTest` minute — the deadlock is real and the mechanism detects it.) They are rewritten to
launch the second trigger concurrently — which is how production delivers triggers — and must keep
asserting `runs == 2` **and** additionally assert that the coalesced call returned only after the drain
ended and re-armed per its own trigger's policy. A rewrite preserving only `runs == 2` would silently stop
observing anything.

**D9 — The download adoption is logged.** `adoptBackgroundEvents` writes nothing today, so no diagnostic
dump can say whether that handler was ever released — which is why the download side's behaviour was
invisible while the upload side's was measurable. It gets the same `Logger.invocation` wrap the upload side
has (law "Absence is never silent").

## Risks / Trade-offs

- **A coalesced caller can wait through several retrigger-driven cycles under sustained triggers** → The
  receipt bounds when the *handler* is released; nothing bounds the call itself. On expiry the handler goes
  out, the line is logged, and the work continues — the safe degradation `OsReceipt` already documents.
  What is **not** bounded, and is worth saying plainly, is the caller: a drain that never ends holds its
  coalesced callers indefinitely.
- **Serialising the receipts' work runs introduces head-of-line blocking** (D1's `running` mutex) → A stuck
  `work()` parks every later drain behind it, and on downloads that is reachable in ordinary operation: an
  import's own deadline is 30 s, longer than this session's 20 s handler budget, so a slow batch outlives
  the receipt that announced it. Handlers still release on their deadlines, so the symptom is a run of
  expiry lines with no drain work rather than a stall. Accepted because the alternative is what the
  download side did before — independent drains, where the second releases its handlers against the
  first's unfinished imports, the destructive-read defect D1 exists to close. This is a **new** cost on the
  download side and it is not free.
- **A coalesced caller now inherits the in-flight drain's exception** → It previously returned normally
  whatever happened. The throwable reaches the caller's own unwind — for `onForeground` and
  `onSelectionChanged`, the trigger flow, whose scope handler logs it to `debug.log` and lets the app live.
  Non-fatal, but it is a caller-visible change, not merely a blocking one.
- **`onForeground`, `onStart` and `onSelectionChanged` now block while a drain finishes** → Aligned with
  the law "a trigger flow never outlives its own run"; they previously returned before the work they
  announced. Bounded by the drain, and none of them is on the UI lane.
- **Multiple coalesced callers may each call `scheduleNext()`** → `BGTaskScheduler` replaces a pending
  request with the same identifier, and `IosBackgroundScheduler` already wraps the submit in
  `runCatching`, so the surplus is idempotent rather than an error.
- **The window is un-evidenced** → D3, stated with its expiry trigger rather than argued from necessity.
- **The test fakes could hide the harm** → The receipts tests must adopt handlers with **distinct
  identities** and assert each one's release individually; a shared counter cannot see an orphaned first
  handler. This is the `parked/settle-imports-by-transaction` lesson, where a flagship test passed while
  the duplicate it existed to prevent was being created, because its fake reused one identifier.
- **Every behavioural claim here must be revert-proofed** → Each defect is reintroduced in an **isolated
  git worktree** and the suite must go red naming a failing test; a mutation that fails to compile or
  hangs is not a kill.

## Migration Plan

None required. No durable state, schema, wire format, or persisted value changes; behaviour differences are
confined to one process's in-memory coordination. Rollback is a revert.

## Open Questions

- Whether the release lane for the download session should be `AppPorts.uiLane` as-is or a distinctly named
  seam. `uiLane` is documented as "presents platform UI", and "releases a UIKit-owned handler" is adjacent
  but not identical. Resolvable during implementation without changing any requirement.
