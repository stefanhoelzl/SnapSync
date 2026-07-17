# desktop-test-harness — delta for port-need-renames

## MODIFIED Requirements

### Requirement: Display-override controls

The control panel SHALL provide display-override presets in labeled groups plus behavior controls,
all mutating harness state exclusively through a single `PanelController` (which holds the stand-in
permission, config, sync, creation, download, attestation, and pending-join cells and implements the
stand-in sources, the fake `PhotoAccessRequester`, and the no-op `EventCreator`); composables MUST NOT
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
  or `DENIED`; the fake `PhotoAccessRequester.request()` writes the armed outcome into the permission
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
