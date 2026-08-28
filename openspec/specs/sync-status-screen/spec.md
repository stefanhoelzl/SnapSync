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

Decision record: `changes/archive/2026-06-27-permission-on-status-screen`,
`changes/archive/2026-08-26-honest-sync-total` (a cold launch never claims settled),
`changes/archive/2026-08-28-make-the-screen-a-function-of-state` (the screen is a function of its state).
## Requirements
### Requirement: Sync status snapshots reduce to UI state
The presentation layer SHALL reduce config presence, permission, each observed `SyncStatus`, and any
**pending join** to a display-ready `UiState`. `SyncProgress`, its `SyncStatusSource` seam, the
`SyncStatus` vocabulary, and the three-state classification are owned by the `sync-status` capability —
this screen consumes them.

`UiState` SHALL have exactly these families: the create layer (`CreateEvent(error?)` /
`CreatingEvent`, owned by the `event-creation-ui` capability and outranking everything on config
absence); the **`JoiningEvent`** family (owned by the `join-event` capability, carrying the pending
`eventId`, the loaded event details, the member's uncommitted form, and its details phase), which
represents an interactive join confirmation in progress; and a single **`Joined`** state carrying the
persisted membership, the derived invite URL, the rename status, the selected joined surface, a health
descriptor and an optional **`pendingSwitch`** (owned by `join-event`) for a switch confirmation over the
joined screen. The prior joined states `InProgress`, `Completed`, and `NothingToSync`, the permission
state `PermissionBlocked`, and the standalone `Loading` state are **removed** and fold into `Joined` (the
joined loading first-frame is a health value, `SyncHealth.Loading`).

`Joined`'s membership SHALL be **non-null**: the reduction reaches `Joined` only when config is present,
so a nullable membership would state a combination the reduction makes unreachable and force the screen to
re-check it.

The event details a join confirmation carries SHALL be stated **once** per confirmation rather than
repeated on each phase that needs them. The phases that have loaded details SHALL be expressed as one
detailed phase carrying those details plus its step, so that a step which requires details cannot be
constructed without them; the phases that have none (`Loading`, `NotFound`, `LoadFailed`) SHALL carry
none. A retry therefore commits with the floor, the ceiling and the retention deadline still in hand
without any phase restating them.

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
invites the question it fails to answer. It carries its own copy although `Joined` now also carries the
membership: `SyncHealth` stays self-contained, so a health value can be rendered — and forged — without a
membership in hand.

`Unattested` SHALL rank **below** `NeedsAccess` and `NotStarted` and **above** every snapshot-derived
value. Uploads are gated on an App Attest device token (capability `device-attestation`); when none is
usable, nothing of the member's can upload at all, so a snapshot-derived line would say "Syncing" while
every upload `401`s — the lie this rung exists to prevent. It ranks below the two above it for the reason
they rank where they do: without library access, or before the event begins, nothing could upload anyway,
so an unusable token is not yet the member's problem, and two attention lines would only compete.

`Unattested` SHALL be raised **only** when there is no usable token **and** obtaining one failed — never
for a merely stale token, which the next wake renews. **"No usable token" means the token is absent,
unparseable, or past its expiry**, and nothing wider: a token inside the renewal margin still authorises
every gated request until it expires, so a renewal that fails against one changes nothing the member can
see and SHALL NOT raise this rung. The margin that decides *when the app renews* is `device-attestation`'s
and is deliberately wide; reusing it here would state that sharing is paused for up to a week while every
upload was in fact authorised.

**The value this rung reduces from SHALL NOT predate the screen's own entry.** Attestation is re-checked
only when the app process wakes, and a process may carry an outcome across an arbitrary suspension; a
foreground entry SHALL therefore reduce from the refresh that entry triggers, not from whatever the last
wake concluded. Until that refresh reports, the reduction SHALL fall through to the snapshot-derived
health exactly as it does when the token is usable — the first frame after a foreground entry SHALL NOT
render a verdict formed under conditions that no longer hold.

Decision record for both the narrowed predicate and the freshness rule: `changes/archive/2026-08-25-correct-attestation-health-surfacing`.

`Unattested` is **not actionable**: there is nothing for the member to do, and the screen SHALL NOT ask
them for anything (capability `design-system`). Because opening the app **is** a wake, looking at the
screen renews the token and clears the line — so it survives to be seen only when renewal itself keeps
failing (offline, or the backend refusing this device), which is a real problem that would otherwise be
invisible.

`UiState` SHALL carry **no** upload/download counts — the joined states no longer surface `synced`,
`total`, or an in-progress number. The event **name** SHALL NOT be carried separately from the membership
that already holds it, and the invite URL SHALL be carried as a reduced field derived once from the
membership's `eventId` (capability `event-invite-qr`), so that the rendered QR and the shared link cannot
disagree. Neither is supplied to the screen as a parameter.

The reduction MUST depend only on the latest snapshot (**no event history**, so that any missed
intermediate snapshot cannot corrupt the displayed state) **and the current instant**. This bounds how the
reduction may read the **snapshot stream**; it does not forbid reducing a current value that a source
remembers, which the create-failure status already is. The container's initial UI state SHALL be computed
from the sources' current values at construction. `UiState.Loading` and every `Joined` health value are
derived from real source values (never placeholders). Once a join has committed there is no join-status
reduction: during the (re)join provisioning the screen simply shows the current `Joined` health (typically
`Syncing`); the `JoiningEvent` family is the **pre-commit** confirmation gate only.

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

#### Scenario: A token near expiry that could not be renewed does not reduce to Unattested
- **WHEN** config is present, permission grants photo access, the event has started, and the device holds a
  token that is inside the renewal margin but has not expired, whose renewal failed
- **THEN** the `Joined` health derives from the snapshot as usual, and is not `Unattested`

#### Scenario: An expired token that could not be replaced reduces to Unattested
- **WHEN** the same conditions hold but the token is absent, unparseable, or past its expiry, and obtaining
  a usable one failed
- **THEN** the `Joined` health is `Unattested`

#### Scenario: The first frame after a foreground entry does not reduce from an earlier wake's verdict
- **WHEN** a prior wake concluded that no usable token could be obtained, and the screen is entered before
  the refresh that entry triggers has reported
- **THEN** the `Joined` health derives from the snapshot, not `Unattested`; the rung is raised only if that
  refresh also fails

#### Scenario: A joined state carries its membership and invite URL
- **WHEN** config is present and the reduction produces `Joined`
- **THEN** the state carries the persisted membership as a non-null value and the invite URL derived from
  its `eventId`, and the screen receives neither as a separate parameter

#### Scenario: A loaded join step cannot exist without its event details
- **WHEN** a join confirmation is on a step that renders or commits the event's name, floor, ceiling or
  retention deadline
- **THEN** those details are present by construction, because the step is carried by the detailed phase
  that holds them

#### Scenario: A remembered current value may be reduced
- **WHEN** the create-failure status or the transient invalid-link error holds a current value
- **THEN** the reduction may fold it into the state — the no-event-history rule bounds how the snapshot
  stream is read, not whether a current value may be read

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
  **is** a wake. Offering a tap would promise a remedy that does not exist. Its **detail line** SHALL state
  only what is true for **every** cause this one state absorbs: the app is offline, or the backend is
  refusing this device, and the screen cannot tell which. It SHALL NOT name a single cause as though it
  were known, and SHALL NOT prescribe an action that is already under way — telling a member to reopen the
  app is telling them to do the thing that triggered the refresh they are waiting on, and telling them to
  check their connection is wrong whenever the backend is the one refusing. What holds in both cases is
  that retries continue and no photo is lost, and that is what the line SHALL say.
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


#### Scenario: The cannot-verify detail line prescribes nothing and blames nothing
- **WHEN** the health is `Unattested` and the status line is rendered
- **THEN** the detail line states that the app keeps retrying and that no photo is lost, and names neither
  a connection problem nor a backend refusal as the cause

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
granted, exactly as at join). It SHALL be present **whether or not a `pendingSwitch` is carried**: a
reconfigure racing a switch's config write is already prevented by `ReconfigureEvent`'s own `eventId`
guard (a surface opened for a different membership persists nothing) and by the screen closing the
surface when the config clears, and suppressing it here suppressed it during a join's own in-flight
commit too — the commit carries a pending join for the event being joined, which is not a switch.
Unlike the share action — which additionally requires a non-null invite URL — the settings action's
presence SHALL depend only on the joined layer rendering.

