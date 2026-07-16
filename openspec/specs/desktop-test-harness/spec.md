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

### Requirement: Display-override controls

The control panel SHALL provide display-override presets in labeled groups plus behavior controls,
all mutating harness state exclusively through a single `PanelController` (which holds the stand-in
permission, config, sync, creation, download, attestation, and pending-join cells and implements the
stand-in sources, the fake `PermissionRequester`, and the no-op `EventCreator`); composables MUST NOT
mutate harness state inline. Display overrides remain outside any scenario/command system. The
`PanelController` reads no clock — no sync or permission preset forges a timestamp (the joined layer
renders no relative time).

- **Permission group** — presets for `NOT_DETERMINED`, `DENIED`, and `GRANTED` that write the
  permission cell **only**, leaving the sync and config cells untouched (so a forged sync state
  survives a revoke-and-restore walk).
- **Sync group** — presets that set a `SyncStatus` into the stand-in `SyncStatusSource` **and
  additionally force permission to `GRANTED`, config to present, and the attestation cell to
  attested** (a preset's intent is "show me this screen", which is impossible while the setup gate, a
  missing grant, or an unattested cell is up). The reachable joined-layer sync moods (capability
  `sync-status-screen`) SHALL include: the neutral first frame (`SyncHealth.Loading`), nothing-to-sync
  (`SyncHealth.InSync` at N=0), in-progress (`SyncHealth.Syncing`, carrying a real in-flight count
  distinct from `total − synced`), complete (`SyncHealth.InSync`), and an overshoot preset
  (`completed > total`) exercising the projection's `n = min(completed, total)` clamp.

  The counts these presets forge are **not** rendered by the joined layer — `UiState.Joined` carries only
  a `SyncHealth`, and the status line shows arrows and a mood, never a number (capability
  `sync-status-screen`). They are forged because the real reduction derives the mood *from* them, so they
  are what makes a preset a preset rather than a forged answer. What each one proves is which `SyncHealth`
  the projection reaches, not what the screen prints.

  The panel SHALL additionally expose live nudges for the gallery size (N) and the in-flight count,
  and download-line presets (hidden `0/0`, downloading, all-downloaded) that drive the joined-layer
  download arrow (capability `photo-download`). No preset forges a `SyncHealth` value directly — each
  forges the underlying `SyncStatus`/counts and lets the real reduction derive the mood.
- **Armed request outcome** — a control choosing whether the next `request()` resolves to `GRANTED`
  or `DENIED`; the fake `PermissionRequester.request()` writes the armed outcome into the permission
  cell, and its `openSettings()` only logs.

The panel MUST NOT display a "current permission" readout — the phone frame already shows the truth.

#### Scenario: Forcing the in-progress state
- **WHEN** the operator activates the in-progress preset (a real in-flight count distinct from `total − synced`)
- **THEN** the joined layer shows the `Syncing` status with a shown, pulsing upload arrow — and no count, because the joined layer renders none

#### Scenario: Forcing the complete state
- **WHEN** the operator activates the complete preset (`completed == total`)
- **THEN** the joined layer shows the `InSync` status with no arrows

#### Scenario: Forcing the nothing-to-sync state
- **WHEN** the operator activates the nothing-to-sync preset (N=0)
- **THEN** the joined layer shows the `InSync` status with no counts

#### Scenario: Forcing the overshoot clamp
- **WHEN** the operator activates the overshoot preset (`completed = 6`, `total = 5`)
- **THEN** the projection clamps `n` to N (5) and the joined layer reads `InSync` rather than treating the overshoot as unfinished work

#### Scenario: Sync presets force their precondition
- **WHEN** permission is `DENIED`, config is absent, the attestation cell is unattested, and the operator activates any sync preset
- **THEN** permission becomes `GRANTED`, config becomes present, the attestation cell becomes attested, and the joined layer shows the forged sync mood (no invisible state change)

#### Scenario: Permission presets show the gate
- **WHEN** the operator activates the `DENIED` permission preset
- **THEN** the joined layer's status line shows the needs-access affordance, regardless of the forged sync state

#### Scenario: Walking the revoked-and-restored journey
- **WHEN** the operator forges the complete state, then activates the `DENIED` permission preset (status line shows needs-access), then activates the `GRANTED` preset (playing "the user in Settings")
- **THEN** the complete `InSync` status re-emerges unchanged, because permission presets never touched the sync or config cells

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
`NeedsAccess` outranks `NotStarted` (capability `sync-status-screen`). It therefore SHALL NOT force
permission itself — unlike the presets whose precondition is not the thing under review — because a
preset that granted access could not demonstrate the precedence it exists to demonstrate. Permission is
the operator's to set first.

