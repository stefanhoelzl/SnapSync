## MODIFIED Requirements

### Requirement: Leave use-case resets local event state

The capability SHALL provide a `LeaveEvent` use-case that tears down the configured event's **local**
state and notifies the backend, best-effort, in this order: (1) **disable** the background-upload
producer, then (2) **notify the backend** that this device is leaving via a `LeaveNotifier`
(`DELETE /events/<eventId>/devices/<deviceId>`), then (3) **clear the persisted config**
(`ConfigStore.clear()`). The `eventId` and `deviceId` SHALL be read **before** the config is cleared.
The use-case SHALL **not** touch the ledger, the discovery cursor, or any `EventStatus`: with the
producer's reconciliation in the extension (see `event-rejoin-reconciliation`), the extension resets its
private ledger, cursor, and `joinedEventId` marker on its next join (a configured `eventId` that no
longer matches the marker, or a later provision of a different event). The producer is disabled
**before** the notify and the config clear so no producer work races the teardown. The platform
side-effects — disabling the producer and the backend notify — SHALL be injected as suspend lambdas, so
the use-case is pure `commonMain` logic and the app shell stays wiring-only. The use-case SHALL construct
**no** ledger type.

#### Scenario: Leaving disables the producer, notifies the backend, and clears config

- **WHEN** `LeaveEvent` runs with an event configured
- **THEN** the producer is disabled first, then the backend leave is notified, then the config is cleared, and no ledger or `EventStatus` operation is performed

#### Scenario: After leaving, the gate yields the setup screen

- **WHEN** a leave completes
- **THEN** `ConfigSource.config` is `null` and the presentation reduces to the setup gate (storage not connected)

### Requirement: Leave is best-effort with no rollback

A failing step SHALL be logged and SHALL NOT roll back earlier steps; there is no transaction across the
producer registration, the backend notify, and the Keychain. The step order — disable producer, notify
backend, then clear config — SHALL be chosen so the worst partial outcome self-heals: a failed backend
notify SHALL NOT abort the local teardown (the device still leaves locally; the un-removed backend
membership is the accepted abandon-leak), and if the config clear fails, the event remains configured
(the user is simply still joined, with the producer disabled until the next enable) rather than leaving a
half-torn-down state. A stale private ledger left in the extension is reset on the next join via the
`joinedEventId` mismatch, not at leave time.

#### Scenario: A failed backend notify still completes local teardown

- **WHEN** the `LeaveNotifier` call fails (offline, timeout, or error)
- **THEN** the failure is logged, the config is still cleared, and the device leaves locally; the backend membership is simply not removed

#### Scenario: A failed config clear leaves the user joined, not corrupted

- **WHEN** the producer has been disabled but `ConfigStore.clear()` fails
- **THEN** the event is still configured and consistent; re-running leave retries the clear, and no ledger corruption can occur because leave never touched the ledger

## REMOVED Requirements

### Requirement: Leave is local-only

**Reason**: Superseded by the event-leave lifecycle — leave now notifies the backend, which removes the
device from the event (rename to `.left.json`) and, when the last active device leaves, reaps the event
and garbage-collects its unreferenced bytes (see `event-leave-endpoint`).

**Migration**: The device-side `LeaveEvent` gains a best-effort `LeaveNotifier` step
(`DELETE /events/<eventId>/devices/<deviceId>`); re-scanning an event a device left still re-joins and
reconciles via `event-rejoin-reconciliation`, and a fresh active manifest supersedes the prior
`.left.json` by last-write-wins.

## ADDED Requirements

### Requirement: Leave notifies the backend

The `LeaveEvent` use-case SHALL notify the backend that this device is leaving through an injected
`LeaveNotifier` seam that issues `DELETE /events/<eventId>/devices/<deviceId>` (implemented over the
device's HTTP client in the main app, mirroring the `DeviceFilesSource` listing seam). The notify SHALL
be best-effort — its result SHALL NOT gate or roll back the local teardown — and SHALL be invoked by
**both** the explicit Leave action and the switch path (provisioning a different event while joined; see
`deeplink-config`). The notifier SHALL return a `Result` and never throw into the use-case.

#### Scenario: Explicit leave issues the backend DELETE

- **WHEN** the user confirms leaving the joined event
- **THEN** `LeaveEvent` invokes the `LeaveNotifier` with the current `eventId` and this device's `deviceId`, issuing `DELETE /events/<eventId>/devices/<deviceId>`

#### Scenario: The notifier failure is contained

- **WHEN** the `DELETE` call errors or times out
- **THEN** the `LeaveNotifier` returns a failed `Result`, the use-case logs it, and the local teardown proceeds unchanged
