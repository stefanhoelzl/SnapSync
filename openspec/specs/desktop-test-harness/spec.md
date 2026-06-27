# desktop test harness Specification

## Purpose

The desktop dual-pane harness that previews the real shared status screen in a phone-sized frame and drives it into any supported state via a control panel.

## Requirements

### Requirement: Dual-pane harness layout

The desktop application SHALL render two panes side by side: on the left, the real shared status screen inside a fixed phone-sized frame (~390×844 with a visible bezel) so it is previewed at ship proportions; on the right, a control panel. The phone frame's content MUST be the same status-screen composable that the iOS app will ship — not a copy.

#### Scenario: Harness opens with both panes
- **WHEN** the desktop application is launched
- **THEN** the window shows the status screen inside a phone-sized frame on the left and the control panel on the right

#### Scenario: Phone frame keeps ship proportions
- **WHEN** the desktop window is resized
- **THEN** the phone frame retains its fixed ~390×844 content size

### Requirement: Config presence toggle

The control panel SHALL provide a single config toggle, mutating harness state exclusively through
`PanelController`, which holds a stand-in config cell (`MutableStateFlow<S3Config?>`) and implements
the stand-in `ConfigSource`. Toggling on SHALL set a canned `S3Config`; toggling off SHALL set
`null`. This lets the harness reach both setup-gate config states (present / absent) without a real
deeplink. The decode/validate and invalid-link error paths are out of scope for the harness — they
are covered by `commonTest` against the pure `deeplink-config` decoder.

#### Scenario: Toggling config off shows the storage step unsatisfied
- **WHEN** the toggle is off (config `null`) and permission is `GRANTED`
- **THEN** the status screen shows the setup gate with the "Connect your storage" card unsatisfied

#### Scenario: Toggling config on satisfies the storage step
- **WHEN** the toggle is on (canned config) and permission is `GRANTED`
- **THEN** the storage step is satisfied and, with permission granted, the sync hero is revealed

### Requirement: Display-override controls

The control panel SHALL provide display-override presets in two labeled groups plus behavior
controls, all mutating harness state exclusively through a single `PanelController` (which holds
the stand-in permission, config, and sync state cells and implements the stand-in sources and the
fake `PermissionRequester`); composables MUST NOT mutate harness state inline. Display overrides
remain outside any scenario/command system.

- **Permission group** — presets for `NOT_DETERMINED`, `DENIED`, and `GRANTED` that write the
  permission cell **only**, leaving the sync and config cells untouched (so a forged sync state
  survives a revoke-and-restore walk).
- **Sync group** — presets that set `SyncStatus` snapshots into the stand-in `SyncStatusSource`
  **and additionally force permission to `GRANTED` and config to present** (a preset's intent is
  "show me this screen", which is impossible while the setup gate is up). All five states
  (NeverSynced, InProgress, Suspended, Complete, Incomplete) SHALL be reachable, including forged
  timestamps (e.g. `lastFinishedAt = now − 5 min`) and forged estimates — with at least one
  InProgress preset carrying a `null` estimate so the "estimating…" placeholder is reachable.
  Finished presets (Complete, Incomplete) SHALL forge `active = true` — under suspended-first
  classification an inactive snapshot classifies as Suspended regardless of history.
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
- **WHEN** permission is `DENIED`, config is absent, and the user activates the Incomplete preset
- **THEN** permission becomes `GRANTED`, config becomes present, and the status screen immediately
  shows "Sync incomplete" (no invisible state change)

#### Scenario: Permission presets show the gate
- **WHEN** the user activates the Denied permission preset
- **THEN** the status screen immediately shows the setup gate with the denied permission card,
  regardless of the forged sync state

#### Scenario: Walking ask-to-granted via the armed outcome
- **WHEN** the armed outcome is "grants", config is present, permission is `NOT_DETERMINED`, and the
  user clicks "Allow access" in the phone frame
- **THEN** permission becomes `GRANTED` and the status screen reveals the hero for the current
  forged sync state

#### Scenario: Walking the revoked-and-restored journey
- **WHEN** the user forges Incomplete, then activates the Denied permission preset (gate shows the
  denied permission card), then activates the Granted preset (playing "the user in Settings")
- **THEN** the Incomplete hero re-emerges unchanged, because permission presets never touched the
  sync or config cells

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

### Requirement: Invite affordances are rendered UI-only in the harness

The phone frame SHALL render the status screen's invite affordances so they are reviewable offscreen:
when a sync preset forces a joined-layer state (InProgress, NothingToSync, or Complete), the harness
SHALL supply a fixed sample `eventId` so the invite deeplink is non-`null`, and the phone frame SHALL
render the join QR (with the "Scan to join this event" caption) above the hero and the flat icon-only
share action in the bottom action cluster. The harness SHALL NOT perform a real platform share — the
screen's `onShareInvite` callback resolves to a clipboard/log stub (test equipment) — so activating
share exercises the UI and the stub only and mutates no harness state (no config, ledger, or sync cell
changes). The control panel SHALL gain no share control and SHALL gain no editable event-id field (a
fixed sample id suffices); the invite affordances live in the phone frame, like the leave action.

#### Scenario: A joined-layer preset shows the invite QR and share action
- **WHEN** the user activates a joined-layer sync preset (e.g. Complete)
- **THEN** the phone frame renders a scannable join QR with the "Scan to join this event" caption and a
  flat icon-only share action, using the harness's fixed sample `eventId`

#### Scenario: Non-joined presets show no invite affordances
- **WHEN** the user activates a non-joined preset (loading, setup gate, permission, joining, or
  join-failed)
- **THEN** the phone frame renders no invite QR, caption, or share action

#### Scenario: Activating share in the harness is UI-only
- **WHEN** the user activates the share action in the phone frame
- **THEN** the clipboard/log stub runs and no harness state changes (no config, ledger, or sync cell is
  mutated; no real platform share is performed)
