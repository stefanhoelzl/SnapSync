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
state, best-effort, in this order: (1) **disable** the background-upload producer, then (2) **clear the
persisted config** (`ConfigStore.clear()`). The use-case SHALL **not** touch the ledger, the discovery
cursor, or any `EventStatus`: with the producer's reconciliation in the extension (see
`event-rejoin-reconciliation`), the extension resets its private ledger, cursor, and `joinedEventId`
marker on its next join (a configured `eventId` that no longer matches the marker, or a later provision of
a different event). The producer is disabled **before** the config is cleared so no producer work races
the teardown. The platform side-effect — disabling the producer — SHALL be injected as a suspend lambda,
so the use-case is pure `commonMain` logic and the app shell stays wiring-only. The use-case SHALL
construct **no** ledger type.

#### Scenario: Leaving disables the producer and clears config

- **WHEN** `LeaveEvent` runs with an event configured
- **THEN** the producer is disabled first, then the config is cleared, and no ledger or `EventStatus` operation is performed

#### Scenario: After leaving, the gate yields the setup screen

- **WHEN** a leave completes
- **THEN** `ConfigSource.config` is `null` and the presentation reduces to the setup gate (storage not connected)

### Requirement: Leave is best-effort with no rollback

A failing step SHALL be logged and SHALL NOT roll back earlier steps; there is no transaction across the
producer registration and the Keychain. The step order — disable producer, then clear config — SHALL be
chosen so the worst partial outcome self-heals: if the config clear fails, the event remains configured
(the user is simply still joined, with the producer disabled until the next enable), rather than leaving a
half-torn-down state. A stale private ledger left in the extension is reset on the next join via the
`joinedEventId` mismatch, not at leave time.

#### Scenario: A failed config clear leaves the user joined, not corrupted

- **WHEN** the producer has been disabled but `ConfigStore.clear()` fails
- **THEN** the event is still configured and consistent; re-running leave retries the clear, and no ledger corruption can occur because leave never touched the ledger

### Requirement: Leave is local-only

Leaving SHALL NOT delete or modify any object already uploaded to the event's storage; it operates
only on on-device state. Re-scanning the same event's QR after a leave SHALL re-join and reconcile
those already-stored objects back as `COMPLETED` (the join behavior of
`event-rejoin-reconciliation`), so leave and re-join round-trip without re-uploading.

#### Scenario: Leaving does not touch storage
- **WHEN** a leave runs
- **THEN** no request is made to delete or alter the event's stored objects; only on-device state
  (ledger, discovery cursor, config, producer registration) changes

#### Scenario: Re-joining after a leave re-seeds, not re-uploads
- **WHEN** the user leaves an event and later re-scans the same event's QR
- **THEN** the join reconciliation seeds the already-stored photos as `COMPLETED` and the producer
  uploads nothing already present

### Requirement: Leave action is presented only in the joined layer

The presentation layer SHALL expose an `onLeaveEvent()` intent that invokes the `LeaveEvent`
use-case. The leave affordance SHALL be offered to the user **only** while the screen is in the
joined layer — the `InProgress`, `NothingToSync`, and `Completed` states — and SHALL NOT be offered
in the loading, setup-gate, joining, or join-failed states. Restricting the affordance to the joined
layer guarantees no join is in flight when a leave runs, so the leave needs no cancellation of, and
no coordination with, a concurrent join.

#### Scenario: The leave intent invokes the use-case
- **WHEN** `onLeaveEvent()` is invoked
- **THEN** the `LeaveEvent` use-case runs its disable → wipe → clear → idle sequence

#### Scenario: No leave affordance outside the joined layer
- **WHEN** the screen is in the loading, setup-gate, joining, or join-failed state
- **THEN** no leave affordance is presented

### Requirement: Leaving requires explicit confirmation

Activating the leave affordance SHALL raise a confirmation prompt ("Leave event?") with confirm and
cancel choices before any state is torn down. Confirming SHALL invoke `onLeaveEvent()`; cancelling
SHALL dismiss the prompt with no change. The leave SHALL NOT execute on a single activation without
confirmation.

#### Scenario: Confirming executes the leave
- **WHEN** the user activates the leave affordance and confirms the prompt
- **THEN** `onLeaveEvent()` is invoked and the event is left

#### Scenario: Cancelling leaves everything intact
- **WHEN** the user activates the leave affordance and cancels the prompt
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
