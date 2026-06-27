## 1. Presentation: new state + reduction

- [x] 1.1 Add `data class PermissionBlocked(val permission: PermissionStatus) : UiState` to `domain/presentation/.../UiState.kt`, with a doc comment noting `permission ∈ {NOT_DETERMINED, DENIED}` and that it carries no counts.
- [x] 1.2 Rewrite `StatusContainerHost.reduceFrom` guard: `config == null → Setup`; else `permission != GRANTED → PermissionBlocked(permission)`; else the existing `EventStatus`/snapshot chain. Remove the old `!storageConnected || permission != GRANTED → Setup` short-circuit.
- [x] 1.3 Update the comment block on `reduceFrom` to describe the new single-input gate + permission precedence (drop the "two-input setup precedence" wording). Also updated the now-stale `Loading` comment.

## 2. UI: render PermissionBlocked

- [x] 2.1 In `domain/ui/.../StatusScreen.kt`, add a `is UiState.PermissionBlocked` branch rendering a `StatusHero` + `PrimaryButton` in the column, switching on `state.permission`: `NOT_DETERMINED` → Photos indicator / "Allow photo access" / "SnapSync needs your photo library to back it up." / "Allow access" → `onRequestPermission`; `DENIED` → Error indicator / "Photo access turned off" / "SnapSync needs photo access to continue backing up your library." / "Open Settings" → `onOpenSettings`.
- [x] 2.2 Confirm no Material 3 type leaks into any `App*` signature (compose existing `StatusHero` + `PrimaryButton` only).

## 3. Presentation tests

- [x] 3.1 Replace the existing `denied permission shows the setup gate over any sync snapshot` test in `StatusContainerHostTest` with: config present + `DENIED` (over a non-trivial snapshot) → `PermissionBlocked(DENIED)`.
- [x] 3.2 Add: config present + `NOT_DETERMINED` → `PermissionBlocked(NOT_DETERMINED)`.
- [x] 3.3 Add: config present + `DENIED` while `EventStatus.Joining` → `PermissionBlocked(DENIED)` (permission outranks join).
- [x] 3.4 Add/confirm: config absent (any permission) → `UiState.Setup` (added `absent config outranks a loading snapshot`); config present + `GRANTED` paths still reduce as before (existing tests unchanged + the `permission blocks a loading snapshot` rename). Also added `revoking permission mid-sync blocks the running hero`.
- [x] 3.5 Ensure these tests live in `commonTest` so they run on both JVM and `iosSimulatorArm64`.

## 4. UI render tests

- [x] 4.1 Add Compose UI tests asserting the `PermissionBlocked(NOT_DETERMINED)` rendering (headline, detail, "Allow access" button wiring to `onRequestPermission`).
- [x] 4.2 Add Compose UI tests asserting the `PermissionBlocked(DENIED)` rendering (headline, detail, "Open Settings" button wiring to `onOpenSettings`).

## 5. Integration test

- [x] 5.1 ~~In `:test:integration`, add a revoke-while-synced case~~ — **deviation:** the `:test:integration` module does not exist yet (planned per CLAUDE.md), and this change is display-only (the engine is not involved — permission is read by the status source, faked at the seam). The revoke-while-synced flow is instead covered at the presentation level by `revoking permission mid-sync blocks the running hero` in `StatusContainerHostTest`, which exercises the real `StatusContainerHost` reduction across config + permission + sync. Standing up the integration module is left as the separate planned work.

## 6. Test harness

- [x] 6.1 Add `PermissionBlocked(NOT_DETERMINED)` and `PermissionBlocked(DENIED)` to the `app/desktop` control-panel forge presets so both states can be reviewed without a device.

## 7. Verify

- [x] 7.1 `./gradlew build` (compiles all targets + JVM tests, headless) — BUILD SUCCESSFUL.
- [x] 7.2 `./gradlew compileIosMainKotlinMetadata` (iOS source-set proxy) — BUILD SUCCESSFUL.
- [ ] 7.3 `./gradlew :app:desktop:run` and visually confirm both `PermissionBlocked` states via the forge presets — **needs a display** (only `:app:desktop:run` opens a real window); left for the user / a display-backed session. The states are covered by the headless `:domain:ui` render tests.
