# desktop-test-harness Delta Specification

## ADDED Requirements

### Requirement: Dual-pane harness layout

The desktop application SHALL render two panes side by side: on the left, the real shared status screen inside a fixed phone-sized frame (~390×844 with a visible bezel) so it is previewed at ship proportions; on the right, a control panel. The phone frame's content MUST be the same status-screen composable that the iOS app will ship — not a copy.

#### Scenario: Harness opens with both panes
- **WHEN** the desktop application is launched
- **THEN** the window shows the status screen inside a phone-sized frame on the left and the control panel on the right

#### Scenario: Phone frame keeps ship proportions
- **WHEN** the desktop window is resized
- **THEN** the phone frame retains its fixed ~390×844 content size

### Requirement: Display-override controls

The control panel SHALL provide display-override buttons that set `SyncStatus` snapshots into the harness's stand-in `SyncStatusSource`, forcing the status screen into any supported state for manual UI exploration. Every supported UI state SHALL be reachable through the panel. All panel-driven mutations MUST go through a single `PanelController`; composables MUST NOT mutate harness state inline.

#### Scenario: Forcing the uploading state
- **WHEN** the user activates an override that sets `pending = 7, completed = 3`
- **THEN** the status screen immediately shows uploading progress "3 of 10"

#### Scenario: Forcing the idle state
- **WHEN** the user activates an override that sets `pending = 0`
- **THEN** the status screen immediately shows the idle state
