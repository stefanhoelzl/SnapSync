# Proposal: permission-gate

## Why

Backup is always-on by design: photo-library permission is the only switch the user ever flips, so the permission gate is the entire remaining v1 product surface (the enable toggle and "Back up now" button are dropped from the design). Without the gate, the status screen lies on any device that hasn't granted access — and the slice-2 engine will need the same permission contract this slice introduces.

## What Changes

- New `:domain:permission` module with the permission contracts: `PermissionStatus` (`NOT_DETERMINED`, `DENIED`, `GRANTED`), `PermissionStatusSource` (state port), `PermissionRequester` (command port: `fun request()`, `fun openSettings()` — fire-and-forget, no return values).
- v1 requires **full** library access: iOS `.limited` and `.restricted` both map to `DENIED` (no false "Sync complete" for partially-granted libraries; ⚠️ accepted risk: the Settings CTA is a dead end on managed/restricted devices).
- The status screen gains a permission gate that **replaces the hero** whenever permission ≠ granted: NotDetermined renders an onboarding ask ("Sync your photos" + "Allow access" button, CTA-only priming — no auto-request on launch); Denied renders a problem state ("Photo access denied" + "Open Settings").
- `StatusContainerHost` combines the permission source with the sync source, reduces to an extended `UiState` (two new gate variants), and gains its first intents: `onRequestPermission`, `onOpenSettings`.
- **BREAKING (internal seam):** `SyncStatusSource.status` and the new permission source become `StateFlow` (level-triggered state holders); the container computes its initial state from real source values instead of guessing `NeverSynced` — no cold-start flash, no Loading state.
- Design system grows demand-driven: `PrimaryButton(label, onClick)` (first button; distinct component per the existing variant rule, no emphasis enum) and `StatusIndicator.Photos` (neutral photo-library glyph for the first-launch ask).
- Control panel reworked into three labeled groups: Permission presets (NotDetermined / Denied / Granted — set permission only), Sync presets (existing seven — additionally force permission to Granted so their screen is always visible), and an armed-outcome control ("next request → grants / denies") read by the harness's fake `PermissionRequester`; fake `openSettings()` only logs.
- `docs/design.md` §5 amended: enable toggle and "Back up now" removed, full-access requirement and restricted-fold risk recorded, StateFlow seam noted.

## Capabilities

### New Capabilities

- `permission-gate`: permission domain contracts (status values, state port, command ports), platform mapping rules (full access only), gate UI states with locked copy, CTA-only priming, and gate-over-status precedence.

### Modified Capabilities

- `sync-status-screen`: the snapshot seam requirement changes from `Flow` to `StateFlow` — sources hold the current truth synchronously, and the container's initial UI state is computed from real source values rather than a `NeverSynced` guess.
- `design-system`: inventory grows by `PrimaryButton` and `StatusIndicator.Photos`.
- `desktop-test-harness`: display overrides split into permission and sync groups with asymmetric writes (sync presets force Granted), plus the armed fake-requester control.

## Impact

- New module `:domain:permission`; `:domain:presentation` (container combine, intents, new `UiState` variants), `:domain:sync` (seam type), `:domain:ui` (gate rendering), `:domain:ui:components` (`PrimaryButton`, `StatusIndicator.Photos`), `:app:desktop` (`PanelController` grows permission cell + armed requester; `ControlPanel` regrouped).
- Tests: orbit-test for intents (spy requester) and combined reduction; `StatusScreenTest` gate states; `SyncStatusTest` untouched.
- Docs: `docs/design.md` §5/§5.1 amendment.
- No external dependencies added; iOS adapter (`PHPhotoLibrary`) is a later slice — only the contracts ship now.
