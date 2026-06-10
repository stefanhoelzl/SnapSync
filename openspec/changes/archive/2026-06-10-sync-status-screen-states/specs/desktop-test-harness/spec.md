# desktop-test-harness — delta

## MODIFIED Requirements

### Requirement: Display-override controls

The control panel SHALL provide display-override presets that set `SyncStatus` snapshots into the harness's stand-in `SyncStatusSource`, forcing the status screen into any supported state for manual UI exploration. All six states (NeverSynced, InProgress, Suspended, Complete, Incomplete, Failed) SHALL be reachable, including forged timestamps (e.g. `lastFinishedAt = now − 5 min`) and forged estimates — with at least one InProgress preset carrying a `null` estimate so the "estimating…" placeholder is reachable. All panel-driven mutations MUST go through a single `PanelController`; composables MUST NOT mutate harness state inline. Display overrides remain outside any scenario/command system.

#### Scenario: Forcing an in-progress state with estimate
- **WHEN** the user activates an InProgress preset with `pending = 22, completed = 12, active = true` and a 2-minute estimate
- **THEN** the status screen immediately shows "Sync in progress" with a partial progress indicator and "~2 min left"

#### Scenario: Forcing the estimating placeholder
- **WHEN** the user activates an InProgress preset whose estimate is null
- **THEN** the status screen shows "Sync in progress" with "estimating…"

#### Scenario: Forcing the suspended state
- **WHEN** the user activates a Suspended preset (`pending > 0, active = false`)
- **THEN** the status screen immediately shows "Waiting to sync"

#### Scenario: Forcing a finished outcome with a forged timestamp
- **WHEN** the user activates the Failed preset with `lastFinishedAt = now − 5 min`
- **THEN** the status screen immediately shows "Sync failed" with "5 min ago"

#### Scenario: Forcing the virgin state
- **WHEN** the user activates the NeverSynced preset (all counts zero, `lastFinishedAt = null`)
- **THEN** the status screen immediately shows "No sync yet"
