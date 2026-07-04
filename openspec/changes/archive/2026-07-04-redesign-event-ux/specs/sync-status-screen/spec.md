## MODIFIED Requirements

### Requirement: Sync status snapshots reduce to UI state

The presentation layer SHALL reduce config presence, permission, and each observed `SyncStatus` to a
display-ready `UiState`. `SyncProgress`, its `SyncStatusSource` seam, the `SyncStatus` vocabulary, and
the three-state classification are owned by the `sync-status` capability — this screen consumes them.

`UiState` SHALL have exactly these families: the create layer (`CreateEvent(error?)` /
`CreatingEvent`, owned by the `event-creation-ui` capability and outranking everything on config
absence); and a single **`Joined`** state carrying a health descriptor. The prior joined states
`InProgress`, `Completed`, and `NothingToSync`, the permission state `PermissionBlocked`, and the
standalone `Loading` state are **removed** and fold into `Joined` (the joined loading first-frame is a
health value, `SyncHealth.Loading`).

The reduction SHALL be: **config absent** → the create layer (per `event-creation-ui`); **config
present** → `Joined`, **always**, regardless of permission. The `Joined` health descriptor SHALL be
derived from permission and the latest snapshot:

- permission ≠ `GRANTED` → `NeedsAccess(permission)` (the sole attention state; there is no separate
  "not syncing" state — the status projection's only operational signal is permission);
- else `SyncStatus.Loading` → a joined loading first-frame;
- else `SyncStatus.Ready` → `InSync` when settled, else `Syncing(...)` (the completeness/activity
  arrow derivation is specified in *Joined-layer health descriptor and status line*).

`UiState` SHALL carry **no** upload/download counts — the joined states no longer surface `synced`,
`total`, or an in-progress number. The event **name** and the invite URL are supplied to the screen as
parameters (per `event-invite-qr` and the config capability), not as reduced state, so the reduction
gains no branch for them.

The reduction MUST depend only on the latest snapshot (no event history). The container's initial UI
state SHALL be computed from the sources' current values at construction. `UiState.Loading` and every
`Joined` health value are derived from real source values (never placeholders). There is no
join-status reduction: during a (re)join the screen simply shows the current `Joined` health
(typically `Syncing`).

#### Scenario: Settled snapshot reduces to In sync
- **WHEN** config is present, permission is `GRANTED`, and a `Ready` snapshot with `completed == total`
  is observed (downloads also settled)
- **THEN** the UI state is `Joined` with health `InSync`

#### Scenario: Work remaining reduces to Syncing
- **WHEN** config is present, permission is `GRANTED`, and a `Ready` snapshot with `completed < total`
  is observed
- **THEN** the UI state is `Joined` with health `Syncing(...)`, and no synced/total counts are carried

#### Scenario: Permission off with config present reduces to NeedsAccess, not a gate
- **WHEN** config is present and permission is `DENIED` or `NOT_DETERMINED`, for any snapshot
- **THEN** the UI state is `Joined` with health `NeedsAccess(permission)` — the joined layer still
  renders (name, QR, share, leave), and there is no hero-replacing `PermissionBlocked` screen

#### Scenario: Absent config outranks everything
- **WHEN** config is absent (creation status `Idle`), for any permission and any snapshot
- **THEN** the UI state is the create layer, not `Joined`

#### Scenario: A newer snapshot replaces the displayed health entirely
- **WHEN** a snapshot is observed after any earlier snapshots, regardless of any missed in between
- **THEN** the `Joined` health derives from the latest snapshot alone

## ADDED Requirements

### Requirement: Joined-layer health descriptor and status line

In the `Joined` state the screen SHALL render the event **name** as the title and a **single status
line** — never numeric counts. The status line SHALL present one of:

- `NeedsAccess` → an attention affordance reading "Turn on photo access" that is **tappable**:
  tapping SHALL invoke `onRequestPermission()` when permission is `NOT_DETERMINED` and
  `onOpenSettings()` when `DENIED`. It is the only status-line state that carries a background.
- `InSync` → a settled indicator (e.g. a check) reading "In sync", with no direction arrows.
- `Syncing` → the label "Syncing…" with two independent direction arrows, each in a
  shown/pulse state derived as follows:
  - **upload arrow**: hidden when `completed >= total`; else **pulsing** when `pending > 0`, otherwise
    **static**;
  - **download arrow**: hidden when `downloaded >= total`; else **pulsing** when `inFlight > 0`,
    otherwise **static** (`inFlight` from `DownloadProgress`, per the `sync-status` capability).

`InSync` SHALL be shown exactly when both arrows would be hidden (upload complete **and** download
complete); any remaining work SHALL be `Syncing` with the corresponding arrow(s) shown. "Shown" tracks
completeness; "pulse" tracks live activity — so a photo captured but not yet uploaded shows a **static**
upload arrow (honest that work remains without faking motion).

#### Scenario: Upload in flight pulses the up arrow only
- **WHEN** `completed < total`, `pending > 0`, and downloads are complete
- **THEN** the status line reads "Syncing…" with the upload arrow **pulsing** and the download arrow
  hidden

#### Scenario: Work queued but OS idle shows a static arrow
- **WHEN** `completed < total` and `pending == 0`
- **THEN** the upload arrow is **shown static** (not pulsing), and the line reads "Syncing…"

#### Scenario: Download in flight pulses the down arrow independently
- **WHEN** uploads are complete, `downloaded < total`, and `inFlight > 0`
- **THEN** the status line reads "Syncing…" with the download arrow **pulsing** and the upload arrow
  hidden

#### Scenario: Both complete reads In sync
- **WHEN** `completed >= total` and `downloaded >= total`
- **THEN** the status line reads "In sync" with no arrows

#### Scenario: Needs-access line is tappable to the right action
- **WHEN** the health is `NeedsAccess(NOT_DETERMINED)` and the status line is tapped
- **THEN** `onRequestPermission()` is invoked; **WHEN** the health is `NeedsAccess(DENIED)` and it is
  tapped, `onOpenSettings()` is invoked
