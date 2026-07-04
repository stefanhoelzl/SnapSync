## MODIFIED Requirements

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