#### Scenario: The not-started preset shows the clock line
- **WHEN** permission is `GRANTED` and the operator selects the not-started preset
- **THEN** the phone frame's joined layer renders the clock status line naming the event's start, beneath
  the invite QR

#### Scenario: NeedsAccess outranks the not-started preset
- **WHEN** permission is not `GRANTED` and the operator selects the not-started preset
- **THEN** the joined layer renders `NeedsAccess`, not the clock line — the precedence this preset is composable in order to show

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

### Requirement: Join-gate presets

The control panel SHALL let the operator forge the full-screen join gate (`UiState.JoiningEvent`,
capability `join-event`) without a scanned event link or a real details fetch, by writing a chosen
`JoinPhase` into a stand-in **pending-join cell** — an injected `MutablePendingJoinSource` held by
`PanelController` that the container reduces to `JoiningEvent` while config is absent. Forging the
*input* cell (never fabricating a `UiState`) is what keeps the harness honest: the real reduction
still produces the output and still chooses full-screen vs. switch-overlay from the config cell.

Each join preset SHALL force config **absent** (the join gate's precondition) and set the pending-join
cell to the target phase; canned `name`/`startsAt` payloads supply the phases that carry them. All
seven phases SHALL be reachable: `Loading`, `ExplainAccess`, `Ready`, `NotFound`, `LoadFailed`,
`Committing`, `CommitFailed`.

#### Scenario: Forcing the loaded confirm phase
- **WHEN** the operator selects the `Ready` join preset
- **THEN** the phone frame shows the full-screen "Join event" surface with the confirm affordance (config absent)

#### Scenario: Forcing the photo-access explainer
- **WHEN** the operator selects the `ExplainAccess` join preset
- **THEN** the phone frame shows the photo-access explainer surface

#### Scenario: Forcing a blocked or transient phase
- **WHEN** the operator selects the `NotFound`, `LoadFailed`, or `CommitFailed` join preset
- **THEN** the full-screen join surface shows the matching blocked/retry state

#### Scenario: A join preset forces config absent
- **WHEN** config is present and the operator selects any join preset
- **THEN** config becomes absent and the full-screen `JoiningEvent` surface for that phase is shown

### Requirement: Switch-confirmation presets

The control panel SHALL let the operator forge the switch-confirmation overlay
(`Joined.pendingSwitch`, capability `join-event`) — the leave-style dialog shown over the joined layer
when an event link for a **different** event is scanned while already joined. Each switch preset SHALL
force config **present**, permission **granted**, and a settled sync mood (so the underlying joined
layer is coherent), then write the chosen phase into the same pending-join cell; the reducer maps a
non-null pending-join cell with config present to `pendingSwitch`.

The four dialog phases SHALL be reachable: `Ready` (the Switch confirm), `NotFound`, `LoadFailed`, and
`CommitFailed`. The transient `Loading`/`Committing` phases render no overlay and `ExplainAccess` is
unreachable on a switch by design (a switch never explains), so they need no switch preset.

#### Scenario: Forcing the switch confirm dialog
- **WHEN** the operator selects the `Ready` switch preset
- **THEN** the joined layer shows the "Leave … and join …?" switch confirmation dialog

#### Scenario: Forcing a switch failure dialog
- **WHEN** the operator selects the `CommitFailed`, `LoadFailed`, or `NotFound` switch preset
- **THEN** the matching switch dialog is shown over the joined layer

#### Scenario: A switch preset forces its precondition
- **WHEN** config is absent and the operator selects any switch preset
- **THEN** config becomes present, permission becomes granted, and the switch dialog is shown over a coherent joined layer

### Requirement: Unattested preset

The control panel SHALL provide a preset that forges the joined layer's **unattested** health
(`SyncHealth.Unattested`, capability `sync-status-screen`) via an injected `MutableAttestedSource`, by
forcing config present + permission granted and setting the attestation cell to **unattested** — not
by fabricating the health value, so the real reduction and its precedence are exercised.

Because `!attested` outranks the sync states in the reduction, a stuck attestation cell would silently
mask every subsequently-forged sync state; therefore every other precondition-forcing preset (sync,
not-started, join, switch) SHALL force the attestation cell back to **attested**, the same discipline
by which sync presets already force permission-granted and config-present.

#### Scenario: Forcing the unattested state
- **WHEN** the operator selects the unattested preset
- **THEN** the joined layer's status line renders the cannot-verify-device attention state

#### Scenario: Presets reset the attestation cell
- **WHEN** the operator selects the unattested preset and then activates a sync preset
- **THEN** the forged sync mood is shown, because the sync preset forced the attestation cell back to attested

