## MODIFIED Requirements

### Requirement: Leave use-case resets local event state

The capability SHALL provide a `LeaveEvent` use-case that tears down the configured event's **local**
state and notifies the backend, best-effort, in this order: (1) **disable** the background-upload
producer, then (2) **clear the persisted config** (`ConfigStore.clear()`), then (3) **notify the
backend** that this device is leaving via `HttpLeaveNotifier` (`DELETE /events/<eventId>/devices/<deviceId>`).
The `eventId` and `deviceId` SHALL be read **before** the config is cleared; the `eventId` is
snapshotted synchronously (from `ConfigSource.config`) into the use-case's own frame before the clear
and passed into the notify, so the notify targets the correct event with no race against the cleared
config. The backend notify SHALL be dispatched **fire-and-forget** on an injected app-lifetime
`CoroutineScope` so it does not block the local teardown (see "Local teardown does not block on the
backend notify"). The use-case SHALL **not** touch the ledger or any
`EventStatus`: with the producer's reconciliation in the extension (see `upload-state-reconciliation`),
the extension resets its private ledger and `joinedEventId` marker on its next join (a
configured `eventId` that no longer matches the marker, or a later provision of a different event). The
producer is disabled **before** the config clear so no producer work races the teardown. The platform
side-effects — disabling the producer and the backend notify — SHALL be injected as suspend lambdas
(the notify as `suspend (eventId: String) -> Unit`), so the use-case is pure `commonMain` logic and the
app shell stays wiring-only. The use-case SHALL construct **no** ledger type.

#### Scenario: Leaving disables the producer, clears config, and notifies the backend

- **WHEN** `LeaveEvent` runs with an event configured
- **THEN** the producer is disabled first, then the config is cleared, then the backend leave is notified with the snapshotted `eventId`, and no ledger or `EventStatus` operation is performed

#### Scenario: After leaving, the gate yields the setup screen

- **WHEN** a leave completes its local teardown (disable + clear)
- **THEN** `ConfigSource.config` is `null` and the presentation reduces to the setup gate (storage not connected), regardless of whether the backend notify has completed

### Requirement: Leaving requires explicit confirmation

Activating the leave affordance SHALL raise a confirmation prompt titled **"Leave this event?"** with
two choices — **Stay** (dismiss, no change) and **Leave** (confirm) — before any state is torn down.
Choosing **Leave** SHALL invoke `onLeaveEvent()`; choosing **Stay** SHALL dismiss the prompt with no
change. The leave SHALL NOT execute on a single activation without confirmation. The prompt's
visibility is local screen state and SHALL NOT enter `UiState`.

#### Scenario: Choosing Leave executes the leave
- **WHEN** the user activates the leave affordance and chooses **Leave**
- **THEN** `onLeaveEvent()` is invoked and the event is left

#### Scenario: Choosing Stay leaves everything intact
- **WHEN** the user activates the leave affordance and chooses **Stay**
- **THEN** the prompt is dismissed and no config, ledger, or producer state changes
