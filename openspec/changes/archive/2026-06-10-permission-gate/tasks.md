# Tasks: permission-gate

## 1. Permission domain module

- [x] 1.1 Create `:domain:permission` module (Gradle wiring mirroring `:domain:sync`) with `PermissionStatus` (`NOT_DETERMINED`, `DENIED`, `GRANTED`)
- [x] 1.2 Add `PermissionStatusSource` (`permission: StateFlow<PermissionStatus>`) and `PermissionRequester` (`fun request()`, `fun openSettings()`) with KDoc stating the CQS contract (truth arrives only via the source) and the full-access mapping rule (limited/restricted → DENIED)

## 2. StateFlow seam upgrade

- [x] 2.1 Change `SyncStatusSource.status` to `StateFlow<SyncStatus>`; update its KDoc (level-triggered state holder, value available synchronously at construction)
- [x] 2.2 Compute `StatusContainerHost`'s initial state from `source.status.value` (and the permission source's value) instead of the hardcoded `UiState.NeverSynced`
- [x] 2.3 Update `StatusContainerHostTest` fakes to `MutableStateFlow` and add the no-cold-start-guess test (source pre-seeded Failed → first observed state is Failed)

## 3. Presentation: combine + intents

- [x] 3.1 Extend `UiState` with `PermissionAsk` and `PermissionDenied`
- [x] 3.2 Combine permission and sync sources in `StatusContainerHost` with permission-first precedence (non-granted → gate variant regardless of snapshot)
- [x] 3.3 Add `onRequestPermission` / `onOpenSettings` intents delegating to the injected `PermissionRequester`; no auto-request when observing `NOT_DETERMINED`
- [x] 3.4 orbit-test coverage: precedence reduction (denied + failed snapshot → `PermissionDenied`), granted reveals sync state, intents call the requester (spy), no auto-request on start

## 4. Design system

- [x] 4.1 Add `PrimaryButton(label, onClick)` to `:domain:ui:components` (M3 filled button inside; no Modifier/appearance params)
- [x] 4.2 Add `StatusIndicator.Photos` with a neutral photo-library glyph in `StatusHero`'s indicator rendering

## 5. Status screen gate

- [x] 5.1 Render `PermissionAsk` (Photos indicator, "Sync your photos", "SnapSync needs access to your photo library", "Allow access") and `PermissionDenied` (Error, "Photo access denied", "Turn on photo access in system settings", "Open Settings") in `StatusScreen`, wiring button callbacks to the container intents
- [x] 5.2 Extend `StatusScreenTest` with both gate states (copy + button presence) and a gate-wins case

## 6. Harness rework

- [x] 6.1 `PanelController`: add the permission `MutableStateFlow` cell, implement `PermissionStatusSource` + fake `PermissionRequester` (armed-outcome field; `request()` writes the armed value, `openSettings()` logs), make sync presets force `GRANTED`
- [x] 6.2 `ControlPanel`: regroup into labeled "Permission" / "Sync" groups and the "Next request →" armed-outcome control; no current-permission readout
- [x] 6.3 Wire `Main.kt` composition root (permission source + requester into the container)
- [x] 6.4 Manual harness walk: ask→granted, ask→denied, denied→granted, revoked-and-restored (forge Failed → Denied → Granted → Failed re-emerges)

## 7. Docs

- [x] 7.1 Amend `docs/design.md` §5/§5.1: drop the enable toggle and "Back up now", state the full-access requirement and ⚠️ restricted-fold risk, note the StateFlow seam and the composition-root ordering constraint (read store → construct sources → construct container)
- [x] 7.2 Run the full build/test suite and fix fallout
