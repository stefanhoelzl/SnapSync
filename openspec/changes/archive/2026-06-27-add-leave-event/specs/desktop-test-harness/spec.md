## ADDED Requirements

### Requirement: Leave action is rendered UI-only in the harness

The phone frame SHALL render the status screen's leave action and its confirmation dialog so they are
reviewable offscreen: when a sync preset forces a joined-layer state (InProgress, NothingToSync, or
Complete), the leave action SHALL appear bottom-right, and activating it SHALL raise the "Leave
event?" confirmation. The harness SHALL NOT wire a real leave implementation — the screen's
`onLeaveEvent` callback resolves to the container's no-op default — so confirming exercises the UI
flow only and mutates no harness state (no config, ledger, or sync cell changes). The control panel
SHALL gain no leave control; the leave affordance lives in the phone frame, like the gate's
permission actions.

#### Scenario: A joined-layer preset shows the leave action and dialog
- **WHEN** the user activates a joined-layer sync preset (e.g. Complete) and taps the leave action in
  the phone frame
- **THEN** the "Leave event?" confirmation dialog appears

#### Scenario: Confirming leave in the harness is inert
- **WHEN** the user confirms the leave dialog in the phone frame
- **THEN** the dialog dismisses and no harness state changes (the no-op default runs; config, ledger,
  and sync cells are untouched)

#### Scenario: Cancelling leave in the harness dismisses the dialog
- **WHEN** the user cancels the leave dialog
- **THEN** the dialog dismisses and the forged status state remains shown
