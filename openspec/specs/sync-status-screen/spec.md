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

### Requirement: Joined-layer health descriptor and status line

In the `Joined` state the screen SHALL render the event **name** as the title and a **single status
line** — never numeric counts. The status line SHALL present one of:

- `NeedsAccess` → an attention affordance reading "Turn on photo access" that is **tappable**:
  tapping SHALL invoke `onRequestPermission()` when permission is `NOT_DETERMINED` and
  `onOpenSettings()` when `DENIED`. It is the only status-line state that is **actionable**, and one of
  exactly two that carry a background.
- **`Unattested`** → an attention affordance stating that this device cannot be verified. It carries a
  background, like `NeedsAccess`, because it is attention — but it is **not tappable** and carries **no**
  chevron, because there is no action the member can take: the app renews on the next wake, and opening it
  **is** a wake. Offering a tap would promise a remedy that does not exist.
- **`NotStarted`** → a **clock** indicator reading **"Starts &lt;date&gt;, &lt;time&gt;"**, rendered in the
  device's local timezone. It is **not** tappable and carries **no** background (it is information, not
  an action). It renders in the **same slot** as every other status line — directly beneath the invite
  QR — so the joined layer never grows a second line.
- `InSync` → a settled indicator (e.g. a check) reading "In sync", with no direction arrows.
- `Syncing` → two independent direction arrows plus an **activity-dependent label**, each arrow in a
  shown/pulse state derived **from its own counts alone**:
  - **upload arrow**: hidden when `completed >= total`; else **pulsing** when `pending > 0`, otherwise
    **static**;
  - **download arrow**: hidden when `downloaded >= total`; else **pulsing** when `inFlight > 0`, otherwise
    **static** (`inFlight` from `DownloadProgress`, per the `sync-status` capability).

Arrow derivation SHALL NOT read the membership's participation direction, and SHALL NOT force-hide an arrow.
An opted-out direction contributes **no work**, so its total is `0` and its arrow is hidden by the ordinary
completeness rule: the upload total is `0` for a non-contributing membership (capability
`photo-selection-policy`), and the download total is `0` for a membership that never reconciles (capability
`photo-download`, whose total is populated only by that reconcile). The arrows therefore agree with the
direction because the counts already do.

A force-hidden arrow is prohibited because it can only ever conceal a mismatch between the direction contract
and what the system is actually doing — and concealing that mismatch is how a download-only membership came to
upload the member's camera roll for a full release cycle while the screen read "In sync" (see
`upload-lifecycle`). If the counts are right the arrow is already correct; if they are wrong, an arrow the
member never asked for is the only signal anyone gets. The display SHALL NOT assert a contract the system is
not keeping.

The `Syncing` **label** SHALL be derived from the combined arrow activity: when **any** shown arrow is
**pulsing** (work in flight), the label reads **"Synchronization ongoing…"**; when at least one arrow is
shown but **none** is pulsing (work remains but nothing is in flight), the label reads
**"Synchronization pending…"**. The exact label strings are owned by the `App*` status-line component
(see `design-system`); this screen supplies only the health value.

`InSync` SHALL be shown exactly when both arrows are hidden. Because an opted-out direction's total is `0`,
this settles over the enabled direction(s) without the screen knowing which they are: an `UploadOnly`
membership reads `InSync` when uploads are complete regardless of any foreign downloads, and a `DownloadOnly`
membership reads `InSync` when imports are complete regardless of the own-device gallery. Any remaining work
in an **enabled** direction SHALL be `Syncing` with that direction's arrow shown. "Shown" tracks
completeness; "pulse" tracks live activity — so a photo captured but not yet uploaded (on an upload-enabled
membership) shows a **static** upload arrow under the "Synchronization pending…" label. The direction remains
**silent**: no textual mode label is rendered; the single remaining arrow implies it.

#### Scenario: The not-started line names the start instant
- **WHEN** the health is `NotStarted` for an event starting at `2026-07-14T18:00:00Z` and the device is in
  a `UTC+2` zone
- **THEN** the status line shows a clock indicator reading the start rendered in local time (`20:00` on
  14 Jul), beneath the QR, flat and not tappable

#### Scenario: Upload in flight pulses the up arrow and reads ongoing
- **WHEN** `completed < total`, `pending > 0`, and downloads are complete
- **THEN** the status line reads "Synchronization ongoing…" with the upload arrow **pulsing** and the
  download arrow hidden

#### Scenario: Work queued but OS idle shows a static arrow and reads pending
- **WHEN** `completed < total` and `pending == 0`
- **THEN** the upload arrow is **shown static** (not pulsing) and the line reads "Synchronization
  pending…"

