## ADDED Requirements

### Requirement: The bug-report affordance drives the real dump command

The left pane SHALL wire the **real** bug-report command from the composed app core (capability
`diagnostic-logging`), not a stub: a double-tap on the app-name label opens the bug-report sheet, and
sending assembles a real dump over the world's real ledger, download store, config, permission and
device-log sources, delivered to the world's reporter.

Because the world composes a reporter that reports itself as configured, the affordance SHALL be
present in this harness — unlike the forge harness, where it is rendered UI-only. The assembled dump
SHALL be inspectable as a world outcome, so an operator or an agent driving the harness headlessly can
confirm what a report would carry without a device.

The sheet SHALL be reachable in every state the left pane can show, since the affordance rides on the
app-name label the shared layout always renders.

#### Scenario: Sending a report records a real dump
- **WHEN** the operator double-taps the app-name label, writes a description, and sends
- **THEN** the world's reporter records exactly one dump carrying that description together with the
  state, ledger and log sections assembled from real world state

#### Scenario: The dump reflects real world state
- **WHEN** a report is sent after uploads have completed in the world
- **THEN** the recorded dump's ledger counts match the world's real ledger rather than any forged value

#### Scenario: Cancelling records nothing
- **WHEN** the operator opens the bug-report sheet and cancels
- **THEN** the world's reporter records no dump and no world state changes
