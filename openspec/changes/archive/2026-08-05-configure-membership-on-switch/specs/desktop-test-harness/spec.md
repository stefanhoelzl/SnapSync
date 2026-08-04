## MODIFIED Requirements

### Requirement: Switch-confirmation presets

The control panel SHALL let the operator forge the switch-confirmation overlay
(`Joined.pendingSwitch`, capability `join-event`) — the leave-style dialog shown over the joined layer
when an event link for a **different** event is scanned while already joined. Each switch preset SHALL
force config **present**, permission **granted**, and a settled sync mood (so the underlying joined
layer is coherent), then write the chosen phase into the same pending-join cell; the reducer maps a
non-null pending-join cell with config present to `pendingSwitch`.

The three dialog phases SHALL be reachable: `Ready` (the Switch confirm), `NotFound`, and `LoadFailed`.
`CommitFailed` SHALL have **no** switch preset: the confirmation's confirm runs only the leave, so no
commit can fail while a config is still present, and forging that combination would offer the operator a
state the reduction cannot reach. The transient `Loading`/`Committing` phases render no overlay, and
`ExplainAccess` likewise needs no switch preset — not because a switch never explains, but because it
explains only **after** the leave has cleared the config, at which point it is an ordinary full-screen
`JoiningEvent` state the join presets already cover.

#### Scenario: Forcing the switch confirm dialog
- **WHEN** the operator selects the `Ready` switch preset
- **THEN** the joined layer shows the "Leave … and join …?" switch confirmation dialog

#### Scenario: Forcing a switch failure dialog
- **WHEN** the operator selects the `LoadFailed` or `NotFound` switch preset
- **THEN** the matching switch dialog is shown over the joined layer

#### Scenario: A switch preset forces its precondition
- **WHEN** config is absent and the operator selects any switch preset
- **THEN** config becomes present, permission becomes granted, and the switch dialog is shown over a coherent joined layer

#### Scenario: No preset forges a switch commit failure
- **WHEN** the operator reviews the switch presets
- **THEN** no `CommitFailed` switch preset is offered, the post-leave commit failure being reachable only
  through the full-screen join presets
