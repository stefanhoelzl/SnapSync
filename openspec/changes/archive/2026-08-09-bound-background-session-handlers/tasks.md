## 1. The receipt-holding type

- [x] 1.1 Add an optional release lane to `OsReceipt` (`domain/.../ports/OsReceipt.kt`), defaulting to "release here", so the three already-correct call sites are unchanged; document that the lane governs the release only, not where the hold waits (D5)
- [x] 1.2 Create `BackgroundEventsReceipts` in `domain/.../ports/`, with `adopt(handler)` and `drained()`, the window model of D3, and KDoc stating the D3 no-forcing-proof position and its expiry trigger
- [x] 1.3 Serialise the type's work runs, so two overlapping drains cannot let the second release its handlers against the first's unfinished work (`awaitOutstandingImports` is a destructive read and would otherwise return instantly for the second)
- [x] 1.4 Write `BackgroundEventsReceiptsTest` in `domain/src/commonTest/` (runs JVM + `iosSimulatorArm64`): handlers adopted with **distinct identities**, asserted individually, so an orphaned first handler is observable; cover release-after-work, deadline expiry with work continuing, two handlers in one window, a handler adopted after a drain waiting for the next, and release-on-the-injected-lane

## 2. The pump's coalesced triggers

- [x] 2.1 Replace `drive()`'s discarding `return` with: publish a `CompletableDeferred<CycleResult>` for the in-flight drain, have the coalescing caller set `retrigger`, await it outside the lock, then evaluate `shouldSchedule` with its own flags against the awaited result (D4)
- [x] 2.2 Complete that deferred on every drain exit path, including the `catch` that clears `draining` and rethrows
- [x] 2.3 Rewrite `coalescesConcurrentTriggersIntoOneRerun`, `onSilentPush_coalesces_with_an_in_flight_cycle` and `refreshesStatusAfterEachCycle` (the third was found by the deadlock at implementation time) to trigger **concurrently** rather than re-entrantly from inside `runCycle` (the whole-loop await deadlocks on re-entry), keeping `runs == 2` and adding assertions that the coalesced call returned only after the drain ended and re-armed per its own policy (D8)
- [x] 2.4 Add a test that a coalesced `onBackgroundTask` re-submits, and that a coalesced trigger against a `SKIPPED` drain arms nothing

## 3. The two call sites

- [x] 3.1 Convert `IosUrlSessionUploadPlatform.onBackgroundEventsFinished` from `var … = null` to a constructor `val onEventsFinished: () -> Unit`, passed as `{ receipts.drained() }` in the same lazy-capture style as the neighbouring `onTerminal`
- [x] 3.2 Delete `UrlSessionUploadController.backgroundEventsCompletion`; `onBackgroundSessionEvents(completion)` becomes `receipts.adopt(completion)` + `platform.reattach()`
- [x] 3.3 Delete `QueuedPhotoDownloadJobs.backgroundCompletion`; `adoptBackgroundEvents` becomes an adopt, and the `onBackgroundEventsFinished` override a `drained()`, with `awaitOutstandingImports()` as the work
- [x] 3.4 Wrap `adoptBackgroundEvents` in `Logger.invocation` so the download wake and its handler's fate are readable in a dump (D9)
- [x] 3.5 Wire the main-lane release: `SnapSyncRoot` supplies `Dispatchers.Main` to the upload controller; the download side takes `AppPorts.uiLane`. Resolve the design's open question — reuse `uiLane` or introduce a distinctly named seam — and record the choice where it is wired
- [x] 3.6 Confirm `MainLaneContainmentTest` still passes: no new file names the lane outside its allowlist

## 4. The guard

- [x] 4.1 Add the confinement guard to `:test:architecture`: mutable nullary-`Unit` function properties (`var`, `lateinit var`, nullable or not) fail outside `BackgroundEventsReceipts`, with the allowlist entry stating its reason
- [x] 4.2 Add the non-vacuity twin — a zero-file scan fails rather than passing empty
- [x] 4.3 Write the KDoc residue paragraph verbatim from the spec: catches storing not early release; misses non-nullary/non-`Unit` shapes, collections, and type aliases; widen the rule rather than add an exception

## 5. Verification

- [x] 5.1 `./gradlew build` green, plus `./gradlew compileIosMainKotlinMetadata` for the iOS proxy
- [x] 5.2 Run `./gradlew architectureDiagrams` and commit if anything regenerates (stale diagrams block the PR)
- [x] 5.3 Revert-proof mutation 1 — release at adopt instead of after the work — in an isolated `git worktree`; a named test must fail
- [x] 5.4 Revert-proof mutation 2 — a second adopt overwrites the first — must fail a named test
- [x] 5.5 Revert-proof mutation 3 — remove the deadline — must fail a named test
- [x] 5.6 Revert-proof mutation 4 — point the guard's scan at a missing root — the non-vacuity twin must fail
- [x] 5.7 Revert-proof mutation 5 — reintroduce a stored handler field in `UrlSessionUploadController` — the guard must fail
- [x] 5.8 Revert-proof mutation 6 — a coalesced caller returns immediately — the heartbeat-re-arm test must fail
- [x] 5.9 Revert-proof mutation 7 — a coalesced caller re-arms on `alwaysScheduleNext` alone — the `PROCESSING`-schedules / `COMPLETED`-does-not pair must fail
- [x] 5.10 Record each mutation's result naming the failing test; a mutation that fails to compile or hangs is not a kill and must be reworked
- [x] 5.11 Independent review by an agent that did not write the code, scoped to behaviour and to comments that are factually false about the code

## 6. Landing

- [x] 6.1 `openspec validate --specs --strict` (via the pinned npx form) passes
- [ ] 6.2 PR labelled `bug`, then `/ship`
- [ ] 6.3 After merge: watch for the first field dump carrying a receipt expiry line or a wake-to-adopt gap above ~1 s — the stated expiry trigger for D7's refuted lane-stall hypothesis
