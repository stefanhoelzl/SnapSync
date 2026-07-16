## MODIFIED Requirements

### Requirement: Config presence toggle
The control panel SHALL provide a single config toggle, mutating harness state exclusively through
`PanelController`, which holds a stand-in config cell (`MutableStateFlow<EventConfigPayload?>`) and
implements the stand-in `ConfigSource`. Toggling on SHALL set a canned config; toggling off SHALL set
`null`. This lets the harness reach both config states (present / absent) without a real event link.
With config absent the screen shows the create-event layer (`event-creation-ui`); with config present
it shows the downstream permission/join/sync states. The decode/validate and invalid-link error paths
are out of scope for the harness — they are covered by `commonTest` against the pure `event-link`
decoder.

#### Scenario: Toggling config off shows the create screen
- **WHEN** the toggle is off (config `null`), creation status is `Idle`, and permission is `GRANTED`
- **THEN** the status screen shows the create-event screen (name input + Create), not a sync hero

#### Scenario: Toggling config on reveals the downstream state
- **WHEN** the toggle is on (canned config) and permission is `GRANTED`
- **THEN** the create layer is gone and, with permission granted, the sync hero is revealed
