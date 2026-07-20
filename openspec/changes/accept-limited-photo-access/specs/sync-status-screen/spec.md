# sync-status-screen — delta

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

- permission is `NOT_DETERMINED` or `DENIED` → `NeedsAccess(permission)` (the sole **actionable**
  attention state; there is no separate "not syncing" state — the status projection's only operational
  signal is permission). **`LIMITED` SHALL NOT reduce to `NeedsAccess`**: a limited grant is a working
  state (capability `limited-photo-access`), rendered as the ordinary health line plus the
  "Choose more photos" affordance (see the added requirement below);
- else the membership's **`startsAt` is in the future** → **`NotStarted(startsAt)`**;
- else **no usable device token could be obtained** → **`Unattested`** (capability `device-attestation`);
- else `SyncStatus.Loading` → a joined loading first-frame;
- else `SyncStatus.Ready` → `InSync` when settled, else `Syncing(...)` (the completeness/activity
  arrow derivation is specified in *Joined-layer health descriptor and status line*).

`NotStarted` SHALL rank **below** `NeedsAccess` and **above** the snapshot-derived values. Permission
outranks it because permission is the only **actionable** state — it carries a tappable affordance, and a
member must resolve it *before* the event begins or they will miss the start; burying it behind a clock
line would ambush them with a permission prompt at the very moment the party starts. Everything below it
is outranked because, before the start, nothing of the member's **can** be syncing (the floor guarantees
it, capability `photo-selection-policy`), so a snapshot-derived line would say nothing true that the clock
line does not say better.

`NotStarted` SHALL carry the start instant, so the screen can render *when* — a bare "not started yet"
invites the question it fails to answer.

`Unattested` SHALL rank **below** `NeedsAccess` and `NotStarted` and **above** every snapshot-derived
value. Uploads are gated on an App Attest device token (capability `device-attestation`); when none is
usable, nothing of the member's can upload at all, so a snapshot-derived line would say "Syncing" while
every upload `401`s — the lie this rung exists to prevent. It ranks below the two above it for the reason
they rank where they do: without library access, or before the event begins, nothing could upload anyway,
so an unusable token is not yet the member's problem, and two attention lines would only compete.

`Unattested` SHALL be raised **only** when there is no usable token **and** obtaining one failed — never
for a merely stale token, which the next wake renews. It is **not actionable**: there is nothing for the
member to do, and the screen SHALL NOT ask them for anything (capability `design-system`). Because opening
the app **is** a wake, looking at the screen renews the token and clears the line — so it survives to be
seen only when renewal itself keeps failing (offline, or the backend refusing this device), which is a real
problem that would otherwise be invisible.

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

#### Scenario: A limited grant reduces from the snapshot, not to NeedsAccess
- **WHEN** config is present, permission is `LIMITED`, and the event has started
- **THEN** the health derives from the snapshot (`Loading` / `InSync` / `Syncing`) exactly as under
  `GRANTED`, and `NeedsAccess` is not shown

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

## ADDED Requirements

### Requirement: The joined layer offers a choose-more-photos affordance under a limited grant

While permission is `LIMITED` and config is present, the joined layer SHALL render a persistent
**"Choose more photos"** affordance that invokes the limited-library picker (capability
`limited-photo-access`, "The app owns the limited-library picker"). It SHALL be a calm resting
affordance, present regardless of the current health value, rendered **outside the status-line slot**
(the status line stays single and unchanged — this is an affordance, not a health state, so the
status-line contract that the joined layer never grows a second *status* line is preserved). It SHALL
NOT be an attention state: a limited membership at rest is "In sync" over its selection, not a problem
to fix. Under any other permission value the affordance SHALL be absent.

#### Scenario: The affordance shows under limited in every health state
- **WHEN** permission is `LIMITED` with config present, while the health is `InSync`, `Syncing`, or
  `NotStarted`
- **THEN** the "Choose more photos" affordance renders beneath the status area in each case, and the
  status line above it is unchanged

#### Scenario: Tapping it presents the picker
- **WHEN** the member taps "Choose more photos"
- **THEN** the system limited-library picker is presented, and no permission dialog is raised

#### Scenario: The affordance is absent under full access
- **WHEN** permission is `GRANTED`
- **THEN** no "Choose more photos" affordance renders
