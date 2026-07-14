# sync status screen Specification

## Purpose

The app's **only** screen, and the reduction that feeds it: config presence, photo permission, and the
observed `SyncStatus` snapshots collapse into a small, display-ready `UiState` — the create layer
(`CreateEvent` / `CreatingEvent`), the pre-commit `JoiningEvent` gate, and a single `Joined` state — so
the screens branch on meaning, never on raw sources. The snapshot contract and its classification belong
to `sync-status`; this capability reduces and renders them.

Its answer to the joined user is deliberately **one line, no numbers**. Counts were the original design
("n of N images synced") and they were a lie in every direction that mattered: N is the *device's*
post-cutoff library, so it moves with a new photo, says nothing about the photos arriving from everyone
else, and leaves the user reading arithmetic when the only question they have is *is my stuff getting
there?* The joined layer therefore renders the durable, shareable facts — the event **name**, its invite
**QR**, **share**, **leave** — under a single `SyncHealth` status line: `NeedsAccess` / `Loading` /
`InSync` / `Syncing` with direction arrows whose shown-ness tracks completeness and whose pulse tracks
live activity. A user who wants "is it healthy?" gets it at a glance; a user who wants a photo looks in
their library, where it belongs.

The same move dissolved permission as a *state*: a missing grant no longer replaces the screen with a
gate — it is one value of the health line, an inline affordance on a joined layer that renders in full
regardless (capability `permission-gate`).

Decision record: `changes/archive/2026-06-27-permission-on-status-screen`.

## Requirements
### Requirement: Sync status snapshots reduce to UI state

The presentation layer SHALL reduce config presence, permission, each observed `SyncStatus`, and any
**pending join** to a display-ready `UiState`. `SyncProgress`, its `SyncStatusSource` seam, the
`SyncStatus` vocabulary, and the three-state classification are owned by the `sync-status` capability —
this screen consumes them.

`UiState` SHALL have exactly these families: the create layer (`CreateEvent(error?)` /
`CreatingEvent`, owned by the `event-creation-ui` capability and outranking everything on config
absence); the **`JoiningEvent`** family (owned by the `join-event` capability, carrying the pending
`eventId` and its details phase), which represents an interactive join confirmation in progress; and a
single **`Joined`** state carrying a health descriptor and an optional **`pendingSwitch`** (owned by
`join-event`) for a switch confirmation over the joined screen. The prior joined states `InProgress`,
`Completed`, and `NothingToSync`, the permission state `PermissionBlocked`, and the standalone
`Loading` state are **removed** and fold into `Joined` (the joined loading first-frame is a health
value, `SyncHealth.Loading`).

The reduction SHALL be: a **pending interactive join with config absent** → `JoiningEvent`
(outranking the create layer); otherwise **config absent** → the create layer (per
`event-creation-ui`); **config present** → `Joined`, **always**, regardless of permission, carrying a
`pendingSwitch` when a switch confirmation is in progress. The `Joined` health descriptor SHALL be
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
`Joined` health value are derived from real source values (never placeholders). Once a join has
committed there is no join-status reduction: during the (re)join provisioning the screen simply shows
the current `Joined` health (typically `Syncing`); the `JoiningEvent` family is the **pre-commit**
confirmation gate only.

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
- **WHEN** config is absent (creation status `Idle`), no interactive join is pending, for any permission and any snapshot
- **THEN** the UI state is the create layer, not `Joined`

#### Scenario: A pending interactive join outranks the create layer
- **WHEN** config is absent but an interactive join has been decoded and is awaiting confirmation
- **THEN** the UI state is the `JoiningEvent` family for that pending event, not the create layer

#### Scenario: A newer snapshot replaces the displayed health entirely
- **WHEN** a snapshot is observed after any earlier snapshots, regardless of any missed in between
- **THEN** the `Joined` health derives from the latest snapshot alone

### Requirement: Joined-layer health descriptor and status line

In the `Joined` state the screen SHALL render the event **name** as the title and a **single status
line** — never numeric counts. The status line SHALL present one of:

- `NeedsAccess` → an attention affordance reading "Turn on photo access" that is **tappable**:
  tapping SHALL invoke `onRequestPermission()` when permission is `NOT_DETERMINED` and
  `onOpenSettings()` when `DENIED`. It is the only status-line state that carries a background.
