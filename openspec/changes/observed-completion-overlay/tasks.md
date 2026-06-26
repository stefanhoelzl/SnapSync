## 1. Ledger read face — snapshot + pending rows (`:domain:engine`)

- [x] 1.1 Add `selectPending` to `Ledger.sq` (`SELECT assetId, key FROM ledgerRow WHERE state != 'COMPLETED'`).
- [x] 1.2 Add a `pendingResources()` read to the `LedgerBackend` interface (returns `(assetId, key)` rows) and implement it on the SQLDelight backend.
- [x] 1.3 Implement `pendingResources()` on every other backend: the iOS App-Group backend, the `DarwinCrossProcessLedgerBackend` decorator (delegates), and the in-memory test fakes (`InMemoryLedgerBackend` in engine tests and in `:app:ios:photokit-extension` tests).
- [x] 1.4 Add a `LedgerSnapshot(completed, newestCompletionAt, pendingByAsset: Map<assetId, Set<key>>)` type and replace `LedgerWatcher.aggregates` with `LedgerWatcher.snapshot`: per ding read `backend.aggregates()` (reuse for the scalars) **and** `pendingResources()` in one shot, group by assetId, emit one consistent snapshot; keep the cold-flow + `distinctUntilChanged` semantics.
- [x] 1.5 Extend the shared `LedgerBackendContract` (commonTest) with cases for `pendingResources()` (only non-`COMPLETED` rows; empty when all complete) and for `snapshot` consistency (scalars and backlog from one read; unchanged snapshot stays silent). Runs on JVM + `iosSimulatorArm64`.

## 2. Cross-process ding coalesced to once per cycle (`:domain:engine` iOS, `:app:ios`)

- [x] 2.1 In `DarwinCrossProcessLedgerBackend`, stop posting the Darwin notification on each `put` (keep the in-process `changes` ding and the app-side notification observer that feeds `changes`); expose an explicit cross-process notify entry point.
- [x] 2.2 In `UploadExtensionRoot`, post the cross-process notification once after `cycle.run()` returns.
- [x] 2.3 `./gradlew compileIosMainKotlinMetadata` to confirm the iOS source sets still compile.

## 3. Observed-completions seam, overlay, sticky (`:domain:status`)

- [x] 3.1 Define the `ObservedCompletionsSource` seam (`keys: StateFlow<Set<String>>` + `suspend fun refresh()`) and a no-op implementation (empty set, no-op refresh).
- [x] 3.2 Implement the pure overlay `overlay(snapshot, observed) -> (completed, pending, newestCompletionAt)` (promotion rule; empty observed = identity; `state != COMPLETED` covers `REQUESTED`/`FAILED`); unit-test in commonTest.
- [x] 3.3 Implement the sticky operator `S' = (S ∪ fresh) ∩ flatten(pendingByAsset)` as a stateful `scan` over `(rawObserved, snapshot)`; unit-test the retain-until-confirmed and self-pruning behavior in commonTest.
- [x] 3.4 Rewire `LedgerSyncStatusSource`: add the `ObservedCompletionsSource` constructor param, collect `watcher.snapshot` × permission × gallery × observed, apply sticky then overlay, mint `SyncProgress` (overlaid `completed`/`pending`, `lastFinishedAt = snapshot.newestCompletionAt`); observed seeds empty and does not gate the first `Ready`.
- [x] 3.5 Update `LedgerSyncStatusSource` tests (Loading→Ready gating on snapshot/permission/gallery only; ledger re-mint; observed-promotes-before-write; released-key-does-not-revert; constants).

## 4. Refresh cadence in the container (`:domain:presentation`)

- [x] 4.1 Add a foreground `Flow<Boolean>` and a refresh hook (the `ObservedCompletionsSource` or its `refresh`) to `StatusContainerHost`; run a poll intent that refreshes on foreground-enter and re-refreshes on a bounded interval while foreground AND projected pending > 0, stopping on drained/backgrounded.
- [x] 4.2 Unit-test the cadence with virtual time (polls while pending>0; stops at pending=0; stops on background; resumes on foreground).

## 5. iOS wiring (`:app:ios`)

- [x] 5.1 Implement the iOS `ObservedCompletionsSource`: `refresh()` calls `fetchJobsWithAction(.acknowledge)`, keeps `succeeded`, maps to keys via `destination.URL.lastPathComponent`, updates the `StateFlow`; strictly read-only (no acknowledge/retry), guarded by `backgroundUploadSupported()`, fetch off the main dispatcher.
- [x] 5.2 In `SnapSyncRoot`: construct the iOS source, inject it into `LedgerSyncStatusSource`; own a foreground `MutableStateFlow<Boolean>` injected into the container; expose `onForeground()`/`onBackground()`.
- [x] 5.3 In `iOSApp.swift`: drive `SnapSyncRoot.shared.onForeground()/onBackground()` from `scenePhase` (replacing the spike's probe hook).
- [x] 5.4 Remove the spike: `SnapSyncRoot.probeUploadJobs` + its imports, and the spike comment/hook in `iOSApp.swift`.

## 6. Desktop harness (`:app:desktop`)

- [x] 6.1 Update `Main.kt` to construct `StatusContainerHost` with the no-op `ObservedCompletionsSource` and an always-true foreground flow (`flowOf(true)`), so the harness still compiles and every forged UI state renders as before. (Satisfied by trailing-default constructor params — no edit needed.)

## 6b. In-progress caption (`:domain:ui`, `sync-status-screen`)

- [x] 6b.1 `StatusScreen` omits the `"{inProgress} in progress"` label when `inProgress == 0` (show only the last-sync age, or no detail line); add a UI test for the 0-in-progress case.

## 7. Verify

- [x] 7.1 `./gradlew build` (compiles all targets + runs JVM tests, including the new overlay/sticky/cadence/contract tests).
- [x] 7.2 `./gradlew compileIosMainKotlinMetadata` (iOS source sets compile).
- [ ] 7.3 On device: sideload, sync ~20 photos, confirm the count + "in progress" caption advance live and reach COMPLETE before the extension runs; confirm the ledger still reaches `COMPLETED` and the extension remains the only writer (no flicker on handoff).