#### Scenario: Settings appears in the joined action row in every health state
- **WHEN** the UI state is `Joined` with health `NeedsAccess(DENIED)`
- **THEN** the joined layer renders a settings action alongside share and leave, and tapping it opens the reconfigure surface

#### Scenario: Settings is available without photo access
- **WHEN** the UI state is `Joined` with health `NeedsAccess(NOT_DETERMINED)`
- **THEN** the settings action is present and tappable (a member may adjust direction, cutoff, or album before granting access)

#### Scenario: Settings stays offered while a pending join or switch is carried
- **WHEN** the UI state is `Joined` carrying a `pendingSwitch`
- **THEN** the settings action is still offered, exactly as it is with none — including for the whole of a join's own commit, which carries a pending join for the event being joined

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

### Requirement: The joined layer offers a rename affordance on the event heading

In the `Joined` state the screen SHALL render an **edit affordance beside the event-name heading**
(`ScreenLayout`'s `onEditHeading`, capability `design-system`). Tapping it SHALL open the rename dialog
(capability `event-rename`).

Unlike the diagnostic-dump gesture — a **hidden** double-tap on the app-name label, which exposes no click
semantics precisely so it does not read as a control (capability `diagnostic-logging`) — the rename
affordance SHALL be a **visible, discoverable control** with click semantics and an accessibility label.
The two occupy different slots of the same layout, the app-name label and the heading, so neither can
shadow the other.

The affordance SHALL be present in **every** `Joined` health value — including `NeedsAccess`,
`Unattested`, `NotStarted`, `Loading`, `InSync`, and `Syncing` — because renaming needs neither photo
access nor a started event. It SHALL be present **whether or not a `pendingSwitch` is carried**, for the
same reason the settings affordance is: `RenameEvent`'s own `eventId` guard already makes a rename that
lands after a switch a no-op, and suppressing it here suppressed it for the whole of a join's own commit.

The affordance SHALL be offered only where a heading is rendered — so not on the create screen, not in
any join-gate phase, and not while the reconfigure surface is open.

#### Scenario: Rename appears beside the heading in every joined health state
- **WHEN** the UI state is `Joined` with health `NeedsAccess(DENIED)`
- **THEN** the screen renders an edit affordance beside the event-name heading, and tapping it opens the
  rename dialog

#### Scenario: Rename is available without photo access and before the event starts
- **WHEN** the UI state is `Joined` with health `NotStarted`, or with health `NeedsAccess(NOT_DETERMINED)`
- **THEN** the rename affordance is present and tappable

#### Scenario: Rename stays offered while a pending join or switch is carried
- **WHEN** the UI state is `Joined` carrying a `pendingSwitch`
- **THEN** the rename affordance is still offered, exactly as it is with none — including for the whole of a join's own commit

#### Scenario: Rename is absent where there is no heading
- **WHEN** the screen shows the create screen, any join-gate phase, or the reconfigure surface
- **THEN** no rename affordance is rendered

#### Scenario: The rename affordance is a control, the diagnostic gesture is not
- **WHEN** the joined screen's accessibility tree is inspected
- **THEN** the heading's rename affordance exposes a click action and a label, while the app-name label
  still exposes no click action and no control affordance

### Requirement: The joined heading reflects a renamed event

The event-name heading SHALL render the persisted membership's current name, so a successful rename
performed on this device is visible as soon as it is persisted, and a rename performed by another member
becomes visible when the membership refresh folds it in (capability `join-event`). The heading SHALL read
that name from the membership the `Joined` state already carries; there SHALL NOT be a second event-name
value beside it.

The rename SHALL NOT introduce a `UiState` family or a new branch in the status reduction: the rename
status SHALL be carried as a field of the `Joined` state, alongside the membership it concerns. It is not
a layer and not a health — it changes one string and one dialog's state — so it adds a field, never a
family or a precedence rung.

#### Scenario: The heading updates after a local rename
- **WHEN** a rename succeeds and the new name is persisted
- **THEN** the heading renders the new name without any further user action

#### Scenario: The heading updates after a remote rename
- **WHEN** another member renames the event and this device's foreground refresh persists the new name
- **THEN** the heading renders the new name

#### Scenario: The reduction gains no rename family or rung
- **WHEN** the status reduction's `UiState` families and the `Joined` health precedence are enumerated
- **THEN** no family and no health rung represents a rename; the rename status is a field of `Joined`

#### Scenario: One name, one source
- **WHEN** the heading, the rename dialog's prefill, and the reconfigure surface's header each render the
  event name
- **THEN** all three read the membership's `name`, and no separate event-name value is carried

### Requirement: A failing command never disables the status container

The presentation container SHALL keep processing commands after any one of them fails. A throwable escaping
a single command SHALL NOT prevent a later command from running, and SHALL NOT terminate the composition's
scope.

This is a **liveness** requirement about the container, not about any one screen: the commands crossing it
are every user tap the app has — leave, share, settings save, rename, join confirm, cancel, create — so a
container that stops processing them leaves a screen that renders its last state, looks alive, and silently
ignores the member. The MVI library's default is the opposite: with no exception handler configured it
re-throws, cancelling the non-supervisor job that parents every command, after which no later command runs
for the life of the process. The container SHALL therefore configure a handler rather than rely on that
default, and the requirement SHALL be pinned by a test so a library upgrade that changes the behavior fails
the build instead of silently restoring it.

The failure SHALL NOT become silent in the process (law "Absence is never silent"). The seam carrying the
throwable SHALL default to inert, so harnesses and tests that do not bind it construct unchanged — but every
**live composition** SHALL bind it, and SHALL report the throwable at **`Error`** severity, which is the
threshold at which a log line becomes a crash-reporting event rather than a breadcrumb (capability
`crash-reporting`). Binding nothing in a shipped app would lose the only signal that a user's command failed
at all, since the container now absorbs the throwable instead of letting it reach the composition scope.

#### Scenario: A later command still runs after one fails
- **WHEN** a command throws, and another command is issued afterwards
- **THEN** the later command runs and its state change is observed

#### Scenario: The failure is reported, not swallowed
- **WHEN** a command throws in a live composition
- **THEN** the throwable is reported at `Error` severity through the container's error seam, reaching the
  device log and the crash reporter

#### Scenario: The composition scope survives
- **WHEN** a command throws
- **THEN** the composition's scope remains active and the app does not terminate

### Requirement: A joined cold launch never claims settled

The joined layer SHALL NOT render the settled "In sync" line before the snapshot's inputs have been
read. On a cold launch — and on any entry where the status projection has not yet read the gallery
total, the ledger counts, or the download projection — the health SHALL be `SyncHealth.Loading`, which
the status line renders as its neutral in-progress line, never as the settled indicator.

This follows from the precedence already stated in *Sync status snapshots reduce to UI state*
(`SyncStatus.Loading` → a joined loading first-frame, ranked above every `Ready`-derived value) and from
that requirement's rule that every `Joined` health value is derived from real source values, never
placeholders. It is stated as its own requirement because the rule was being satisfied only vacuously:
the arrow derivation hides an arrow when `synced >= total`, and a placeholder `total` of `0` satisfies
`0 >= 0` on **both** arms, so a snapshot minted over unread inputs reduced to `InSync` — a check mark
reading "In sync" — on a device that had counted nothing. The member-visible consequence is a status
that appears settled and then, seconds or minutes later, appears to regress to "Synchronization
ongoing…" with no photos taken in between (`SNAPSYNC-14`, `SNAPSYNC-16`).

The settled line asserts "everything of yours is shared and everything of theirs is received". The
screen SHALL make that assertion only over counts it has.

`SyncHealth.Loading` SHALL remain a **neutral** line — no check indicator, no direction arrows, no
attention background — so that a member who sees it is told that the app is working it out, and is not
told an answer.

#### Scenario: A cold launch renders the neutral line, not the settled one

- **WHEN** the app launches into a joined membership with photo access granted, and the status
  projection has not yet read the gallery total or the ledger counts
- **THEN** the joined health is `SyncHealth.Loading` and the status line renders neutrally — no check
  indicator and no "In sync" text

#### Scenario: A short visit that never completes a read never shows In sync

- **WHEN** the member opens the app and leaves it before any status read completes
- **THEN** the status line showed the neutral in-progress line for the whole visit, and the settled
  "In sync" line was never rendered

#### Scenario: The settled line appears only once the counts are read

- **WHEN** the gallery total, the ledger counts and the download projection have all been read, and
  both direction arrows are hidden by their own counts
- **THEN** the status line reads "In sync" with the settled indicator

#### Scenario: A read zero total still settles

- **WHEN** the membership contributes nothing (a counted upload total of `0`) and the download
  projection has been read with its imports complete
- **THEN** both arrows are hidden and the status line reads "In sync" — a counted zero settles the
  screen exactly as it does today

#### Scenario: Counts arriving do not read as a regression

- **WHEN** the status projection completes its first read and the counts show work outstanding
- **THEN** the line moves from the neutral in-progress line to "Synchronization ongoing…" or
  "Synchronization pending…" — never from the settled "In sync" line, because that line was never shown

### Requirement: The status screen is a function of its UI state

The status screen SHALL receive **every value it renders** through `UiState`. Beside the state it SHALL
receive only its callback bundle and the shareable-count query with that query's own recompute key
(capability `join-share-count`) — no other data input SHALL reach it by any other route.

The rule is: **what the screen SHOWS is `UiState`; how it DRAWS is local.** Dialog and surface visibility
is *shown*, and SHALL be reduced state. Scroll position, animation progress, focus, and a picker's
internal wheel state are *drawn*, and SHALL remain screen-local.

Exactly two exceptions SHALL exist, and both are stated rather than derived:

1. **In-progress text content** SHALL remain screen-local. A per-keystroke round trip through the
   presentation container fights the platform IME. A text surface's **presence and its seed** SHALL be
   reduced state; the characters typed since it opened SHALL NOT be. The consequence is stated so it is
   not discovered later: a consumer rendering this screen from a transported `UiState` can know a sheet
   is open and with what prefill, but not what has been typed.
2. **The design-system module** (`:ui:components`) MAY own the visibility of a control's own popup, which
   is part of how that control draws itself. This rule is scoped to the screen module.

This SHALL be enforced mechanically rather than by review: an architecture guard SHALL assert that
`:ui:screens` declares no Compose-remembered mutable state outside a named allowlist covering exception
1. An unenforced rule is what produced the drift this requirement removes.

#### Scenario: A data value renders only if it is in the state

- **WHEN** the status screen's parameter list is enumerated
- **THEN** it contains the `UiState`, the callback bundle, the shareable-count query and that query's
  recompute key, and no other data parameter

#### Scenario: A call site cannot silently omit a rendered value

- **WHEN** a host composes the status screen and supplies the state
- **THEN** every value the screen renders is present, because it travels inside that one state — there is
  no separate value a host can forget to pass

#### Scenario: Surface and dialog visibility is reduced state

- **WHEN** the leave confirmation, the rename sheet, the diagnostic sheet, or the reconfigure surface is
  open
- **THEN** the `UiState` says so, and a consumer rendering from that state alone shows the same surface

#### Scenario: The guard rejects new screen-held state

- **WHEN** Compose-remembered mutable state is declared in `:ui:screens` outside the allowlist
- **THEN** the architecture guard fails the build, naming the declaration

### Requirement: A rejected event link is told to the member on whatever layer is showing

A link the decoder rejects SHALL surface to the member **wherever it arrives**. The gate decodes every
delivered link regardless of layer, so the transient invalid-link message (capability `event-link`) SHALL
reach the joined layer as well as the create layer, and SHALL self-clear on the same window in both.

Before this it reached only the create layer: the message was set, the joined layer had nowhere to render
it, and a member who scanned a bad QR while already joined was told **nothing at all**. "Nothing happened"
and "that code wasn't valid" are different answers, and only the first was reachable (spec
`module-architecture`, "Absence is never silent").

#### Scenario: A bad scan while joined says so

- **WHEN** a member is joined and a link the decoder rejects is delivered
- **THEN** the joined layer states that the code was not valid, persisted config is unchanged, and the
  message self-clears

#### Scenario: One choreography, both layers

- **WHEN** the same rejected link arrives on the create layer instead
- **THEN** the create layer's one inline error carries it, and it self-clears on the same window — the
  set-then-clear is stated once, not once per layer

### Requirement: The join and reconfigure form is reduced state

The presentation container SHALL hold the member's **uncommitted choices** on both decision surfaces and
reduce them into `UiState`, rather than the screen remembering them: the two participation switches, the
album opt-in, and the four capture-range preset/custom values.

The container SHALL seed them: with the defaults at the join gate (all switches on, the full event window),
and with the reconstruction from the persisted membership at the reconfigure surface (capability
`reconfigure-membership` — the reconstruction rule itself is unchanged, only its location). The container
SHALL resolve them against the event window using the same rules for both surfaces, and SHALL carry the
resolved bounds, the derived direction, the commit-enabled verdict, and the shareable count in the state.

The resolved capture bounds SHALL be carried as **local wall-clock** values, converted in the reduction
using the container's injected time source, so the screen holds no clock or timezone knowledge and
requires no formatter parameter.

Seeding, resolution and discarding SHALL touch no port. Opening a surface, changing a choice, and
cancelling SHALL remain client-side acts that reach no use case; only the commit (Join, or Save) SHALL
cross a command.

#### Scenario: A choice survives a phase change without the screen remembering it

- **WHEN** the join gate advances `Ready` → `Committing` → `CommitFailed` and the member retries
- **THEN** the retry commits the range and direction the member chose, because those live in the state
  rather than in the composition

#### Scenario: The resolved range cannot invert

- **WHEN** any combination of preset and custom values is reduced against an event window
- **THEN** the upper bound resolves first and the lower bound is floored to it, so the resolved range is
  never inverted, and both bounds lie within the event window

#### Scenario: Cancelling discards the choices and writes nothing

- **WHEN** the member changes controls on either surface and cancels
- **THEN** the choices return to their seed, no port is touched, and no config write occurs

#### Scenario: The screen needs no formatter

- **WHEN** the status screen renders a resolved capture range
- **THEN** it renders wall-clock values the reduction already converted, and takes no clock, timezone, or
  formatter parameter
