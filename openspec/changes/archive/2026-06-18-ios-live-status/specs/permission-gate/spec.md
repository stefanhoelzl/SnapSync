## ADDED Requirements

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
