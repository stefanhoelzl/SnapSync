## 1. Status domain — re-source completed/pending from the ledger (`:domain:status`)

- [x] 1.1 Add a `LedgerCounts(completed: Int, pending: Int)` data class in `:domain:status` (`commonMain`, package `app.snapsync.status`).
- [x] 1.2 Add the `LedgerCountsSource` seam (`counts: StateFlow<LedgerCounts>` + `suspend fun refresh()`), backed by an injected `suspend () -> LedgerCounts`; on read failure retain the last good value (seed `LedgerCounts(0, 0)`).
- [x] 1.3 Add a settable fake `LedgerCountsSource` for tests and the desktop harness.
- [x] 1.4 Replace `ListingSyncStatusSource` with the ledger-backed `SyncStatusSource` factory taking `(LedgerCountsSource, PermissionStatusSource, GalleryStatusSource, CoroutineScope)`; combine the three inputs; emit `Ready` once all three have a first value; mint `SyncProgress` with `completed`/`pending` from the counts, `total` from gallery.
- [x] 1.5 Update `SyncProgress` doc/contract: `completed` = ledger complete-asset count, `pending` = ledger in-flight count (same `aggregates()` read), keep `n = min(completed, total)` and the three-state classification unchanged.
- [x] 1.6 Delete `CompletedAssetsSource` and `InFlightSource` (and their fakes) from `:domain:status`; remove the per-device-listing / `EventFilesSource` wiring that only served upload completeness (leave any download-side seam usage intact).
- [x] 1.7 Update/replace the `commonTest` suites: ledger-backed source (Loading→Ready gating, count/gallery/permission re-mint, undiscovered→IN_PROGRESS, failed-read retains last), `LedgerCountsSource` fake behavior, and `SyncProgress` classification. Ensure they run on JVM **and** `iosSimulatorArm64`.

## 2. Presentation & status line (`:domain:presentation`, `:domain:ui`, `:domain:ui:components`)

- [x] 2.1 Confirm the `SyncHealth`/`Arrow` reduction is unchanged (arrow shown = `completed < total`, pulsing = `pending > 0`); no `UiState` field is added — the label derives from arrow levels.
- [x] 2.2 In `AppStatusLine`, derive the `Syncing` label from arrow activity: any `Pulsing` → "Synchronization ongoing…"; else (any shown, none pulsing) → "Synchronization pending…"; keep "In sync" and the loading first-frame label.
- [x] 2.3 Skin (`:domain:ui:components`): tint a `Static` arrow gray (`onSurfaceVariant`) and a `Pulsing` arrow the brand `primary` (keep the infinite fade animation).
- [x] 2.4 Skin: remap the LED-style `StatusIndicator` accent (`Complete`, and the pulsing arrow) from green to `primary`; remove the standalone green constant.
- [x] 2.5 Update the components/UI tests for the two new labels and the state→label mapping (JVM offscreen render).
- [x] 2.6 Reconcile the stale "green dot" references in `sync-status-screen`'s legacy count-line requirement text so the spec doesn't contradict the primary-accent skin (cleanup).

## 3. iOS PhotoKit extension — post the liveness ding (`:app:ios:photokit-extension`, `:capability:upload`)

- [x] 3.1 In `UploadExtensionRoot.process()`, after `cycle.run()` returns (any tri-state result), post a payload-free named Darwin notification via `CFNotificationCenter` Darwin notify center; best-effort (a post failure does not change the returned result).
- [x] 3.2 Define the notification name as a single shared constant reused by the app-side observer (task 5.x); keep it out of `LedgerBackend` (the backend still posts nothing).

## 4. iOS app-driven tier — in-process refresh (`:app:ios:url-session-upload`, `:capability:upload`)

- [x] 4.1 After each `UploadCycle.run()` in the `BackgroundUploadPump`, trigger the in-process `LedgerCountsSource.refresh()` (fire-and-forget) via an injected callback/seam, without disturbing single-flight or the `PROCESSING` re-arm.
- [x] 4.2 Cover the "cycle → refresh invoked" behavior in the pump's JVM/`iosSimulatorArm64` tests (fake refresh).

## 5. iOS app shell — ledger read + Darwin observer (`:app:ios`)

- [x] 5.1 In `SnapSyncRoot`, construct the iOS `LedgerCountsSource` supplying `suspend () -> LedgerCounts` that calls only `iosLedgerBackend().aggregates()` (read-only, no `LedgerWriter`); map to `LedgerCounts`.
- [x] 5.2 Build the ledger-backed `SyncStatusSource` from `(LedgerCountsSource, permission, gallery, scope)`; remove the `CompletedAssetsSource` (HTTP listing) construction from the upload status path.
- [x] 5.3 Register a foreground-only Darwin observer for the extension liveness notification (register on `onForeground()`, unregister on `onBackground()`) that calls `LedgerCountsSource.refresh()` and re-emits; wire the foreground signal to the same refresh.
- [x] 5.4 Wire the app-driven pump's refresh callback (task 4.1) to the same `LedgerCountsSource.refresh()`.

## 6. Docs

- [x] 6.1 Rewrite `docs/design.md §2.3` — remove "No ledger Darwin notification"; describe the composition-root liveness ding (extension post + foreground-only app observer) as the invalidation signal for status.
- [x] 6.2 Rewrite `docs/design.md §2.4` — `completed`/`pending` are ledger-sourced (one `aggregates()` read); classification reads the ledger; state the **no-deletion-during-an-active-event** invariant + `event-rejoin-reconciliation` as the load-bearing safety premise; note the download direction is unchanged.

## 7. Validate & build

- [x] 7.1 `npx --yes @fission-ai/openspec@1.4.1 validate notify-driven-status --strict` passes.
- [x] 7.2 `./gradlew build` (compiles all targets + JVM/offscreen UI tests) passes.
- [x] 7.3 `./gradlew compileIosMainKotlinMetadata` (Linux iOS proxy) passes.
- [x] 7.4 On-device smoke (per `CLAUDE.md`): with the app foreground, an extension `process()` run moves the status line live (pending → ongoing/pending → In sync) with no foreground round-trip; verify uploads landed in the bunny zone.
