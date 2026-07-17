# leave-event — delta for delete-dead-weight

## MODIFIED Requirements

### Requirement: Leave notifies the backend

The `LeaveEvent` use-case SHALL notify the backend that this device is leaving through an injected
notify lambda backed by `HttpLeaveNotifier`, which issues `DELETE /events/<eventId>/devices/<deviceId>`
(implemented over the device's HTTP client in the main app, mirroring the `DeviceFilesSource` listing
seam). The notify SHALL be **dispatched fire-and-forget** on the injected app-lifetime
`CoroutineScope` **after** the local teardown — its result SHALL NOT gate, delay, or roll back the
local teardown — and SHALL be invoked by **both** the explicit Leave action and the switch path
(provisioning a different event while joined; see `event-link`). `HttpLeaveNotifier` SHALL return a
`Result` and never throw into the use-case.

#### Scenario: Explicit leave issues the backend DELETE

- **WHEN** the user confirms leaving the joined event
- **THEN** `LeaveEvent` clears the config, then dispatches the notify with the snapshotted `eventId` and this device's `deviceId` on the app-lifetime scope, issuing `DELETE /events/<eventId>/devices/<deviceId>` via `HttpLeaveNotifier`

#### Scenario: The notifier failure is contained

- **WHEN** the `DELETE` call errors or times out
- **THEN** `HttpLeaveNotifier` returns a failed `Result`, the use-case logs it, and the already-completed local teardown is unaffected
