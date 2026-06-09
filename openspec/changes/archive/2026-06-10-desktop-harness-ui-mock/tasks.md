# Tasks: desktop-harness-ui-mock

## 1. Build scaffolding

- [x] 1.1 Add Orbit 10.0.0 (`orbit-core`, `orbit-compose`, `orbit-test`) and coroutines entries to `gradle/libs.versions.toml`
- [x] 1.2 Create KMP module skeletons (`commonMain` + jvm target only) for `:domain:sync`, `:domain:presentation`, `:domain:ui`, `:domain:ui:components`; add includes to `settings.gradle.kts`; verify `./gradlew build` is green and Orbit resolves/compiles on Kotlin 2.4.0

## 2. Contract and presentation

- [x] 2.1 `:domain:sync`: `SyncStatus(pending, completed)` + `SyncStatusSource { val status: Flow<SyncStatus> }`
- [x] 2.2 `:domain:presentation`: sealed `UiState` (`Idle`, `Uploading(done, total)`) + Orbit container observing `SyncStatusSource`, reducing latest-snapshot-only (Idle when `pending == 0`; otherwise `done = completed`, `total = pending + completed`)
- [x] 2.3 Container unit tests in `commonTest` via `orbit-test`: idle mapping, progress mapping, latest-snapshot-replaces-state (spec scenarios 1:1)

## 3. Design system and status screen

- [x] 3.1 `:domain:ui:components`: `AppTheme`, `ScreenLayout(title) { content }`, `StatusText(text)`, `UploadProgress(done, total)` with Material 3 skin — semantic-only rules (no appearance/Modifier params, no M3 types in signatures, M3 imports only in this module)
- [x] 3.2 `:domain:ui`: status screen rendering `UiState` through `App*` components only (Idle = quiescent "up to date"; Uploading = "X of N" + progress indication)

## 4. CI groundwork for UI tests

- [x] 4.1 Add Xvfb step to `.github/workflows/build.yml` (JetBrains incantation: `sudo Xvfb :1 -screen 0 1920x1080x24 -extension RANDR +extension GLX &`, export `DISPLAY=:1.0`)
- [x] 4.2 Add ONE trivial Compose UI test (status screen composes / shows idle) with `compose.desktop.uiTestJUnit4` + `compose.desktop.currentOs`; push and verify it passes on CI before writing further UI tests (fallback if it can't be made to pass: exclude UI tests from CI, container tests remain the gate; record the outcome)

## 5. Status screen UI tests

- [x] 5.1 Compose UI tests covering the spec scenarios: Idle rendering; Uploading shows "3 of 10" and 30% progress indication

## 6. Desktop harness

- [x] 6.1 `:app:desktop`: `PanelController` exposing named display-override methods writing `SyncStatus` snapshots into a `MutableStateFlow` wrapped as `SyncStatusSource` (single mutation path; no scenario machinery)
- [x] 6.2 Dual-pane window: left = real status screen inside fixed ~390×844 phone frame with visible bezel (proportions survive window resize); right = utilitarian raw-M3 control panel with override buttons reaching every supported state (idle, uploading variants); both bound to the same Orbit container
- [x] 6.3 Manual verification pass: `./gradlew :app:desktop:run` — window titled "SnapSync", harness layout per spec, each override button drives the screen; close exits cleanly

## 7. Wrap-up

- [x] 7.1 Full local `./gradlew build` green; review module dependency graph matches design (ui ↛ sync, components ↛ presentation, M3 contained)
