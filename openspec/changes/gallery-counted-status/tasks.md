## 1. New `:domain:gallery` module (the N seam)

- [x] 1.1 Create the `:domain:gallery` module (Gradle build file, KMP targets matching `:domain:permission`: commonMain/jvmMain/iosMain + commonTest); register in `settings.gradle.kts`.
- [x] 1.2 Define `GalleryStatusSource { val size: StateFlow<Int> }` in `commonMain` (package `app.snapsync.gallery`), with KDoc stating the count is whole-library today and MUST share discovery's predicate when scoping lands.
- [x] 1.3 Add a settable `InMemoryGalleryStatusSource` (in `commonMain`, since the harness is main code) whose `size` can be driven to any non-negative value; reusable by harness and integration tests.
- [x] 1.4 `commonTest`: source emits its current value synchronously, re-emits on set, and seeds a real (non-placeholder) value — runs on JVM and `iosSimulatorArm64`.

## 2. `:domain:status` — three-input source and three-state model

- [x] 2.1 Add `total: Int` to `SyncProgress`; keep `pending`/`completed`/`failed`/`active`/`estimatedRemaining`/`lastFinishedAt`. Added `synced` (clamped) computed property.
- [x] 2.2 Replace `SyncState` with `{ IN_PROGRESS, COMPLETE, NOTHING_TO_SYNC }`; rewrite the computed `state`: `total == 0 → NOTHING_TO_SYNC`; `synced >= total → COMPLETE`; else `IN_PROGRESS`. Remove `SUSPENDED`/`NEVER_SYNCED`/`INCOMPLETE`.
- [x] 2.3 Add `:domain:gallery` as an **implementation**-scope dependency of `:domain:status`.
- [x] 2.4 Change `LedgerSyncStatusSource` factory to take `GalleryStatusSource` and `combine` ledger × permission × gallery; mint `total` from gallery size; seed `Loading` and emit first `Ready` only after all three first-emit.
- [x] 2.5 Update `LedgerSyncStatusSourceTest` (commonTest): Loading seed; first Ready carries `completed` + `total`; gallery-change re-mints; permission flip re-mints; constants (`failed==0`, `estimatedRemaining==null`).
- [x] 2.6 Update `SyncProgress`/classification unit tests to the three-state table incl. the clamp (`completed=6,total=5 → COMPLETE`, n shown as 5), virgin-with-photos (`completed=0,total=5 → IN_PROGRESS`), and pending-ignored.

## 3. `:domain:presentation` — UiState reduction

- [x] 3.1 Rework `UiState`: `InProgress(synced: Int, total: Int)`, `Completed(total: Int, finishedAgo: String)`, `NothingToSync`; remove `NeverSynced`, `Suspended`, `Incomplete`, and the InProgress `fraction`/`estimate` fields. Keep `Loading` and `Setup`.
- [x] 3.2 Update the container reduction: `Ready` → one of the three states with `synced = min(completed, total)`; keep the setup-gate precedence and the `Loading`-under-satisfied-gate rule unchanged.
- [x] 3.3 Remove estimate formatting; keep relative-time formatting + minute-tick for `Completed.finishedAgo` only (null-guarded).
- [x] 3.4 Update presentation tests (commonTest): InProgress carries synced/total; Completed carries total + relative time; NothingToSync at `total==0`; overshoot clamps; no-cold-start-guess still holds; relative-time still ages.

## 4. `:domain:ui` + `:domain:ui:components` — LED hero

- [x] 4.1 Add `StatusIndicator.InProgress`/`Complete` (LED dots); remove now-unused `Warning`/`Progress(fraction)`. Kept `Success`/`Waiting`/`Error`/`Photos`/`Loading` (still used by the setup gate + loading).
- [x] 4.2 In `:domain:ui:components` (Material 3 skin only), render `InProgress` as a yellow LED dot and `Complete` as a green LED dot; `Loading` stays the indeterminate indicator with no dot. No color/shape in any `App*` signature.
- [x] 4.3 Update `StatusHero`/`StatusScreen` to the dot-over-count-line layout (no headline, no ring): InProgress "{synced} of {total} images synced"; NothingToSync "Nothing to sync yet"; Completed "{total} images synced" + muted relative time; Loading "Loading …".
- [x] 4.4 Update `:domain:ui` screen tests for each rendered state and the textual counts (offscreen/headless, no display).

## 5. iOS wiring (`:app:ios`, untested)

- [x] 5.1 Implement the iOS `GalleryStatusSource` (`PhotoLibraryGalleryStatus` in `domain/gallery/iosMain`, mirroring `PhotoLibraryPermission`'s placement) backed by a PhotoKit count (whole-library, matching current discovery), re-reading on `photoLibraryDidChange`, foreground, and `refresh()` (join). The Obj-C change observer is a separate class (a Kotlin interface can't mix with an Obj-C supertype).
- [x] 5.2 Wire it into `SnapSyncRoot` so `LedgerSyncStatusSource` receives the real gallery source (and `resetForReprovision` dings `refresh()`); `:app:ios` declares the `:domain:gallery` dep; `./gradlew compileIosMainKotlinMetadata` green.

## 6. Desktop harness (`:app:desktop`)

- [x] 6.1 The harness forges the projection directly (it doesn't run `LedgerSyncStatusSource`), so the gallery `N` maps to the forged `SyncProgress.total`; presets rewritten to the new states + a live "Gallery size (N) − / +" control added to the panel.
- [x] 6.2 Presets cover every state: nothing-to-sync (N=0), in-progress (12 of 47), completed, overshoot (6 of 5 → clamps); the N±control forges discovery-lag/overshoot live. (Harness compiles; an actual `:app:desktop:run` needs a display and is a manual step.)

## 7. Integration tests (`:test:integration`)

- [ ] 7.1 DEFERRED — `:test:integration` does not exist yet (a separate "planned" module per CLAUDE.md). The intended coverage already exists at the unit level: `LedgerSyncStatusSourceTest` assembles engine → status → gallery, and `StatusContainerHostTest` assembles status → presentation. Add the assembled-stack test when that module is created.

## 8. Verify & sync specs

- [x] 8.1 `./gradlew build` green (all targets compile, JVM tests pass) and `./gradlew compileIosMainKotlinMetadata` green.
- [x] 8.2 `npx openspec validate gallery-counted-status --strict` passes; after merge, sync deltas into `openspec/specs/` and archive the change.
