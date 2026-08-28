## MODIFIED Requirements

### Requirement: Creation-state overrides

The control panel SHALL let the operator forge each `CreationStatus` without a backend, mutating
harness state exclusively through `PanelController`, which holds a stand-in creation-status cell and
implements the stand-in `CreationStatusSource` and a no-op stand-in `EventCreator`. The panel SHALL
expose presets for `Idle` (create input), `InFlight` (the creating indicator), and `Failed` for each
reason variant (invalid-name and transient/server), so every create-layer screen state can be reviewed
on the desktop. These overrides take effect only while config is absent (the create layer's
precondition).

The panel SHALL additionally offer a **rejected event link** (capability `event-link`): unlike the
presets above it forges no source, because a rejected link is an EVENT rather than a state — the panel
hands the real gate a link the decoder will refuse, and the self-clearing message that follows is
presentation's own choreography.

It exists because that message was previously unreviewable in either desktop harness, and not for want
of a button: the banner reached the screen as a parameter this harness's one call site never passed, so
even with a lever the message could not have appeared. Reviewing it at all is a consequence of the value
now travelling inside `UiState`.

#### Scenario: Forcing the creating state
- **WHEN** the operator selects the in-flight creation preset while config is absent
- **THEN** the status screen shows the preparing indicator, with no input

#### Scenario: Forcing a create failure
- **WHEN** the operator selects a failed-create preset (invalid-name or transient) while config is absent
- **THEN** the create screen shows the input with the matching inline error

#### Scenario: Creation presets require config absent
- **WHEN** a creation preset is selected while config is present
- **THEN** the create layer is not shown (config presence outranks the create layer)

#### Scenario: Scanning a rejected link
- **WHEN** the operator selects the rejected-event-link action
- **THEN** the real gate decodes it, refuses it, and the create layer states that the code was not valid