#### Scenario: Download in flight pulses the down arrow and reads ongoing
- **WHEN** uploads are complete, `downloaded < total`, and `inFlight > 0`
- **THEN** the status line reads "Synchronization ongoing…" with the download arrow **pulsing** and the
  upload arrow hidden

#### Scenario: Any direction in flight reads ongoing
- **WHEN** either a shown upload or a shown download arrow is pulsing
- **THEN** the label reads "Synchronization ongoing…", regardless of the other direction's state

#### Scenario: Upload-only hides the download arrow through its zero total
- **WHEN** the membership is `UploadOnly` (so no reconcile ever runs and the download total is `0`) and
  `completed >= total`
- **THEN** both arrows are hidden and the status line reads "In sync"

#### Scenario: Download-only hides the upload arrow through its zero total
- **WHEN** the membership is `DownloadOnly` (so the upload total is `0`), `downloaded >= total`, and the
  own-device gallery holds un-uploaded photos
- **THEN** both arrows are hidden and the status line reads "In sync" — the un-uploaded gallery does not
  count toward the total, so it does not keep the screen out of sync

#### Scenario: Download-only with imports remaining shows only the download arrow
- **WHEN** the membership is `DownloadOnly` and `downloaded < total`
- **THEN** only the download arrow is shown (the upload arrow is hidden by its zero total) and the label
  reflects the download activity

#### Scenario: An upload arrow appears if a non-contributing membership ever uploads
- **WHEN** the membership is `DownloadOnly` yet the upload counts report work — the direction gate is not
  being honored
- **THEN** the upload arrow is **shown**, because no mask suppresses it; the screen surfaces the breach
  rather than reading "In sync"

#### Scenario: Both complete reads In sync
- **WHEN** the membership is `Both`, `completed >= total`, and `downloaded >= total`
- **THEN** the status line reads "In sync" with no arrows

#### Scenario: Needs-access line is tappable to the right action
- **WHEN** the health is `NeedsAccess(NOT_DETERMINED)` and the status line is tapped
- **THEN** `onRequestPermission()` is invoked; **WHEN** the health is `NeedsAccess(DENIED)` and it is
  tapped, `onOpenSettings()` is invoked

### Requirement: The not-started health advances on a foreground tick

The presentation container SHALL re-evaluate `startsAt > now` on a **one-minute tick**, which SHALL run
**only** while the app is foregrounded **and only** while the event has not yet started — it SHALL stop
itself once the start passes, and SHALL NOT run for the entire life of a joined event. The tick is
necessary because the `NotStarted` health depends on **wall-clock time**, not on any ledger event, so no
snapshot emission would ever retire it when the start passes.

The tick SHALL live in the **presentation** layer (which already owns a coroutine scope and the injected
time source), **not** in the `feature/status` projection. The status projection SHALL remain a clock-free, read-only
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
- **WHEN** the `feature/status` projection is inspected
- **THEN** it reads only the ledger and holds no clock, the not-started derivation living entirely in the
  presentation reduction

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

### Requirement: The joined layer offers an allow-full-access affordance under a limited grant

