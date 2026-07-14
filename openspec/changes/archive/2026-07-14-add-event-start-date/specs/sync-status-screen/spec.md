## ADDED Requirements

### Requirement: The not-started health advances on a foreground tick

The presentation container SHALL re-evaluate `startsAt > now` on a **one-minute tick**, which SHALL run
**only** while the app is foregrounded **and only** while the event has not yet started — it SHALL stop
itself once the start passes, and SHALL NOT run for the entire life of a joined event. The tick is
necessary because the `NotStarted` health depends on **wall-clock time**, not on any ledger event, so no
snapshot emission would ever retire it when the start passes.

The tick SHALL live in the **presentation** layer (which already owns a coroutine scope and the injected
time source), **not** in `:domain:status`. The status projection SHALL remain a clock-free, read-only
ledger→`SyncStatus` projection: it has no notion of wall-clock time today, and giving it one to render a
label would be the wrong seam.

A staleness of up to one minute is accepted: nothing of the member's can upload before the start in any
case, so a briefly-late transition costs nothing but the label.

#### Scenario: The clock line retires itself when the start passes
- **WHEN** the app is foregrounded showing `NotStarted` and the event's `startsAt` passes
- **THEN** within one minute the health re-derives to the snapshot-driven value (`InSync` / `Syncing`)
  without any ledger event having occurred

#### Scenario: The tick does not run after the start
- **WHEN** the event has already started
- **THEN** no tick is scheduled, the health deriving from the snapshot alone

#### Scenario: The status projection stays clock-free
- **WHEN** the `:domain:status` projection is inspected
- **THEN** it reads only the ledger and holds no clock, the not-started derivation living entirely in the
  presentation reduction

## MODIFIED Requirements

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
derived from permission, the membership's **`startsAt`**, and the latest snapshot, in this precedence:

- permission ≠ `GRANTED` → `NeedsAccess(permission)` (the sole attention state; there is no separate
  "not syncing" state — the status projection's only operational signal is permission);
- else the membership's **`startsAt` is in the future** → **`NotStarted(startsAt)`**;
- else `SyncStatus.Loading` → a joined loading first-frame;
- else `SyncStatus.Ready` → `InSync` when settled, else `Syncing(...)` (the completeness/activity
  arrow derivation is specified in *Joined-layer health descriptor and status line*).

`NotStarted` SHALL rank **below** `NeedsAccess` and **above** the snapshot-derived values. Permission
outranks it because permission is the only **actionable** state — it carries a tappable affordance, and a
member must resolve it *before* the event begins or they will miss the start; burying it behind a clock
line would ambush them with a permission prompt at the very moment the party starts. Everything below it
is outranked because, before the start, nothing of the member's **can** be syncing (the floor guarantees
it, capability `photo-date-cutoff`), so a snapshot-derived line would say nothing true that the clock
line does not say better.

`NotStarted` SHALL carry the start instant, so the screen can render *when* — a bare "not started yet"
invites the question it fails to answer.

`UiState` SHALL carry **no** upload/download counts — the joined states no longer surface `synced`,
`total`, or an in-progress number. The event **name** and the invite URL are supplied to the screen as
parameters (per `event-invite-qr` and the config capability), not as reduced state, so the reduction
gains no branch for them.

The reduction MUST depend only on the latest snapshot (no event history) **and the current instant**. The
container's initial UI state SHALL be computed from the sources' current values at construction.
`UiState.Loading` and every `Joined` health value are derived from real source values (never
placeholders). Once a join has committed there is no join-status reduction: during the (re)join
provisioning the screen simply shows the current `Joined` health (typically `Syncing`); the
`JoiningEvent` family is the **pre-commit** confirmation gate only.

#### Scenario: A future start reduces to NotStarted
- **WHEN** config is present, permission is `GRANTED`, and the membership's `startsAt` is in the future
- **THEN** the UI state is `Joined` with health `NotStarted(startsAt)`, whatever the snapshot says

#### Scenario: Permission outranks the not-started state
- **WHEN** config is present, the membership's `startsAt` is in the future, and permission is `DENIED` or
  `NOT_DETERMINED`
- **THEN** the UI state is `Joined` with health `NeedsAccess(permission)` — the actionable state wins, so
  the member can grant access before the event begins

#### Scenario: A past start reduces from the snapshot as before
- **WHEN** config is present, permission is `GRANTED`, and the membership's `startsAt` is at or before now
- **THEN** the health derives from the snapshot exactly as it did before this change (`Loading` /
  `InSync` / `Syncing`)

#### Scenario: Settled snapshot reduces to In sync
- **WHEN** config is present, permission is `GRANTED`, the event has started, and a `Ready` snapshot with
  `completed == total` is observed (downloads also settled)
- **THEN** the UI state is `Joined` with health `InSync`

#### Scenario: Work remaining reduces to Syncing
- **WHEN** config is present, permission is `GRANTED`, the event has started, and a `Ready` snapshot with
  `completed < total` is observed
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
- **`NotStarted`** → a **clock** indicator reading **"Starts &lt;date&gt;, &lt;time&gt;"**, rendered in the
  device's local timezone. It is **not** tappable and carries **no** background (it is information, not
  an action). It renders in the **same slot** as every other status line — directly beneath the invite
  QR — so the joined layer never grows a second line.
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

#### Scenario: The not-started line names the start instant
- **WHEN** the health is `NotStarted` for an event starting at `2026-07-14T18:00:00Z` and the device is in
  a `UTC+2` zone
- **THEN** the status line shows a clock indicator reading the start rendered in local time (`20:00` on
  14 Jul), beneath the QR, flat and not tappable

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
