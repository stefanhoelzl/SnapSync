# Delta: permission-gate

## ADDED Requirements

### Requirement: Permission domain contracts

The permission domain (`:domain:permission`) SHALL define `PermissionStatus` with exactly three values — `NOT_DETERMINED`, `DENIED`, `GRANTED` — and two ports:

- `PermissionStatusSource` (state port): exposes `permission: StateFlow<PermissionStatus>`, a level-triggered state holder whose current value is always available synchronously. Every emission is the whole truth; consumers depend only on the latest value.
- `PermissionRequester` (command port): `fun request()` and `fun openSettings()`. Both are fire-and-forget — they MUST NOT return values and MUST NOT suspend. Status changes resulting from a command arrive exclusively via `PermissionStatusSource`.

Implementations MAY be a single object implementing both ports, but consumers SHALL depend on each port separately.

#### Scenario: Truth arrives only via the state port
- **WHEN** `request()` is invoked and the platform resolves the request
- **THEN** the new status is observed as an emission of `PermissionStatusSource.permission`, and `request()` itself communicates nothing

#### Scenario: Duplicate requests are harmless
- **WHEN** `request()` is invoked twice before the first resolves
- **THEN** no error occurs and the source ends up holding the single resolved status

### Requirement: Full library access is required

v1 SHALL treat only a full library grant as `GRANTED`. Any platform adapter MUST map partial or unchangeable grants to `DENIED` (on iOS: `.denied`, `.restricted`, and `.limited` → `DENIED`; `.authorized` → `GRANTED`; `.notDetermined` → `NOT_DETERMINED`). This prevents the screen from ever reporting "Sync complete" over a partially-synced library. ⚠️ Accepted risk: on managed/restricted devices the Denied gate's Settings CTA is a dead end; revisit only if such a report appears.

#### Scenario: Limited grant keeps the gate up
- **WHEN** a platform reports a limited (partial) library grant
- **THEN** the permission status is `DENIED` and the gate remains visible with the Settings call to action

### Requirement: Gate replaces the status hero

Whenever the observed permission status is not `GRANTED`, the status screen SHALL render the permission gate instead of the sync status hero — permission-first precedence, regardless of the sync snapshot. The gate is composed from `StatusHero` plus a `PrimaryButton`; it is not a separate screen and introduces no navigation.

| Permission | Indicator | Headline | Detail | Button |
|---|---|---|---|---|
| `NOT_DETERMINED` | Photos (neutral) | "Sync your photos" | "SnapSync needs access to your photo library" | "Allow access" |
| `DENIED` | Error | "Photo access denied" | "Turn on photo access in system settings" | "Open Settings" |

#### Scenario: First launch shows the ask
- **WHEN** permission is `NOT_DETERMINED`
- **THEN** the screen shows the neutral photos indicator, "Sync your photos", "SnapSync needs access to your photo library", and an "Allow access" button — and no sync status hero

#### Scenario: Denied shows the settings path
- **WHEN** permission is `DENIED`
- **THEN** the screen shows the error indicator, "Photo access denied", "Turn on photo access in system settings", and an "Open Settings" button

#### Scenario: Gate wins over any sync state
- **WHEN** permission is `DENIED` while the sync source holds a Failed snapshot
- **THEN** the screen shows the Denied gate, not the failed hero

#### Scenario: Granted reveals the status hero
- **WHEN** permission becomes `GRANTED`
- **THEN** the gate disappears and the screen renders the sync status hero derived from the current sync snapshot

### Requirement: Gate intents route through the container

The presentation container SHALL expose its first intents — `onRequestPermission` and `onOpenSettings` — which call the injected `PermissionRequester`'s `request()` and `openSettings()` respectively. The UI layer MUST NOT call platform permission APIs directly. The system permission dialog SHALL fire only from the "Allow access" button (CTA-only priming): observing `NOT_DETERMINED` MUST NOT trigger an automatic request.

#### Scenario: Allow access requests permission
- **WHEN** the user activates "Allow access"
- **THEN** the container invokes `PermissionRequester.request()` exactly as a pass-through, and any resulting state change arrives via the permission source

#### Scenario: Open Settings delegates to the platform
- **WHEN** the user activates "Open Settings"
- **THEN** the container invokes `PermissionRequester.openSettings()`

#### Scenario: No auto-request on launch
- **WHEN** the container starts observing and the permission source holds `NOT_DETERMINED`
- **THEN** `request()` is not invoked until the user activates the call to action
