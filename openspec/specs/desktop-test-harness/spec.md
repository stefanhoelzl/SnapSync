# desktop test harness Specification

## Purpose

The desktop dual-pane harness that previews the real shared status screen in a phone-sized frame and drives it into any supported state via a control panel.
## Requirements
### Requirement: Dual-pane harness layout

The forge harness SHALL be a Compose desktop **application** in the `:app:desktop:ui` module (run task `:app:desktop:ui:run`) that renders two panes side by side: on the left, the real shared status screen inside a fixed phone-sized frame (~390×844 with a visible bezel) so it is previewed at ship proportions; on the right, a control panel. The phone frame and the status-screen composition wiring (construct `StatusContainerHost` from the injected seams → render the shared `StatusScreen` inside the frame) SHALL live in the parent `:app:desktop` **library** (`PhoneFrame` + `StatusPane`), which the `:app:desktop:ui` child depends on, so a later full-stack harness can reuse them; the parent declares no application block, leaving `:app:desktop:run` free. The phone frame's content MUST be the same status-screen composable that the iOS app will ship — not a copy.

#### Scenario: Harness opens with both panes
- **WHEN** the desktop application is launched
- **THEN** the window shows the status screen inside a phone-sized frame on the left and the control panel on the right

#### Scenario: Phone frame keeps ship proportions
- **WHEN** the desktop window is resized
- **THEN** the phone frame retains its fixed ~390×844 content size

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

### Requirement: Display-override controls

The control panel SHALL provide display-override presets in two labeled groups plus behavior
controls, all mutating harness state exclusively through a single `PanelController` (which holds
the stand-in permission, config, and sync state cells and implements the stand-in sources and the
fake `PermissionRequester`); composables MUST NOT mutate harness state inline. Display overrides
remain outside any scenario/command system. The `PanelController` reads no clock — no preset forges
a timestamp (the status screen no longer renders relative time).

- **Permission group** — presets for `NOT_DETERMINED`, `DENIED`, and `GRANTED` that write the
  permission cell **only**, leaving the sync and config cells untouched (so a forged sync state
  survives a revoke-and-restore walk).
- **Sync group** — presets that set `SyncStatus` snapshots into the stand-in `SyncStatusSource`
  **and additionally force permission to `GRANTED` and config to present** (a preset's intent is
  "show me this screen", which is impossible while the setup gate is up). All five states
  (NeverSynced, InProgress, Suspended, Complete, Incomplete) SHALL be reachable, with forged counts
  and forged estimates — with at least one InProgress preset carrying a `null` estimate so the
  "estimating…" placeholder is reachable. Finished presets (Complete, Incomplete) SHALL forge
  `active = true` — under suspended-first classification an inactive snapshot classifies as Suspended
  regardless of history.
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

#### Scenario: Forcing a finished outcome
- **WHEN** the user activates the Incomplete preset with `active = true`
- **THEN** the status screen immediately shows "Sync incomplete" with no relative-time detail line

#### Scenario: Forcing the virgin state
- **WHEN** the user activates the NeverSynced preset (all counts zero, `active = true`)
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

### Requirement: Not-started status preset

The forge harness's control panel SHALL offer a preset that forges the joined layer's **not-started**
health, so the clock status line can be reviewed without waiting for wall-clock time to pass and without
a device.

Because the not-started state is derived from `startsAt > now` against an injected time source, the
harness SHALL forge it by supplying a config whose `startsAt` lies in the **future** relative to the
harness's clock — not by fabricating the health value directly. Forging the *input* rather than the
*output* is what keeps the harness honest: it exercises the real reduction and its precedence, so a
regression in either shows up here.

The preset SHALL be composable with the existing permission presets, so the reviewer can confirm that
`NeedsAccess` outranks `NotStarted` (capability `sync-status-screen`).

#### Scenario: The not-started preset shows the clock line
- **WHEN** the operator selects the not-started preset
- **THEN** the phone frame's joined layer renders the clock status line naming the event's start, beneath
  the invite QR

#### Scenario: Permission outranks the forged not-started state
- **WHEN** the operator selects the not-started preset together with a not-granted permission preset
- **THEN** the status line renders the needs-access affordance, not the clock line — the real precedence
  being exercised

#### Scenario: The preset forges the start, not the health
- **WHEN** the not-started preset is applied
- **THEN** it sets a future `startsAt` on the forged config and lets the real reduction derive the health

### Requirement: Phone-pane theme toggle

The control panel SHALL provide a Light/Dark toggle that forces the **phone pane's** theme
deterministically, so the shipped dark skin can be reviewed without a device. The toggle SHALL drive
the design system's test-only theme override (`LocalDarkThemeOverride`) around the rendered
`StatusScreen` only — via the shared `StatusPane`'s `darkThemeOverride` input — so the phone pane
renders the real `AppTheme` in the chosen theme. The toggle SHALL NOT depend on the host OS setting
(`isSystemInDarkTheme()`), which is unreliable on the desktop target. The toggle SHALL default to
Light, matching the harness's current appearance, and SHALL leave the control panel's own raw-Material 3
chrome unthemed by it.

#### Scenario: Toggle forces the phone pane dark
- **WHEN** the operator sets the theme toggle to Dark
- **THEN** the phone pane's status screen renders in the dark color scheme, independent of the host OS setting

#### Scenario: Toggle forces the phone pane light
- **WHEN** the operator sets the theme toggle to Light
- **THEN** the phone pane's status screen renders in the light color scheme

#### Scenario: Toggle leaves the control panel chrome unchanged
- **WHEN** the operator switches the theme toggle to Dark
- **THEN** the right-hand control panel chrome is unaffected by the toggle

