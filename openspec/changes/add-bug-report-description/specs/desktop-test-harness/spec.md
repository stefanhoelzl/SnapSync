## ADDED Requirements

### Requirement: The bug-report sheet is rendered UI-only in the harness

The phone frame SHALL render the hidden bug-report affordance (capability `diagnostic-logging`) so it
is reviewable offscreen: a double-tap on the app-name label SHALL open the bug-report sheet in every
forged state, exactly as on device, and the sheet's input, disabled-until-written send action, and
cancel SHALL behave as they do on device.

The harness SHALL NOT wire a real reporting channel — the forge harness forges display state and
composes no reporter — so sending SHALL echo the entered description to the engine console and mutate
no harness state (no config, ledger, or sync cell changes). The control panel SHALL gain no
bug-report control; the affordance lives in the phone frame, like the leave action and the invite
affordances.

This is the harness's own wiring and SHALL NOT reach the shared forge host factory that the on-device
forge composition uses: a build with no reporting configuration must offer no affordance at all, and
that rule is enforced by the command being absent rather than by the sheet being inert.

#### Scenario: A double-tap opens the sheet in the harness
- **WHEN** the user double-taps the app-name label in the phone frame under any preset
- **THEN** the bug-report sheet opens with its input and its send and cancel actions

#### Scenario: Sending in the harness is UI-only
- **WHEN** the user writes a description and sends it in the phone frame
- **THEN** the description is echoed to the engine console, the sheet dismisses, and no harness state
  changes (no config, ledger, or sync cell is mutated; no report leaves the process)

#### Scenario: Cancelling in the harness dismisses the sheet
- **WHEN** the user cancels the bug-report sheet
- **THEN** the sheet dismisses, nothing is echoed, and the forged state remains shown

#### Scenario: The on-device forge composition is untouched
- **WHEN** the shared forge host factory used by the on-device forge composition is inspected
- **THEN** it wires no bug-report command, so a build with no reporting configuration opens no sheet
