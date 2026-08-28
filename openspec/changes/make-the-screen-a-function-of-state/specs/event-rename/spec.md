## MODIFIED Requirements

### Requirement: The rename lifecycle is its own status seam with a success value

The capability SHALL expose a `RenameStatus` seam (a `StateFlow` the presentation reduction consumes and
carries as a **field of the joined state**, not as a `UiState` family and not as a health rung) with
exactly four shapes:

- `Idle` — no rename in flight.
- `InFlight` — the request is running.
- `Succeeded` — the rename completed and the new name is persisted.
- `Failed(reason)` — the request failed, with `reason` one of `INVALID_NAME` (the backend's `400`) or
  `SERVER` (every other failure).

`Succeeded` SHALL exist even though the create-status twin (capability `event-creation-ui`) deliberately
has no success value. That twin needs none because a successful create provisions config and moves the
reduction off the create layer entirely; a successful rename leaves the member on the same screen with a
dialog that must close, so the success transition SHALL be an observable fact rather than an inference
from a return to `Idle`.

The capability SHALL provide a command that resets the seam to `Idle`, which the screen fires after
consuming `Succeeded` or dismissing a `Failed`.

The seam SHALL keep reporting a **reason**; turning that reason into words SHALL happen in the
presentation reduction, not in the screen. The joined state therefore carries the dialog's condition with
its failure **copy already formatted**, exactly as the create layer's twin does (capability
`event-creation-ui`) — having one of a matched pair formatted in the reduction and the other in a
composable was an inconsistency, not a design.

#### Scenario: A successful rename is observable as a success
- **WHEN** a rename completes successfully
- **THEN** the seam emits `InFlight` and then `Succeeded`, and the screen has an explicit success to
  react to

#### Scenario: A rejected name is distinguishable from a server failure
- **WHEN** the backend rejects the name with `400`
- **THEN** the seam emits `Failed(INVALID_NAME)`

#### Scenario: Reset returns the seam to Idle
- **WHEN** the screen consumes a terminal status and fires the reset command
- **THEN** the seam emits `Idle` and a subsequent rename starts from a clean sequence

#### Scenario: The rename status travels with the state
- **WHEN** a rename is in flight and the joined state is inspected
- **THEN** the state carries the in-flight condition, and the screen receives it through that state rather
  than as a separate parameter

#### Scenario: The failure copy is formatted where the create layer's is
- **WHEN** a rename fails with either reason
- **THEN** the joined state carries the message the dialog shows, and the screen renders it verbatim —
  the reason itself never reaches the screen
