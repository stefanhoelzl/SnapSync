## Why

When an event has been joined (config present) and the user later revokes photo access in system
Settings, the app drops the running status screen and shows the **full setup gate** again — the same
cold two-card onboarding a brand-new user sees. That reads as "start over" rather than "your backup
hit a snag." Permission is the only switch the user ever flips after setup, so a revocation should
surface as an actionable error *on the status screen*, not a regression to onboarding.

## What Changes

- **The setup gate's guard narrows to `config == null`.** Today the gate shows whenever config is
  absent **or** permission is not `GRANTED`. After this change the gate is shown **only** while no
  event is connected (`config == null`). Permission is no longer part of the gate guard. **BREAKING**
  (spec-level): the "two-input setup precedence" requirement becomes single-input.
- **The setup gate keeps both cards** (connect storage × allow photo access) while it is shown —
  unchanged internally. Side effect: the storage card's *satisfied/collapsed* state is now
  unreachable (config-present means the gate is gone), so the storage card is always the pending
  "scan your QR" instruction.
- **Permission becomes a status-screen concern once config is present.** A new `UiState` variant
  `PermissionBlocked(permission)` renders the non-granted permission states as the status hero:
  - `NOT_DETERMINED` → "Allow photo access" + an **Allow access** CTA that fires `request()` (covers
    the case where the QR is scanned before permission was ever requested — otherwise a dead end).
  - `DENIED` → "Photo access turned off" + an **Open Settings** CTA.
- **The reduction simplifies.** With permission known synchronously, the guard is: `config == null`
  → `Setup`; else `permission != GRANTED` → `PermissionBlocked(permission)`; else the existing
  join/sync chain. No ledger inference, no "was joined" history, no Loading-window special case.
- **Display-only.** Engine and background-upload enablement already gate on `GRANTED` and are
  unchanged; only what the screen shows while permission is not granted changes.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `setup-gate`: the "two-input setup precedence" requirement narrows to a single input — the gate is
  shown only when `config == null`; permission status no longer gates the screen. Permission rendering
  while config is present moves out of the gate.
- `sync-status-screen`: add the `UiState.PermissionBlocked` reduction and rendering, with precedence
  above the join/sync chain (config present + permission not `GRANTED` → `PermissionBlocked`); update
  the `Loading` clause that currently short-circuits to the gate on any non-`GRANTED` permission.

## Impact

- Code: `domain/presentation` (`UiState.kt` new variant; `StatusContainerHost.reduceFrom` guard),
  `domain/ui` (`StatusScreen.kt` renders `PermissionBlocked` as a `StatusHero` + `PrimaryButton`).
- Tests: `domain/presentation` reduction tests (denied/not-determined with config present →
  `PermissionBlocked`; the existing "denied permission shows the setup gate over any sync snapshot"
  test is replaced), `domain/ui` render tests for both permission states, `:test:integration`
  revoke-while-synced case.
- Test harness: `app/desktop` control-panel forge presets gain the two `PermissionBlocked` states.
- No change to the permission seam contracts (`permission-gate`), the engine, or background upload.