- `InSync` → a settled indicator (e.g. a check) reading "In sync", with no direction arrows.
- `Syncing` → two independent direction arrows plus an **activity-dependent label**, each arrow in a
  shown/pulse state derived as follows, **masked by the membership's participation direction**
  (`EventConfig.direction`):
  - **upload arrow**: **force-hidden** when the direction excludes upload (`DownloadOnly`); otherwise
    hidden when `completed >= total`; else **pulsing** when `pending > 0`, otherwise **static**;
  - **download arrow**: **force-hidden** when the direction excludes download (`UploadOnly`); otherwise
    hidden when `downloaded >= total`; else **pulsing** when `inFlight > 0`, otherwise **static**
    (`inFlight` from `DownloadProgress`, per the `sync-status` capability).

The `Syncing` **label** SHALL be derived from the combined arrow activity: when **any** shown arrow is
**pulsing** (work in flight), the label reads **"Synchronization ongoing…"**; when at least one arrow is
shown but **none** is pulsing (work remains but nothing is in flight), the label reads
**"Synchronization pending…"**. The exact label strings are owned by the `App*` status-line component
(see `design-system`); this screen supplies only the health value.

`InSync` SHALL be shown exactly when both arrows would be hidden — where a masked (force-hidden) arrow
counts as hidden — so `InSync` is computed over the **enabled direction(s) only**: an `UploadOnly`
membership reads `InSync` when uploads are complete regardless of any foreign downloads, and a
`DownloadOnly` membership reads `InSync` when imports are complete regardless of the own-device gallery.
Any remaining work in an **enabled** direction SHALL be `Syncing` with that direction's arrow shown.
"Shown" tracks completeness; "pulse" tracks live activity — so a photo captured but not yet uploaded (on
an upload-enabled membership) shows a **static** upload arrow under the "Synchronization pending…" label.
The masking is **silent**: no textual mode label is rendered; the single remaining arrow implies the
direction.

#### Scenario: Upload in flight pulses the up arrow and reads ongoing
- **WHEN** direction includes both, `completed < total`, `pending > 0`, and downloads are complete
- **THEN** the status line reads "Synchronization ongoing…" with the upload arrow **pulsing** and the
  download arrow hidden

#### Scenario: Work queued but OS idle shows a static arrow and reads pending
- **WHEN** direction includes upload, `completed < total` and `pending == 0`
- **THEN** the upload arrow is **shown static** (not pulsing) and the line reads "Synchronization
  pending…"

#### Scenario: Download in flight pulses the down arrow and reads ongoing
- **WHEN** direction includes download, uploads are complete, `downloaded < total`, and `inFlight > 0`
- **THEN** the status line reads "Synchronization ongoing…" with the download arrow **pulsing** and the
  upload arrow hidden

#### Scenario: Any direction in flight reads ongoing
- **WHEN** either a shown upload or a shown download arrow is pulsing
- **THEN** the label reads "Synchronization ongoing…", regardless of the other direction's state

#### Scenario: Upload-only masks the download arrow and reads In sync when uploads complete
- **WHEN** direction is `UploadOnly`, `completed >= total`, and foreign downloads are irrelevant (never fetched)
- **THEN** the download arrow is force-hidden, the upload arrow is hidden, and the status line reads "In sync"

#### Scenario: Download-only masks the upload arrow and reads In sync when imports complete
- **WHEN** direction is `DownloadOnly`, `downloaded >= total`, and the own-device gallery has un-uploaded photos
- **THEN** the upload arrow is force-hidden, the download arrow is hidden, and the status line reads "In sync" (the un-uploaded gallery does not keep it out of sync)

#### Scenario: Download-only with imports remaining shows only the download arrow
- **WHEN** direction is `DownloadOnly` and `downloaded < total`
- **THEN** only the download arrow is shown (the upload arrow is force-hidden) and the label reflects the download activity

#### Scenario: Both complete reads In sync
- **WHEN** direction is `Both`, `completed >= total`, and `downloaded >= total`
- **THEN** the status line reads "In sync" with no arrows

#### Scenario: Needs-access line is tappable to the right action
- **WHEN** the health is `NeedsAccess(NOT_DETERMINED)` and the status line is tapped
- **THEN** `onRequestPermission()` is invoked; **WHEN** the health is `NeedsAccess(DENIED)` and it is
  tapped, `onOpenSettings()` is invoked

