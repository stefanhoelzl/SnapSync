# Proposal: desktop-harness-ui-mock

## Why

SnapSync v1 is built desktop-first: the desktop test harness is the only interactive surface for the shared UI and sync presentation while the iOS 27 upload extension remains physical-device-only. This change is the first vertical slice toward it — a UI-first mock that makes the real status screen explorable (and its architecture seams real) before any sync engine exists, so the harness UX and the module boundaries are validated at the cheapest possible moment.

## What Changes

- New Gradle modules, all Kotlin Multiplatform from day one (jvm target only; code in `commonMain` so iOS targets are later a build-file change):
  - `:domain:sync` — contract only: `SyncStatus(pending, completed)` + `SyncStatusSource { val status: Flow<SyncStatus> }` (the snapshot seam, design.md §2.3). No orchestration.
  - `:domain:presentation` — Orbit MVI container mapping `SyncStatus` → `UiState` (happy path only: `Idle`, `Uploading X of N`). Compose-free.
  - `:domain:ui` — the status screen, written exclusively against `App*` components.
  - `:domain:ui:components` — semantic design-system components + Material 3 skin (the only module allowed to import Material 3): `AppTheme`, `ScreenLayout(title)`, `StatusText`, `UploadProgress(done, total)`.
- `:app:desktop` reworked from blank window to the dual-pane harness: left = real status screen in a fixed ~390×844 phone frame; right = utilitarian control panel (display-override buttons → one `PanelController` → `MutableStateFlow<SyncStatus>`).
- Tests for MVP-permanent code only: container reduction (orbit-test) + Compose UI tests on the status screen. No tests for panel/harness scaffolding.
- CI: Xvfb step in `build.yml` (Compose Desktop UI tests require a display on Linux).
- Explicitly out of scope: sync engine, capability modules (gallery/uploader/s3/store), error states, permission gate, buttons/actions, scenario-runner machinery, theme tokens.

## Capabilities

### New Capabilities

- `sync-status-screen`: the shared status screen and its presentation — rendering sync status snapshots as Idle / Uploading X of N via the Orbit container.
- `design-system`: the semantic component rules and initial inventory — appearance-free `App*` signatures, Material 3 containment, semantic containers owning convention-bearing arrangement. Future slices extend this spec (e.g. emphasis-level buttons arrive with the permission gate).
- `desktop-test-harness`: the dual-pane desktop harness — phone-framed real UI beside a control panel whose display-override buttons force any supported sync status for manual UI exploration.

### Modified Capabilities

- `desktop-app-shell`: the "window is empty" requirement is replaced — the window now hosts the dual-pane harness as its content.

## Impact

- **Code**: new modules `:domain:sync`, `:domain:presentation`, `:domain:ui`, `:domain:ui:components`; `:app:desktop` `Main.kt` rewritten; `settings.gradle.kts` gains the module includes.
- **Dependencies**: Orbit MVI 10.0.0 (`orbit-core`, `orbit-compose`, `orbit-test`), Compose `uiTestJUnit4` + `desktop.currentOs` for tests, kotlinx-coroutines (via catalog). Orbit 10.0.0 is built with Kotlin 2.1.21 — fine for JVM consumption; klib compatibility re-checked at the iOS slice.
- **CI**: `build.yml` gains the Xvfb step (JetBrains compose-tests workflow incantation, incl. `+extension GLX`).
- **Docs**: `docs/design.md` already reflects all decisions (snapshot seam §2.3, design system §5, harness §5.1, testing §6); `openspec/specs/` gains/updates the capabilities above on archive.