While permission is `LIMITED` and config is present, the joined layer SHALL render a persistent
**"Allow full access"** affordance directly **below** "Choose more photos", invoking the app's
system-Settings route (the existing `openSettings` command) — the only mechanism iOS offers, since no
API re-raises the full-access dialog under `.limited` (capability `limited-photo-access`, "An upgrade
to full access is an offered route and an ordinary transition"). It SHALL share its neighbor's calm
resting posture: outside the status-line slot, present regardless of the current health value, the
same quiet borderless-text visual weight, and never an attention state — the limited grant stays
first-class, and this affordance is an offer, not a nag. No interstitial consent surface SHALL be
interposed between the tap and Settings: the label plus the OS-mediated toggle are the consent, and
the widened scope stays bounded by the cutoff and origin exclusions (`photo-selection-policy`) like
every full-access membership. Under any other permission value the affordance SHALL be absent.

#### Scenario: The affordance shows under limited, below choose-more, in every health state
- **WHEN** permission is `LIMITED` with config present, while the health is `InSync`, `Syncing`, or
  `NotStarted`
- **THEN** the "Allow full access" affordance renders directly below "Choose more photos" in each
  case, and the status line above both is unchanged

#### Scenario: Tapping it opens the system Settings page
- **WHEN** the member taps "Allow full access"
- **THEN** the app's system Settings page opens via the `openSettings` command, and no in-app dialog
  or permission request is raised

#### Scenario: The affordance is absent under full access
- **WHEN** permission is `GRANTED`
- **THEN** no "Allow full access" affordance renders

### Requirement: The joined layer offers a settings affordance next to share and leave

In the `Joined` state the screen SHALL render a flat icon-only **settings** action (capability
`design-system`) in the joined-layer action cluster, ordered **settings · share · leave**. Tapping it
SHALL open the reconfigure surface (capability `reconfigure-membership`). The settings action SHALL be
present in **every** `Joined` health value — including `NeedsAccess` (`NOT_DETERMINED` / `DENIED`),
`Unattested`, `NotStarted`, `Loading`, `InSync`, and `Syncing` — because a member can change
direction / cutoff / album without photo access (enabling upload simply does nothing until access is
granted, exactly as at join). It SHALL be **suppressed while an event-switch is in progress** (a
`pendingSwitch` is present) so a reconfigure cannot race a switch's config write. Unlike the share action
— which additionally requires a non-null invite URL — the settings action's presence SHALL depend only on
the joined layer rendering (and the absence of a pending switch).

#### Scenario: Settings appears in the joined action row in every health state
- **WHEN** the UI state is `Joined` with health `NeedsAccess(DENIED)` and no switch is pending
- **THEN** the joined layer renders a settings action alongside share and leave, and tapping it opens the reconfigure surface

#### Scenario: Settings is available without photo access
- **WHEN** the UI state is `Joined` with health `NeedsAccess(NOT_DETERMINED)`
- **THEN** the settings action is present and tappable (a member may adjust direction, cutoff, or album before granting access)

#### Scenario: Settings is suppressed during a pending switch
- **WHEN** the UI state is `Joined` carrying a `pendingSwitch`
- **THEN** the settings action is not offered, so a reconfigure cannot race the switch's config write

### Requirement: The joined layer marks an ended event

When the membership carries an `endsAt` and `now > endsAt`, the `Joined` state SHALL carry an **"Event
ended"** marker rendered on its **own line, directly above** the regular status line. The marker SHALL NOT
be an inline prefix of the status text, and the two SHALL NOT be joined by a separator into a single
phrase.

Two facts are being stated, and they are unrelated: the event's **capture window** has closed, and the
device's **transfer** is in some state. Rendered inline as `Event ended · Synchronization pending…` they
read as one sentence — a claim *about* the syncing — which is false: sync continues exactly as before,
and it is the window that ended. On a phone-width line the pair also wraps mid-phrase, breaking wherever
the text happens to run out rather than between the two facts. Stacking states each fact once and lets the
status keep the full width it was designed for.

The marker SHALL be styled **subordinate** to the status it labels, so the health value remains the
primary thing read.

The marker SHALL be purely **informational**: it SHALL NOT change any arrow, count, or the underlying
health value, and sync SHALL continue exactly as before the end passed — `endsAt` bounds only which
photos may be uploaded (capability `event-limits`), it closes nothing, and the client asserts no
lifecycle enforcement.

The marker SHALL be computed from the now-stored `endsAt` and the existing foreground **`nowTick`**,
symmetric with the existing not-started line: it SHALL advance on the one-minute foreground tick, so an
event that ends while the screen is foregrounded gains the marker within one minute without any ledger
event. When the membership carries **no** `endsAt` (a pre-backfill legacy membership, capability
`event-rejoin-reconciliation`), no "Event ended" marker SHALL be shown.

#### Scenario: A past end marks the health line on its own line
- **WHEN** config is present, the membership's `endsAt` is before `now`, and the snapshot-derived health
  is `InSync`
- **THEN** "Event ended" is rendered as its own line above the status, the status reads `In sync`
  unchanged, the two are not joined into one phrase, and sync continues

#### Scenario: The marker is informational while syncing continues
- **WHEN** the membership's `endsAt` is past and work still remains (a `Syncing` health)
- **THEN** the "Event ended" marker is shown above the `Syncing` status, no upload or download is halted
  by the marker, and the marker text never merges into the status text

#### Scenario: The marker appears on the foreground tick when the end passes
- **WHEN** the app is foregrounded showing a joined event whose `endsAt` then passes
- **THEN** within one minute the joined layer gains the "Event ended" marker, computed from `endsAt` and
  the foreground `nowTick`, with no ledger event having occurred

#### Scenario: A membership without an endsAt shows no marker
- **WHEN** config is present but the membership carries no `endsAt` (a pre-backfill legacy membership)
- **THEN** no "Event ended" marker is shown, and the joined layer shows the regular status line alone
