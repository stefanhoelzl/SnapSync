## ADDED Requirements

### Requirement: Join-gate presets

The control panel SHALL let the operator forge the full-screen join gate (`UiState.JoiningEvent`,
capability `join-event`) without a scanned deeplink or a real details fetch, by writing a chosen
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
when a deeplink for a **different** event is scanned while already joined. Each switch preset SHALL
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
(`SyncHealth.Unattested`, capability `device-attestation`) via an injected `MutableAttestedSource`, by
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

## MODIFIED Requirements

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
  distinct from `total − synced` so the "uploading now" caption is exercised), complete
  (`SyncHealth.InSync`), and an overshoot preset (`completed > total`) that the joined layer clamps to
  N. The panel SHALL additionally expose live nudges for the gallery size (N) and the in-flight count,
  and download-line presets (hidden `0/0`, downloading, all-downloaded) that drive the joined-layer
  download arrow (capability `photo-download`). No preset forges a `SyncHealth` value directly — each
  forges the underlying `SyncStatus`/counts and lets the real reduction derive the mood.
- **Armed request outcome** — a control choosing whether the next `request()` resolves to `GRANTED`
  or `DENIED`; the fake `PermissionRequester.request()` writes the armed outcome into the permission
  cell, and its `openSettings()` only logs.

The panel MUST NOT display a "current permission" readout — the phone frame already shows the truth.

#### Scenario: Forcing the in-progress state
- **WHEN** the operator activates the in-progress preset (a real in-flight count distinct from `total − synced`)
- **THEN** the joined layer shows the `Syncing` status with a shown, pulsing upload arrow and the "uploading now" caption

#### Scenario: Forcing the complete state
- **WHEN** the operator activates the complete preset (`completed == total`)
- **THEN** the joined layer shows the `InSync` status with no arrows

#### Scenario: Forcing the nothing-to-sync state
- **WHEN** the operator activates the nothing-to-sync preset (N=0)
- **THEN** the joined layer shows the `InSync` status with no counts

#### Scenario: Forcing the overshoot clamp
- **WHEN** the operator activates the overshoot preset (`completed = 6`, `total = 5`)
- **THEN** the joined layer clamps the total to N (5)

#### Scenario: Sync presets force their precondition
- **WHEN** permission is `DENIED`, config is absent, the attestation cell is unattested, and the operator activates any sync preset
- **THEN** permission becomes `GRANTED`, config becomes present, the attestation cell becomes attested, and the joined layer shows the forged sync mood (no invisible state change)

#### Scenario: Permission presets show the gate
- **WHEN** the operator activates the `DENIED` permission preset
- **THEN** the joined layer's status line shows the needs-access affordance, regardless of the forged sync state

#### Scenario: Walking the revoked-and-restored journey
- **WHEN** the operator forges the complete state, then activates the `DENIED` permission preset (status line shows needs-access), then activates the `GRANTED` preset (playing "the user in Settings")
- **THEN** the complete `InSync` status re-emerges unchanged, because permission presets never touched the sync or config cells
