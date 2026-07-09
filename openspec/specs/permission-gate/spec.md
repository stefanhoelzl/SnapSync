# permission gate Specification

## Purpose

The photo-library permission contracts and the inline gate that replaces the status hero until a full grant exists: permission is the only switch the user ever flips — sharing runs on its own once the grant is in place and an event is joined.
## Requirements
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

### Requirement: iOS PhotoKit permission adapter

The permission domain SHALL provide an iOS platform adapter (in `:domain:permission` `iosMain`) — a single object implementing **both** `PermissionStatusSource` and `PermissionRequester` against PhotoKit. It SHALL seed its `permission` `StateFlow` synchronously from `PHPhotoLibrary.authorizationStatus(for: .readWrite)` at construction (the platform exposes the current status synchronously, so the permission seam keeps its synchronous-real guarantee). It SHALL request the `.readWrite` (full-library) access level. The status mapping is the one already required by *Full library access is required* (`.authorized → GRANTED`, `.notDetermined → NOT_DETERMINED`, `.limited/.denied/.restricted → DENIED`).

`request()` SHALL invoke `PHPhotoLibrary.requestAuthorization(for: .readWrite)` and update the source from its completion callback; `openSettings()` SHALL open the app's system settings surface (`UIApplication.openSettingsURLString`). Both are fire-and-forget per the port contract.

#### Scenario: Initial status is read synchronously
- **WHEN** the adapter is constructed
- **THEN** `permission.value` immediately reflects the current PhotoKit authorization status, mapped per the contract, without waiting for an emission

#### Scenario: Granting via the system dialog updates the source
- **WHEN** `request()` is invoked and the user grants full access in the system dialog
- **THEN** `permission` emits `GRANTED`, and `request()` itself returns nothing

#### Scenario: Limited grant maps to denied
- **WHEN** the user grants *limited* (partial) library access
- **THEN** `permission` holds `DENIED`

### Requirement: Permission liveness across the system Settings round-trip

The iOS permission adapter SHALL keep its `permission` `StateFlow` current with changes made outside the app. Because PhotoKit exposes no authorization-change observer and the user can change access in system Settings while the app is backgrounded, the adapter SHALL treat the app returning to the foreground as a refresh trigger: it SHALL observe `UIApplication.didBecomeActiveNotification`, re-read `authorizationStatus`, and emit the mapped status. The observer SHALL be registered for the app's lifetime.

#### Scenario: Revoking access in Settings is reflected on return
- **WHEN** access is `GRANTED`, the user backgrounds the app, revokes photo access in system Settings, and returns to the foreground
- **THEN** the app re-reads the status and `permission` emits `DENIED`

#### Scenario: Granting access in Settings is reflected on return
- **WHEN** access is `DENIED`, the user backgrounds the app, enables full photo access in system Settings, and returns to the foreground
- **THEN** the app re-reads the status and `permission` emits `GRANTED`

