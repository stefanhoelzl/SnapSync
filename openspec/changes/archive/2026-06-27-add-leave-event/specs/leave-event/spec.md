## ADDED Requirements

### Requirement: Leave use-case resets local event state

The capability SHALL provide a `LeaveEvent` use-case that tears down the configured event's **local**
state in this order, best-effort: (1) **disable** the background-upload producer, (2) **reset the
ledger to empty** (`LedgerBackend.resetTo(emptyList())`) and **clear the discovery cursor**, (3)
**clear the persisted config** (`ConfigStore.clear()`), and (4) set `EventStatus` to `Idle`. The
producer SHALL be disabled **before** the ledger is reset so there is never a concurrent ledger
writer during the reset (mirroring the enable gate's disable-first discipline). The platform
side-effects — disabling the producer and clearing the discovery cursor — SHALL be injected as
suspend lambdas (as `JoinEvent` takes `clearDiscoveryCursor`), so the use-case is pure
`commonMain` logic and the app shell stays wiring-only. The use-case SHALL construct no
`LedgerWriter` (the reset rides the `LedgerBackend` reset family).

#### Scenario: Leaving disables, wipes, forgets, and idles in order
- **WHEN** `LeaveEvent` runs with an event configured and a non-empty ledger
- **THEN** the producer is disabled first, then the ledger is reset to empty and the discovery cursor
  cleared, then the config is cleared, and `EventStatus` becomes `Idle`

#### Scenario: After leaving, the gate yields the setup screen
- **WHEN** a leave completes
- **THEN** `ConfigSource.config` is `null`, the ledger reports zero pending and completed, and the
  presentation reduces to the setup gate (storage not connected)

### Requirement: Leave is best-effort with no rollback

A failing step SHALL be logged and SHALL NOT roll back earlier steps; there is no transaction across
the Keychain, the App-Group ledger, and the discovery cursor. The step order SHALL be chosen so the
worst partial outcome self-heals: if the config clear fails after the ledger is wiped, the next
launch's join gate reconciles the still-configured event from storage (an idempotent re-join) rather
than leaving a corrupt state.

#### Scenario: A failed config clear self-heals on next launch
- **WHEN** the ledger has been reset and the producer disabled but `ConfigStore.clear()` fails
- **THEN** the event is still configured against an empty ledger, so the next launch's join gate
  re-joins it (already-stored photos are re-seeded `COMPLETED`), with no corrupt intermediate state

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
