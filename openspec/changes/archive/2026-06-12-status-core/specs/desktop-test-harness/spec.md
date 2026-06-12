# desktop test harness — delta

## MODIFIED Requirements

### Requirement: Display-override controls

The control panel SHALL provide display-override presets in two labeled groups plus one behavior
control, all mutating harness state exclusively through a single `PanelController` (which holds
the stand-in permission and sync state cells and implements both the stand-in sources and the
fake `PermissionRequester`); composables MUST NOT mutate harness state inline. Display overrides
remain outside any scenario/command system.

- **Permission group** — presets for `NOT_DETERMINED`, `DENIED`, and `GRANTED` that write the
  permission cell **only**, leaving the sync cell untouched (so a forged sync state survives a
  revoke-and-restore walk).
- **Sync group** — presets that set `SyncStatus` snapshots into the stand-in `SyncStatusSource`
  **and additionally force permission to `GRANTED`** (a preset's intent is "show me this screen",
  which is impossible while gated). All five states (NeverSynced, InProgress, Suspended,
  Complete, Incomplete) SHALL be reachable, including forged timestamps (e.g.
  `lastFinishedAt = now − 5 min`) and forged estimates — with at least one InProgress preset
  carrying a `null` estimate so the "estimating…" placeholder is reachable. Finished presets
  (Complete, Incomplete) SHALL forge `active = true` — under suspended-first classification an
  inactive snapshot classifies as Suspended regardless of history.
- **Armed request outcome** — a control choosing whether the next `request()` resolves to
  `GRANTED` or `DENIED`; the fake `PermissionRequester.request()` writes the armed outcome into
  the permission cell, and its `openSettings()` only logs.

The panel MUST NOT display a "current permission" readout — the phone frame already shows the
truth.

#### Scenario: Forcing an in-progress state with estimate
- **WHEN** the user activates an InProgress preset with `pending = 22, completed = 12,
  active = true` and a 2-minute estimate
- **THEN** the status screen immediately shows "Sync in progress" with a partial progress
  indicator and "~2 min left"

#### Scenario: Forcing the estimating placeholder
- **WHEN** the user activates an InProgress preset whose estimate is null
- **THEN** the status screen shows "Sync in progress" with "estimating…"

#### Scenario: Forcing the suspended state
- **WHEN** the user activates a Suspended preset (`active = false`)
- **THEN** the status screen immediately shows "Waiting to sync"

#### Scenario: Forcing a finished outcome with a forged timestamp
- **WHEN** the user activates the Incomplete preset with `lastFinishedAt = now − 5 min` and
  `active = true`
- **THEN** the status screen immediately shows "Sync incomplete" with "5 min ago"

#### Scenario: Forcing the virgin state
- **WHEN** the user activates the NeverSynced preset (all counts zero, `lastFinishedAt = null`,
  `active = true`)
- **THEN** the status screen immediately shows "No sync yet"

#### Scenario: Sync presets force their precondition
- **WHEN** permission is `DENIED` and the user activates the Incomplete preset
- **THEN** permission becomes `GRANTED` and the status screen immediately shows
  "Sync incomplete" (no invisible state change)

#### Scenario: Permission presets show the gate
- **WHEN** the user activates the Denied permission preset
- **THEN** the status screen immediately shows the Denied gate, regardless of the forged sync
  state

#### Scenario: Walking ask-to-granted via the armed outcome
- **WHEN** the armed outcome is "grants", permission is `NOT_DETERMINED`, and the user clicks
  "Allow access" in the phone frame
- **THEN** permission becomes `GRANTED` and the status screen reveals the hero for the current
  forged sync state

#### Scenario: Walking the revoked-and-restored journey
- **WHEN** the user forges Incomplete, then activates the Denied permission preset (gate shows
  "Photo access denied"), then activates the Granted preset (playing "the user in Settings")
- **THEN** the Incomplete hero re-emerges unchanged, because permission presets never touched
  the sync cell
