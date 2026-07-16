# leave-event Specification

## Purpose

Leaving the configured event: the local-only inverse of the join lifecycle. The `LeaveEvent`
use-case disables the upload producer, wipes the ledger and discovery cursor, and forgets the
`eventId`, returning to the setup gate — without touching anything already uploaded to storage (a
later re-scan re-joins and reconciles it back). Covers the leave sequence and its best-effort
semantics, the local-only guarantee, the joined-layer-only affordance and its confirmation, and the
presentation seam that triggers it.
## Requirements
### Requirement: Leave use-case resets local event state

The capability SHALL provide a `LeaveEvent` use-case that tears down the configured event's **local**
state and notifies the backend, best-effort, in this order: (1) **disable** the background-upload
producer, then (2) **clear the persisted config** (`ConfigStore.clear()`), then (3) **notify the
backend** that this device is leaving via a `LeaveNotifier` (`DELETE /events/<eventId>/devices/<deviceId>`).
The `eventId` and `deviceId` SHALL be read **before** the config is cleared; the `eventId` is
snapshotted synchronously (from `ConfigSource.config`) into the use-case's own frame before the clear
and passed into the notify, so the notify targets the correct event with no race against the cleared
config. The backend notify SHALL be dispatched **fire-and-forget** on an injected app-lifetime
`CoroutineScope` so it does not block the local teardown (see "Local teardown does not block on the
backend notify"). The use-case SHALL **not** touch the ledger, the discovery cursor, or any
`EventStatus`: with the producer's reconciliation in the extension (see `event-rejoin-reconciliation`),
the extension resets its private ledger, cursor, and `joinedEventId` marker on its next join (a
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

### Requirement: Leave is best-effort with no rollback

A failing step SHALL be logged and SHALL NOT roll back earlier steps; there is no transaction across the
producer registration, the Keychain, and the backend notify. The step order — disable producer, clear
config, then notify backend — SHALL be chosen so the worst partial outcome self-heals: a failed backend
notify SHALL NOT abort or reverse the local teardown (the device still leaves locally; the un-removed
backend membership is the accepted abandon-leak), and if the config clear fails, the event remains
configured (the user is simply still joined, with the producer disabled until the next enable) rather
than leaving a half-torn-down state. The backend notify SHALL be dispatched **unconditionally** after the
clear step — a failed `clear()` SHALL NOT suppress it — preserving the independence of each best-effort
step; the resulting transient state (backend told the device left while it is still joined locally)
self-heals when the producer re-enables and re-writes the device manifest. A stale private ledger left in
the extension is reset on the next join via the `joinedEventId` mismatch, not at leave time.

#### Scenario: A failed backend notify still completes local teardown

- **WHEN** the `LeaveNotifier` call fails (offline, timeout, or error)
- **THEN** the failure is logged, the config has already been cleared, and the device leaves locally; the backend membership is simply not removed

#### Scenario: A failed config clear leaves the user joined, not corrupted

- **WHEN** the producer has been disabled but `ConfigStore.clear()` fails
- **THEN** the event is still configured and consistent; re-running leave retries the clear, and no ledger corruption can occur because leave never touched the ledger

### Requirement: Leave action is presented only in the joined layer

The presentation layer SHALL expose an `onLeaveEvent()` intent that invokes the `LeaveEvent`
use-case. The leave affordance SHALL be offered to the user **only** while the screen is in the
joined layer — defined as **config present** (the `UiState.Joined` state, any health including
`NeedsAccess`) — and SHALL NOT be offered in the loading or create-layer states. Restricting the
affordance to the joined layer guarantees no join is in flight when a leave runs, so the leave needs
no cancellation of, and no coordination with, a concurrent join. (Leave is available even when
permission is not granted — a user may leave regardless of access.)

#### Scenario: The leave intent invokes the use-case
- **WHEN** `onLeaveEvent()` is invoked
- **THEN** the `LeaveEvent` use-case runs its disable → clear sequence

#### Scenario: Leave is offered across all joined health states
- **WHEN** the screen is in `UiState.Joined` with health `NeedsAccess`, `Syncing`, or `InSync`
- **THEN** the leave affordance is presented

#### Scenario: No leave affordance outside the joined layer
- **WHEN** the screen is in the loading or create-layer state
- **THEN** no leave affordance is presented

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
- **THEN** the prompt is dismissed and no config, ledger, cursor, or producer state changes

### Requirement: The container leave action defaults to a no-op

`StatusContainerHost` SHALL accept the leave action as an injected **`suspend () -> Unit` lambda**
with a no-op default — not the `LeaveEvent` type itself, since the presentation layer is Compose-free
and SHALL NOT gain an engine/gallery dependency (the composition root binds the lambda to
`LeaveEvent.leave`). Hosts and tests that do not exercise leave (non-iOS harness, presentation tests)
SHALL construct unchanged, and a confirmed leave in those contexts SHALL be inert.

#### Scenario: A host without a real leave action constructs and is inert
- **WHEN** `StatusContainerHost` is constructed without injecting a real leave action
- **THEN** construction succeeds and invoking `onLeaveEvent()` performs no teardown

#### Scenario: Presentation gains no engine dependency
- **WHEN** the presentation module's dependencies are inspected after this change
- **THEN** it depends on no engine, gallery, or rejoin module — the leave action enters as a plain
  suspend lambda

### Requirement: Leave notifies the backend
The `LeaveEvent` use-case SHALL notify the backend that this device is leaving through an injected
`LeaveNotifier` seam that issues `DELETE /events/<eventId>/devices/<deviceId>` (implemented over the
device's HTTP client in the main app, mirroring the `DeviceFilesSource` listing seam). The notify SHALL
be **dispatched fire-and-forget** on the injected app-lifetime `CoroutineScope` **after** the local
teardown — its result SHALL NOT gate, delay, or roll back the local teardown — and SHALL be invoked by
**both** the explicit Leave action and the switch path (provisioning a different event while joined; see
`event-link`). The notifier SHALL return a `Result` and never throw into the use-case.

#### Scenario: Explicit leave issues the backend DELETE

- **WHEN** the user confirms leaving the joined event
- **THEN** `LeaveEvent` clears the config, then dispatches the `LeaveNotifier` with the snapshotted `eventId` and this device's `deviceId` on the app-lifetime scope, issuing `DELETE /events/<eventId>/devices/<deviceId>`

#### Scenario: The notifier failure is contained

- **WHEN** the `DELETE` call errors or times out
- **THEN** the `LeaveNotifier` returns a failed `Result`, the use-case logs it, and the already-completed local teardown is unaffected

### Requirement: Local teardown does not block on the backend notify

The `LeaveEvent` local teardown (disable producer + `ConfigStore.clear()`) SHALL complete without
awaiting the backend `DELETE`. The backend notify SHALL be dispatched fire-and-forget on an injected
app-lifetime `CoroutineScope` (owned by the composition root, outliving the screen transition), so
`ConfigSource.config` goes `null` — and the screen leaves the joined layer — with latency independent of
the DELETE's round-trip. This non-blocking behavior SHALL apply to **both** the explicit leave and the
switch path: on a switch, the departed event's DELETE SHALL NOT delay the enroll/provision of the new
event (the "Joining …" surface no longer waits on it).

#### Scenario: The screen flips before the DELETE completes

- **WHEN** the user confirms leaving and the backend `DELETE` is slow or never completes
- **THEN** the local teardown returns promptly, `ConfigSource.config` becomes `null`, and the presentation reduces to the setup gate without waiting for the DELETE

#### Scenario: A switch does not wait on the departed event's DELETE

- **WHEN** the user confirms switching to a different event while joined
- **THEN** the departed event's `DELETE` is dispatched fire-and-forget and the new event's enroll/provision proceeds without blocking on it
