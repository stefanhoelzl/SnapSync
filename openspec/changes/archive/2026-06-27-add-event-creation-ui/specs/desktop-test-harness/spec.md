## MODIFIED Requirements

### Requirement: Config presence toggle

The control panel SHALL provide a single config toggle, mutating harness state exclusively through
`PanelController`, which holds a stand-in config cell (`MutableStateFlow<EventConfigPayload?>`) and
implements the stand-in `ConfigSource`. Toggling on SHALL set a canned config; toggling off SHALL set
`null`. This lets the harness reach both config states (present / absent) without a real deeplink.
With config absent the screen shows the create-event layer (`event-creation-ui`); with config present
it shows the downstream permission/join/sync states. The decode/validate and invalid-link error paths
are out of scope for the harness — they are covered by `commonTest` against the pure `deeplink-config`
decoder.

#### Scenario: Toggling config off shows the create screen
- **WHEN** the toggle is off (config `null`), creation status is `Idle`, and permission is `GRANTED`
- **THEN** the status screen shows the create-event screen (name input + Create), not a sync hero

#### Scenario: Toggling config on reveals the downstream state
- **WHEN** the toggle is on (canned config) and permission is `GRANTED`
- **THEN** the create layer is gone and, with permission granted, the sync hero is revealed

## ADDED Requirements

### Requirement: Creation-state overrides

The control panel SHALL let the operator forge each `CreationStatus` without a backend, mutating
harness state exclusively through `PanelController`, which holds a stand-in creation-status cell and
implements the stand-in `CreationStatusSource` and a no-op stand-in `EventCreator`. The panel SHALL
expose presets for `Idle` (create input), `InFlight` (the creating indicator), and `Failed` for each
reason variant (invalid-name and transient/server), so every create-layer screen state can be reviewed
on the desktop. These overrides take effect only while config is absent (the create layer's
precondition).

#### Scenario: Forcing the creating state
- **WHEN** the operator selects the in-flight creation preset while config is absent
- **THEN** the status screen shows `UiState.CreatingEvent` (the preparing indicator, no input)

#### Scenario: Forcing a create failure
- **WHEN** the operator selects a failed-create preset (invalid-name or transient) while config is absent
- **THEN** the create screen shows the input with the matching inline error

#### Scenario: Creation presets require config absent
- **WHEN** a creation preset is selected while config is present
- **THEN** the create layer is not shown (config presence outranks the create layer)
