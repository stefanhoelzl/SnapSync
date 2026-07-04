## MODIFIED Requirements

### Requirement: Permission domain contracts

The permission domain (`:domain:permission`) SHALL define `PermissionStatus` with exactly three values — `NOT_DETERMINED`, `DENIED`, `GRANTED` — and two ports:

- `PermissionStatusSource` (state port): exposes `permission: StateFlow<PermissionStatus>`, a level-triggered state holder whose current value is always available synchronously. Every emission is the whole truth; consumers depend only on the latest value.
- `PermissionRequester` (command port): `fun request()` and `fun openSettings()`. Both are fire-and-forget — they MUST NOT return values and MUST NOT suspend. Status changes resulting from a command arrive exclusively via `PermissionStatusSource`.

Implementations MAY be a single object implementing both ports, but consumers SHALL depend on each port separately. These contracts are consumed by the **status screen's joined-layer status line** (capability `sync-status-screen`), which renders the missing-permission state **inline** (the `NeedsAccess` health) and routes its intents — permission is no longer a hero-replacing gate; the joined layer (name, QR, share, leave) renders regardless of permission.

#### Scenario: Truth arrives only via the state port
- **WHEN** `request()` is invoked and the platform resolves the request
- **THEN** the new status is observed as an emission of `PermissionStatusSource.permission`, and `request()` itself communicates nothing

#### Scenario: Duplicate requests are harmless
- **WHEN** `request()` is invoked twice before the first resolves
- **THEN** no error occurs and the source ends up holding the single resolved status

### Requirement: Full library access is required

v1 SHALL treat only a full library grant as `GRANTED`. Any platform adapter MUST map partial or unchangeable grants to `DENIED` (on iOS: `.denied`, `.restricted`, and `.limited` → `DENIED`; `.authorized` → `GRANTED`; `.notDetermined` → `NOT_DETERMINED`). This prevents the screen from ever reporting "In sync" over a partially-synced library. When permission is not `GRANTED` while an event is configured, the joined-layer status line SHALL show the inline `NeedsAccess` affordance (per `sync-status-screen`) rather than a hero-replacing gate. ⚠️ Accepted risk: on managed/restricted devices the inline affordance's Settings path is a dead end; revisit only if such a report appears.

#### Scenario: Limited grant keeps the inline affordance up
- **WHEN** a platform reports a limited (partial) library grant while an event is configured
- **THEN** the permission status is `DENIED` and the joined-layer status line shows the inline
  "Turn on photo access" affordance with the Settings path
