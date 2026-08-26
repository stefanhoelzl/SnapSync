# join-event Specification

## Purpose

The join gate: scanning a QR or opening an event link no longer joins silently. The app loads and
verifies the event's details, shows an explicit confirmation, and only on confirm enrolls the device with the
backend and provisions the config.

The surface is deliberately a **distinct, extensible `UiState` family** rather than a bare confirm dialog,
because joining is where a member's participation is *configured*, and those options were always going to
accumulate: the capture-date cutoff (`photo-selection-policy`), the upload/download direction
(`add-join-direction-mode`), and the per-event album opt-in (`event-album`) are all rows on this screen. A
dialog could not have grown them.

Switching events composes leave-then-join as **two user-visible acts**: the confirmation's confirm departs
the previous event, and the join that follows is this same surface, so a switching member configures the
new membership exactly as any other joiner does. `autoJoin` auto-confirms the gate for the device that
just created the event, which has already expressed intent.

**One active membership at a time is the current contract, not a law of the product.** Joining a
different event is a switch, and every joined surface renders *the* joined event. Concurrent
membership in several events is a **named future direction** — the backend already composes with it
(bytes are device-partitioned and event-independent, the leave cascade reference-counts a device
across surviving events, the ledger key is event-independent) — so new work SHALL NOT deepen the
single-membership assumption beyond what this spec already states, and a change that must lean on it
names it.

Decision record: `changes/archive/2026-07-06-add-event-join-confirmation` (the gate itself);
`changes/archive/2026-08-05-configure-membership-on-switch` (dissolving the switch into leave-then-join, so
the switching member configures the new membership on this surface).
## Requirements
### Requirement: Joining is gated by an explicit confirmation
The system SHALL NOT provision an event directly from a decoded interactive event link. When the
container's `onOpenUrl` decodes a valid event link **without** the `autoJoin` flag, it SHALL
route the decoded `eventId` into a **pending-join** UI state and provision the event **only** after the
user confirms. Decoding, the details fetch, and the confirmation SHALL all occur before any config is
persisted or any upload producer is enabled. A decode **failure** SHALL surface the existing invalid-
link effect and produce no pending-join state.

This gate is the single decode-and-route point shared by both entry doors (the presentation
container and the iOS `SnapSyncRoot.onOpenUrl`); neither door provisions ahead of the gate for the
interactive case.

Because the AASA matches the link's **path only** (capability `event-link`), a malformed or truncated
event link still opens the app and surfaces the invalid-link effect here, rather than dead-ending
silently in a browser — a visible failure is preferred to an invisible one.

#### Scenario: A scanned interactive link opens the join confirmation, not a provision
- **WHEN** a valid `https://<link domain>/join#…` link without `autoJoin` is decoded and no event is currently configured
- **THEN** the UI reduces to the `JoiningEvent` family for that `eventId`, and no config is saved and no upload producer is enabled until the user confirms

#### Scenario: A malformed link produces no pending join
- **WHEN** a raw URL fails the structural decode — including a link whose fragment is absent or damaged
- **THEN** the invalid-config-link effect is surfaced and no `JoiningEvent` state is entered

### Requirement: The confirmation loads and verifies event details first

On entering the pending-join state, the system SHALL fetch the event's details by `GET /events/:eventId`
before offering the confirm action, showing a **loading** phase ("Loading event details…"). The screen
SHALL open immediately on decode (the `eventId` is local) and the load SHALL gate only the confirm, per
these outcomes:

- **200 with a name** → a **loaded** phase showing the event **name** (a **required, non-null,
  non-blank** value) and carrying the event's **`startsAt`** (both read from the
  `{ eventId, name, createdAt, startsAt }`
  body), with the confirm action (Join) enabled. The loaded `startsAt` SHALL be the cutoff row's
  **default** *and* its **floor** (see capability `photo-selection-policy`). `startsAt` is **always present**
  on a 200 — the backend synthesizes it from `createdAt` for markers written before it existed
  (capability `api-endpoints`) — so the loaded phase SHALL carry it non-null and there is **no**
  seed-from-`createdAt` fallback and **no** seed-to-now fallback;
- **200 without a name, or whose name is blank** → treated as a **failed** phase with a **Retry** action
  — a loaded event SHALL
  always carry a name (the backend enforces name-required on create, trimming and rejecting an empty or
  whitespace-only value, capability `api-endpoints`), so a
  nameless or blank-named 200 is a malformed/transient response, never a loaded phase with a null or
  blank name. This is the **only** guard against a blank name entering the persisted membership: the
  membership type requires the name to be present, not to be non-blank (capability `event-link`), and no
  downstream consumer re-checks it;
- **200 without a parseable `startsAt`** → likewise a **failed** phase with a **Retry** action. A loaded
  event SHALL always carry a `startsAt` (the backend rejects a non-canonical one on create and
  synthesizes one on read), so its absence is a malformed/transient response. It SHALL NOT be defaulted
  to now: `startsAt` is a **floor**, and inventing one on the client would silently lower it;
- **404** → a **blocked** phase ("this invite is invalid or the event no longer exists") with **no**
  confirm action — the details fetch is the event-existence gate;
- **network / non-404 failure** → a **failed** phase with a **Retry** action that re-runs the fetch.

The confirm action SHALL NOT be offered while loading, blocked, or failed. The join surface SHALL hold a
cutoff that is **always present**: the loaded phase's cutoff and the surface's chosen cutoff SHALL both be
non-nullable, so a join with no cutoff is unrepresentable rather than guarded against at confirm time
(capability `photo-selection-policy`). Because the loaded phase carries a non-null name, downstream
provisioning and album titling (capability `event-album`) always have a name to use.

#### Scenario: Details load and enable confirm
- **WHEN** `GET /events/:eventId` returns 200 with the event name and `startsAt`
- **THEN** the join surface shows the name, defaults the cutoff to `startsAt`, and offers the Join confirm action

#### Scenario: A missing or unparseable startsAt is retryable, never defaulted
- **WHEN** `GET /events/:eventId` returns 200 with a name but no `startsAt`, or one that does not parse
- **THEN** the join surface shows a load-failure message and a Retry action, and never enters the loaded
  phase with an invented floor

#### Scenario: The cutoff row is seeded on first composition and never empty
- **WHEN** the join surface first composes in any phase — including a commit-failure phase reached without
  passing through the loaded phase
- **THEN** the cutoff row carries a value (the loaded `startsAt`), and the confirm/retry action passes
  that value on, there being no representable state in which it could pass none

#### Scenario: A nameless 200 is retryable, not a null-named load
- **WHEN** `GET /events/:eventId` returns 200 whose body carries no name
- **THEN** the join surface shows a load-failure message and a Retry action, and never enters the loaded phase with a null name

#### Scenario: A blank-named 200 is retryable, not a blank-named load
- **WHEN** `GET /events/:eventId` returns 200 whose body carries a name that is empty or whitespace-only
- **THEN** the join surface shows a load-failure message and a Retry action, and no membership is ever
  provisioned or refreshed with that blank name

#### Scenario: A missing event blocks the join
- **WHEN** `GET /events/:eventId` returns 404
- **THEN** the join surface shows an invalid/expired-invite message and offers no Join action

#### Scenario: A load failure is retryable
- **WHEN** `GET /events/:eventId` fails on the network or returns a non-404 error
- **THEN** the join surface shows a load-failure message and a Retry action that re-runs the fetch
### Requirement: Confirming enrolls the device, then provisions

The `JoinEvent` use-case SHALL, on confirm, **first** enroll the device by writing a **register-only,
empty** device manifest (no assets) via `PUT /events/:eventId/devices/:deviceId`, and **only on a
successful (201) enrollment** commit the join by saving the config (`eventId`, the loaded name, the
event's **`startsAt`** and **`endsAt`**, the **clamped** capture-date **range** — `minPhotoDate`
floor-clamped and `maxPhotoDate` ceiling-clamped, see below and capability `photo-selection-policy` —
the chosen participation **direction**, **and whether the join opted into an event album —
`saveToAlbum`**, capability `event-album`) and, **when the chosen direction includes upload**
(`Both` or `UploadOnly`), enabling the background-upload producer.

The persisted lower bound (`minPhotoDate`) SHALL be `max(chosen_from, startsAt)` — the event's start
applied as a **floor** — and the persisted upper bound (`maxPhotoDate`) SHALL be
`min(chosen_until, endsAt)` — the event's end applied as a **ceiling** via a new `clampToCeiling`. Both
clamps SHALL be applied in the use-case (not only in the UI) so that **every** entry path is covered —
the interactive confirm, the switch confirm, the retry, and the `autoJoin` path with a deeplink-supplied
range alike. The single `JoinEvent` choke point bounds hostile-link values from **both** sides, so a link
can never widen a membership below the event's start nor above the event's declared end.

When the chosen direction is `DownloadOnly` the producer SHALL **not** be enabled — the device still
enrolls (the empty manifest makes it a member) and still runs the download machinery, but contributes no
photos. Enrollment SHALL be performed for **all** directions, so a download-only device is an enumerable,
notifiable, event-alive member exactly like a contributor; enrollment SHALL make the device a member
immediately — before any photo upload — by making its manifest object present under
`events/<eventId>/devices/`; a contributing device's real asset manifest is written later by the normal
upload cycle (last-write-wins), **scoped by the persisted capture-date range**. The `saveToAlbum` choice
SHALL be persisted for **all** directions (the album is populated by whichever direction(s) sync). A
**failed** enrollment SHALL keep the user on the join surface with an error and a **Retry** action, and
SHALL persist nothing and enable no producer (no half-joined state). The platform effects (the enrollment
write and the producer enable) SHALL be injected so the use-case is pure `commonMain`.

#### Scenario: Confirm clamps the cutoff to the event's start
- **WHEN** the user confirms a join to an event whose `startsAt` is `2026-07-14T18:00:00Z` with a chosen
  from-bound of `2026-07-14T12:00:00Z` and enrollment returns 201
- **THEN** the saved config carries `minPhotoDate = 2026-07-14T18:00:00Z` and `startsAt =
  2026-07-14T18:00:00Z`

#### Scenario: A cutoff above the floor is persisted unchanged
- **WHEN** the user confirms with a chosen from-bound of `2026-07-14T21:00:00Z` against a `startsAt` of
  `2026-07-14T18:00:00Z`
- **THEN** the saved config carries `minPhotoDate = 2026-07-14T21:00:00Z` and `startsAt =
  2026-07-14T18:00:00Z`

#### Scenario: Confirm clamps the upper bound to the event's end
- **WHEN** the user confirms a join to an event whose `endsAt` is `2026-07-21T23:00:00Z` with a chosen
  until-bound of `2026-07-25T00:00:00Z` and enrollment returns 201
- **THEN** the saved config carries `maxPhotoDate = 2026-07-21T23:00:00Z` and `endsAt =
  2026-07-21T23:00:00Z`

#### Scenario: An upper bound below the ceiling is persisted unchanged
- **WHEN** the user confirms with a chosen until-bound of `2026-07-20T12:00:00Z` against an `endsAt` of
  `2026-07-21T23:00:00Z`
- **THEN** the saved config carries `maxPhotoDate = 2026-07-20T12:00:00Z` and `endsAt =
  2026-07-21T23:00:00Z`

#### Scenario: Both clamps live in the use-case, so every path is covered
- **WHEN** a range reaches `JoinEvent` from any entry path — interactive confirm, switch confirm, retry,
  or an `autoJoin` deeplink override
- **THEN** the same `max(chosen_from, startsAt)` floor clamp and `min(chosen_until, endsAt)` ceiling clamp
  are applied before the config is saved

#### Scenario: Confirm persists the album choice
- **WHEN** the user confirms with `saveToAlbum = true` and enrollment returns 201
- **THEN** the saved config carries `saveToAlbum = true` alongside the event id, name, startsAt, endsAt,
  range, and direction

#### Scenario: Confirm enrolls with an empty manifest, then commits with the direction and range
- **WHEN** the user confirms with direction `Both` and `PUT /events/:eventId/devices/:deviceId` with an empty manifest returns 201
- **THEN** the config is saved with the event id, name, `startsAt`, `endsAt`, the clamped range
  (`minPhotoDate`/`maxPhotoDate`), direction `Both`, and the chosen `saveToAlbum`, the upload producer is
  enabled, and the UI reduces to `Joined`

#### Scenario: A download-only confirm enrolls but does not enable the producer
- **WHEN** the user confirms with direction `DownloadOnly` and enrollment returns 201
- **THEN** the config is saved with direction `DownloadOnly`, the upload producer is **not** enabled, and the device is still an enrolled member with an empty manifest

#### Scenario: An upload-only confirm enables the producer
- **WHEN** the user confirms with direction `UploadOnly` and enrollment returns 201
- **THEN** the config is saved with direction `UploadOnly` and the upload producer is enabled

#### Scenario: A failed enrollment does not join
- **WHEN** the user confirms and the enrollment PUT fails
- **THEN** no config is saved and no producer is enabled, and the join surface shows an error with a Retry action

#### Scenario: Enrollment makes the device a member before any upload
- **WHEN** enrollment succeeds against an event with no prior manifest for this device, for any direction
- **THEN** the device's manifest object exists under `events/<eventId>/devices/` so the event enumerates and can notify it, even though no photo bytes have been uploaded yet

### Requirement: Enrollment fires only on a genuine new join

The enrollment PUT (which writes an **empty** manifest) SHALL fire **only** when joining an event the
device is not currently in — which, since a switch's leave clears the config before its join commits, is
always a join taken with **no config**. A re-scan or re-provision of the event the device is **already**
joined to SHALL be a no-op that does **not** re-write the empty manifest, so a real asset manifest
previously written by the upload cycle is never clobbered back to empty. This is consistent with
`event-rejoin-reconciliation`'s no-op on re-provision of the already-joined event.

#### Scenario: Re-scanning the current event does not clobber the manifest
- **WHEN** a deeplink for the event the device is already joined to is decoded
- **THEN** no enrollment PUT is issued and the device's existing manifest is left untouched

#### Scenario: Switching to a different event enrolls
- **WHEN** a switch's leave has cleared the config and the member confirms the join for the new event
- **THEN** the enrollment PUT for the new event is issued as part of that join

### Requirement: Switching events composes leave then join

The system SHALL, when a deeplink for a **different** event is decoded while an event is already
configured, present a leave-style confirmation (of the form "Switch events?") carried as a
`pendingSwitch` on the `Joined` state, and this confirmation SHALL be details-gated like a first join
(it shows the new event's fetched name and blocks on a 404). Its body SHALL name **both** the current
and the new event, and SHALL state **nothing** about the participation that results: the member chooses
the direction, the capture-date range, and the album opt-in on the join surface that follows, so a body
promising a fixed participation would be false. For the same reason the confirmation SHALL NOT render a
shareable count (capability `join-share-count`) — no range has been chosen for it to count.

Its confirm SHALL run the **leave and nothing else** — `LeaveEvent.leave()` as-is, reached through the
same leave command the joined layer's Leave action uses, so in-flight downloads are cancelled and
non-terminal download rows pruned first, and so the switch inherits any backend behavior `leave()` later
gains. The confirm SHALL NOT commit a join.

The pending join SHALL survive the leave. Once the leave has cleared the config, the system SHALL
present the **regular full-screen join surface** for the new event — the same surface a first join
presents, with the Share and Receive switches, the capture-date cutoff choices, the album opt-in, the
live shareable count, and the photo-access explainer where the gate's loaded-phase derivation calls for
it — and the confirm there SHALL commit the join with the member's chosen direction, range, and album
opt-in like any other join. A commit failure SHALL therefore be the join surface's ordinary
commit-failure phase with its Retry, not a switch-specific error.

Because the leave has already run when that surface is presented, **cancelling it SHALL leave the device
in no event**, returning to the create layer per the surface's existing cancel rule; rescanning an invite
rejoins. A leave whose local config clear fails SHALL leave the confirmation presented (the config is
still there, so the state is unchanged), no error surface of its own being raised — matching the joined
layer's Leave action, which reports a failed clear no differently.

#### Scenario: The switch confirmation names both events and promises no participation
- **WHEN** a link for a different event is decoded while joined and its details load
- **THEN** the confirmation names both the current and the new event, states nothing about the resulting
  participation, and renders no shareable count

#### Scenario: Confirming the switch leaves, then opens the regular join surface
- **WHEN** the user confirms the switch confirmation
- **THEN** `LeaveEvent.leave()` runs (after the leave command's download cancellation and non-terminal row
  prune), no join is committed, and once the config is cleared the pending join is presented as the
  full-screen join surface for the new event

#### Scenario: A switch configures the new membership
- **WHEN** the member, on the join surface reached through a switch, turns the Share switch off, opts into
  the album, and confirms
- **THEN** the join commits with direction `DownloadOnly` and `saveToAlbum = true`, exactly as the same
  choices on a first join would

#### Scenario: A join that fails after a successful leave is retryable
- **WHEN** the leave has run and the subsequent join enrollment fails
- **THEN** the device is in no event and the join surface shows its commit-failure phase, whose Retry
  re-runs only the join for the pending target, carrying the choices the member already made

#### Scenario: Cancelling after the leave leaves the device in no event
- **WHEN** the member cancels on the join surface reached through a switch
- **THEN** the pending join is discarded, no device is enrolled, and the UI returns to the create layer
  with no event configured

#### Scenario: A failed local clear keeps the confirmation presented
- **WHEN** the user confirms the switch and the config clear step fails
- **THEN** the event remains configured, the switch confirmation remains presented so the user can confirm
  again, and no separate error surface is raised

#### Scenario: The switch never edits the leave use-case
- **WHEN** the switch path runs
- **THEN** it invokes `LeaveEvent.leave()` as-is (the composition adds no leave behavior of its own)

### Requirement: The join surface is a distinct, extensible UiState family

The capability SHALL own a `JoiningEvent` `UiState` family (carrying the `eventId` and a details phase
of loading / loaded-with-name / not-found / failed) and the full-screen "Join event" screen that
renders it, built from `App*` components (no Material 3 in any `App*` signature). In its **loaded** phase
the surface SHALL present the event name as a hero and the two participation decisions as plain on/off
**switches**, from which the membership's **direction** is **derived** — never chosen by name. There is
**no** direction selector, and the labels `Both` / `Upload only` / `Download only` SHALL NOT appear on
the surface.

The loaded phase SHALL present, in addition to the event name and the confirm (Join) / Cancel actions:

- a **Share switch** ("Share my photos"), **defaulting to on**. When on, the section SHALL state the
  origin exclusions (what the selection policy subtracts, capability `photo-selection-policy`), state the
  resulting cutoff instant **once, in the surface's heaviest type**, and present the capture-date cutoff
  choices (below). When **off**, the section SHALL state that nothing of the member's leaves the phone
  and SHALL **hide the cutoff entirely** (a member sharing nothing has no cutoff to pick).
- a **capture-date cutoff choice** within the Share section, a **three-option selector** — **Now** /
  **Event start** / **Custom** — **defaulting to Event start** (capability `photo-selection-policy`). The
  resulting instant is stated by the Share section's bold value line, so the choice rows SHALL NOT repeat
  it. Selecting **Custom** SHALL open the date+time picker directly; only the picker's confirm commits
  the choice, and cancelling SHALL leave the previous choice and its instant unchanged. The picker SHALL
  enforce the event's start as a **floor** (see capability `photo-selection-policy`). When the event's
  `startsAt` is **in the future**, the **Now** option SHALL be rendered **disabled** (it would clamp to
  the same instant as **Event start**).
- a **Receive switch** ("Receive everyone's photos" — titled to name the **source**, not "save to your
  library", which reads as backing up the member's own photos), **defaulting to on**. When on, the
  section SHALL state that others' photos arrive in the member's library automatically; when off, that
  the member will not receive the event's photos.
- a **standalone album opt-in** ("Create an album"), **defaulting to off** (opt-in), presented as a
  minor section nested under **neither** switch (capability `event-album`). When checked, the event's
  synced photos are gathered into a PhotoKit album titled after the event.

The membership's **direction** SHALL be **derived** from the two switches on confirm — share+receive →
`Both`, share only → `UploadOnly`, receive only → `DownloadOnly` — and crossed to `JoinEvent` together
with the chosen cutoff and the album opt-in (`saveToAlbum`). A switch SHALL NEVER auto-flip the other.
When **both switches are off**, the membership does nothing: the surface SHALL keep that state
representable, **disable Join**, and state the reason directly above the action, rather than silently
enabling either switch. The chosen cutoff SHALL cross to `JoinEvent` only when Share is on; the album
opt-in crosses in every case.

Cancel SHALL discard the pending join and return to the base screen (the create layer when no event is
configured).

#### Scenario: The join screen renders switches, the 3-option cutoff, and the standalone album section
- **WHEN** the `JoiningEvent` state is in its loaded phase for an event that has already started
- **THEN** the surface shows the event name hero, a **Share my photos** switch (on) with the resulting
  cutoff instant in bold and the Now / Event start / Custom choices (default Event start), a **Receive
  everyone's photos** switch (on), a standalone **Create an album** opt-in (off), and Join / Cancel — and
  no `Both` / `Upload only` / `Download only` label appears anywhere

#### Scenario: Direction is derived from the switches on confirm
- **WHEN** the user leaves both switches on and taps Join
- **THEN** the confirm crosses direction `Both`; **WHEN** only Share is on, it crosses `UploadOnly`;
  **WHEN** only Receive is on, it crosses `DownloadOnly`

#### Scenario: Both switches off is representable and blocks Join with a stated reason
- **WHEN** the user turns both the Share and the Receive switch off
- **THEN** the state is representable, Join is **disabled**, and a reason is shown above the action, and
  neither switch is auto-flipped to re-enable Join

#### Scenario: Share off hides the cutoff
- **WHEN** the user turns the Share switch off
- **THEN** the cutoff choices and the resulting-instant line are hidden, and the section states that
  nothing of the member's leaves the phone

#### Scenario: Custom opens the picker directly and only OK commits
- **WHEN** the user selects the **Custom** cutoff option
- **THEN** the date+time picker opens immediately; confirming it commits the picked (floored) instant as
  the cutoff, and cancelling it restores the previously-selected option and instant

#### Scenario: Now is disabled before the event starts
- **WHEN** the loaded event's `startsAt` is in the future
- **THEN** the **Now** option is rendered disabled, **Event start** remains selected, and the bold value
  shows the event's start instant

#### Scenario: The chosen cutoff, derived direction, and album choice cross on confirm
- **WHEN** the user adjusts the switches and cutoff, opts into the album, and taps Join
- **THEN** the derived direction, the chosen cutoff, and `saveToAlbum = true` are passed through the
  confirm intent into `JoinEvent`

#### Scenario: Cancel discards the pending join
- **WHEN** the user cancels on the join surface with no event configured
- **THEN** the pending join is discarded and the UI returns to the create layer

### Requirement: The autoJoin flag auto-confirms the gate
When a decoded event link carries `autoJoin = true`, the system SHALL run the **same** gate — decode,
fetch details, and (when already joined to a different event) leave-then-join — but SHALL **auto-fire**
the confirm once details reach the loaded phase, rather than waiting for a user tap.

The `autoJoin` reading SHALL be reached **only after** the delivery has been established as one the gate
has not already acted on (capability `event-link`): a repeat of a link whose pending join is open, or
whose event is already the joined one, is ignored **whatever `autoJoin` says**. That ordering is the
whole of the protection, because this is the one path with no confirmation surface to absorb a second
delivery — everything else asks for a tap, and a tap happens once however many times the link arrived.
The platform does deliver the same link more than once (measured: twice on an iOS 18.7.9 cold launch
~130 ms apart, and twice on iOS 26.6 both while running and cold), so before that ordering an
`autoJoin` link provisioned once per delivery. The auto-fired
confirm SHALL use the **default** cutoff (the loaded event's **`startsAt`** — never an absent cutoff,
capability `photo-selection-policy`) unless the event link carries an explicit dev/test cutoff (see capability
`event-link`), in which case that value SHALL be used **subject to the floor**: the persisted cutoff
is `max(override, startsAt)`, so an event link's cutoff can raise a membership above the event's start but
never lower it below. SHALL use the **default** direction **Both** unless the event link carries an explicit
dev/test `direction` override (`both`/`upload`/`download`, capability `event-link`), in which case
that direction SHALL be used; and SHALL use the **default** album choice **off** unless the event link
carries an explicit dev/test `saveToAlbum` override (capability `event-link`), in which case that
value SHALL be used. This keeps the headless developer launch path working (it cannot tap a confirm
control) and lets it force a direction and album choice on device; to exercise date filtering against a
distant-past library, the developer SHALL create the event with an early `startsAt` (the create screen's
picker is unbounded) rather than relying on an unclamped override. Because the auto path has no
interactive surface, a load failure (404 or network) or a failed enrollment SHALL **abort and log** rather
than parking on a retryable error state.

#### Scenario: autoJoin provisions without a tap, using startsAt as the cutoff, Both direction, and album off
- **WHEN** an event link with `autoJoin = true` and no explicit cutoff, direction, or album override is decoded and its details load successfully
- **THEN** the confirm is auto-fired with the cutoff defaulting to the loaded `startsAt`, the direction defaulting to `Both`, and `saveToAlbum` defaulting to off

#### Scenario: autoJoin honors an explicit dev/test cutoff above the floor
- **WHEN** an event link with `autoJoin = true` carries an explicit dev/test cutoff **later** than the event's `startsAt` and its details load
- **THEN** the auto-fired confirm provisions with that explicit cutoff

#### Scenario: autoJoin clamps an explicit dev/test cutoff below the floor
- **WHEN** an event link with `autoJoin = true` carries an explicit dev/test cutoff **earlier** than the event's `startsAt`
- **THEN** the auto-fired confirm provisions with `startsAt`, so a hostile QR cannot auto-join at a wider scope than the event itself allows

#### Scenario: autoJoin honors an explicit dev/test direction override
- **WHEN** an event link with `autoJoin = true` carries `direction = download` and its details load
- **THEN** the auto-fired confirm provisions with direction `DownloadOnly` (the producer is not enabled)

#### Scenario: autoJoin honors an explicit dev/test saveToAlbum override
- **WHEN** an event link with `autoJoin = true` carries `saveToAlbum = true` and its details load
- **THEN** the auto-fired confirm provisions with `saveToAlbum = true`, so a headless launch exercises album placement

#### Scenario: autoJoin still leaves an existing event
- **WHEN** an event link with `autoJoin = true` for a different event is decoded while already joined
- **THEN** the existing event is left first and the new event is joined, without any confirmation UI

#### Scenario: autoJoin aborts on failure instead of showing Retry
- **WHEN** the details fetch returns 404 (or the enrollment fails) on an `autoJoin` launch
- **THEN** the flow aborts and logs, presenting no retryable error surface

#### Scenario: A repeated autoJoin link provisions once
- **WHEN** the same event link carrying `autoJoin = true` is delivered twice through two different
  platform delivery hooks
- **THEN** the device provisions exactly once, the second delivery performing no enrollment, and the
  ignored repeat is recorded (capability `event-link`)

### Requirement: The join gate explains photo access before the first system dialog

The join gate SHALL show a full-screen **photo-access explainer** before the photo-library permission
dialog is ever raised, and the system dialog SHALL be reachable from that surface **only** via its
confirm action.

The explainer SHALL be a phase of the `JoiningEvent` family (not a dialog, not a separate `UiState`),
chosen by the gate's **loaded-phase derivation**: the single rule that decides, for loaded event details,
whether the gate presents the **explain-access** phase or the loaded/confirm phase. The derivation SHALL
select the explain-access phase exactly when **both** hold:

- **no event is currently configured** (so a switch's confirmation, presented over the joined layer while
  the old event is still configured, is never the explainer), and
- photo permission is `NOT_DETERMINED`.

The derivation SHALL run at **every** point the gate resolves to a loaded phase, so that no entry path
can reach the confirm surface without having been offered the explainer. There are two such points: when
the details fetch resolves, and when a switch's leave clears the config (capability `join-event`,
"Switching events composes leave then join"), the second re-deriving from the details the first already
loaded rather than re-fetching them. The derivation SHALL run only after the config is confirmed cleared,
so a leave whose clear failed leaves the phase untouched.

Permission SHALL be read as a **snapshot at the moment the phase is chosen**, not observed — the phase
advances only by user action, so a permission change while the explainer is on screen SHALL NOT move it.

`GRANTED` and `DENIED` SHALL both go straight to the loaded/confirm phase. `DENIED` is excluded because
iOS raises the photo dialog at most once: from `DENIED` a request is a silent no-op, so an explainer
promising a dialog that cannot appear would be false. A `DENIED` joiner instead meets the joined layer's
existing `NeedsAccess` Settings affordance (capability `sync-status-screen`).

The explainer SHALL **name the event** it invites the user to, showing the same event hero rendered
across the Loading → explain-access → loaded/confirm → committing phases, so the event's identity never
jumps between phases. (This reverses the earlier decision to keep the explainer deliberately anonymous:
naming the event is what makes the surface continuous with the confirm phase it precedes.) The three
consent facts SHALL be presented as card rows beneath the hero, share-first: that photos the user takes
are shared automatically, that full library access is genuinely needed for **both** halves (sharing and
receiving), and that only photos after the capture-date the user chooses next are shared.

The explainer's **confirm** ("I understand") SHALL call `PhotoAccessRequester.request()` and advance to
the loaded/confirm phase in the same action. Because `request()` returns nothing and cannot suspend
(capability `permission-gate`), the phase SHALL advance immediately rather than await an outcome; the
system dialog lands over the confirm surface. The explainer's **cancel** SHALL discard the pending join
exactly as cancel does in every other phase — no enrollment, no config, no producer.

The explainer SHALL NOT auto-request: the system dialog fires only from the confirm action (CTA-only
priming). No other join-gate phase SHALL raise it.

The copy MUST NOT describe the app's function as "backing up" the user's library (consistent with
`event-creation-ui`), and MUST NOT claim a scope wider than the membership can produce.

Because a created event routes into the **same** gate (`onEventCreated` → the pending-join state), the
explainer SHALL cover a first-time creator and a first-time scanner identically, with no create-path
surface of its own.

#### Scenario: A first join with permission never asked explains before the dialog, naming the event
- **WHEN** the details fetch resolves to a loaded event, no event is configured, and photo permission is `NOT_DETERMINED`
- **THEN** the gate enters the explain-access phase showing the **named** event hero and the three consent
  facts, no permission request has been made, and the loaded/confirm surface is not yet shown

#### Scenario: The confirm requests permission and advances to the confirm surface
- **WHEN** the user takes the explainer's confirm action
- **THEN** `PhotoAccessRequester.request()` is called exactly once and the gate advances to the loaded/confirm phase carrying the same event name and default cutoff

#### Scenario: Cancelling the explainer abandons the join
- **WHEN** the user cancels on the explain-access phase
- **THEN** the pending join is discarded, no device is enrolled, no config is saved, no upload producer is enabled, and the UI returns to the create layer

#### Scenario: Already-granted access skips the explainer
- **WHEN** the details fetch resolves to a loaded event and photo permission is `GRANTED`
- **THEN** the gate enters the loaded/confirm phase directly and no explainer is shown

#### Scenario: Previously-denied access skips the explainer
- **WHEN** the details fetch resolves to a loaded event and photo permission is `DENIED`
- **THEN** the gate enters the loaded/confirm phase directly, no explainer is shown, and no permission request is made

#### Scenario: A switch does not explain before its leave
- **WHEN** a deeplink for a different event is decoded while an event is already configured, and photo permission is `NOT_DETERMINED`
- **THEN** the derivation selects the loaded/confirm phase, so the switch confirmation is presented over the joined layer and the explain-access phase is not entered

#### Scenario: A switch explains after its leave
- **WHEN** the user confirms that switch confirmation and the leave clears the config, photo permission still being `NOT_DETERMINED`
- **THEN** the derivation runs again over the already-loaded details and the gate enters the explain-access phase, naming the new event, before its confirm surface

#### Scenario: A created event's first join explains too
- **WHEN** a freshly created event is routed into the pending-join gate, no event is configured, and photo permission is `NOT_DETERMINED`
- **THEN** the same explain-access phase is entered, naming the just-created event, with no create-specific surface

#### Scenario: The explainer never auto-requests
- **WHEN** the explain-access phase is rendered
- **THEN** no permission request is made until the user takes the confirm action

### Requirement: The cutoff row derives from the phase; it is never seeded at mount

The join surface's capture-date choice SHALL be a **`[from, until]` range** whose default is the **full
event window** `[startsAt, endsAt]` (narrow, never widen — admits on doubt). Both handles SHALL show
values derived from the **event's `startsAt` and `endsAt`**, read from whichever phase currently carries
them — **not** from values captured when the surface first rendered.

The **From** handle offers presets **Event start · Now · Custom** (defaulting to **Event start**); the
**Until** handle offers presets **Event end · Custom** (defaulting to **Event end**). The **Now**
From-preset SHALL be offered **only while** `startsAt <= now <= endsAt` — before the event starts it would
clamp to the same instant as **Event start** (and is disabled), and after the event ends it would fall
outside the window (and is not offered).

The surface is mounted at the **loading** phase (the pending join is created before the details fetch is
issued), so no start or end date exists at first render; the explain-access phase, when shown, carries them
ahead of the confirm phase. A value captured once at first render therefore falls through to *now* and is
never revisited, silently defaulting every real join to now — the bug this requirement exists to forbid.

The surface SHALL therefore remember only the user's **choices** — the selected From/Until presets and,
for a Custom bound, the picked local wall-clock value — never a **default instant** seeded at mount, and
SHALL derive the resulting instants from the phase on every composition. A Custom **from** SHALL be coerced
up to the floor (the event's start) and a Custom **until** coerced down to the ceiling (the event's end) on
every composition, so the surface never displays or commits a bound outside `[startsAt, endsAt]`. This
makes the staleness above unrepresentable rather than merely guarded: there is no default captured, so
there is nothing to go stale, and no re-seed can discard a choice the user made.

The **commit phases** (committing, commit-failed) SHALL carry `startsAt` **and `endsAt`** for this reason.
A retry commits **without** passing back through the loaded phase, so a surface that could read the window
only from the loaded phase would derive a retry's range from *now* — silently discarding the user's
selection at the one moment they are already recovering from a failure. A surface entered directly at a
phase that carries no window SHALL fall back to a *now* from-bound and an unbounded until — the safe
direction, since a narrow range shares too few photos (which a re-join fixes) whereas the opposite error
cannot be undone.

#### Scenario: The range shows the full event window across the real phase sequence
- **WHEN** the join surface advances loading → explain-access → confirm, for an event with a `startsAt`
  and `endsAt`
- **THEN** the From value shows the event's start and the Until value shows the event's end, not the
  current time

#### Scenario: A user's chosen range survives a failed commit
- **WHEN** the user picks From and Until options (including Custom instants), confirms, the commit fails,
  and they retry
- **THEN** the retry carries the range their choices resolve to against the event's window — not now, and
  not values re-derived from a phase that lost them

#### Scenario: Now is offered only while the event is in its window
- **WHEN** the loaded event's `startsAt` is in the future, or its `endsAt` is in the past
- **THEN** the **Now** From-preset is not selectable, and the default range remains the full
  `[startsAt, endsAt]` window

#### Scenario: A surface with no window falls back to now
- **WHEN** the join surface renders a phase that carries no `startsAt`/`endsAt`, and none was ever loaded
- **THEN** the From bound is the current time and the Until bound is unbounded, never absent and never
  whole-library below the floor

### Requirement: The persisted membership carries the event's start date

The persisted membership state (`EventConfig`) SHALL carry the event's **`startsAt`**, its **`endsAt`**,
and its **`deletesAt`** alongside the capture-date range, in the canonical cutoff shape. On a
**successful details load**, all three are **required and non-invented** — read from the loaded event's
`{ startsAt, endsAt, deletesAt }` body, never defaulted to now or to any client-side guess. `startsAt` is
what the not-started state compares against (capability `sync-status-screen`) and the floor on the
range's lower bound; `endsAt` is what the "Event ended" marker compares against (capability
`sync-status-screen`) and the ceiling on the range's upper bound; `deletesAt` is the second witness the
self-leave requires (capability `leave-event`). All three make the event's bounds auditable on the
device.

A config persisted **before** `startsAt` existed SHALL decode with `startsAt` **defaulted to that
config's `minPhotoDate`**. It SHALL NOT fail to decode.

A config persisted **before** `endsAt` existed SHALL decode with `endsAt` **absent** rather than failing;
reconcile backfills it from the event's details (capability `event-rejoin-reconciliation`). An absent
`endsAt` is the **event's** fact going momentarily unknown — never an unbounded upload ceiling: the
membership's own `maxPhotoDate` is required and concrete on every config that decodes at all (see "The
persisted membership's capture-date ceiling is required"), so the capture-date range stays bounded
throughout. Until it is backfilled, consumers SHALL treat an absent `endsAt` as an **"Event ended" marker
not yet reached** (capability `sync-status-screen`).

A config persisted **before** `deletesAt` existed SHALL decode with `deletesAt` **absent** rather than
failing; reconcile backfills it (capability `event-rejoin-reconciliation`). An absent `deletesAt` SHALL
be treated as **never reached**, so the self-leave cannot fire on a membership that has not yet learned
its deadline — the safe direction, matching `endsAt`'s absent default.

Defaulting `startsAt` rather than failing is deliberate and is **not** symmetric with `minPhotoDate`'s own
no-default rule. `minPhotoDate`'s harshness buys protection against uploading a whole camera roll;
`startsAt`'s would buy a status line. And the blast radius is severe: `EventConfig` is the **only**
place the `eventId` is held, and the invite QR is derived from it — so a decode failure destroys the
member's event id **and** their QR, with nothing in the app to surface either back. A host who is the
only member yet would be permanently locked out of their own event, its uploaded photos stranded.

`minPhotoDate` SHALL be the `startsAt` default because it is the only value **guaranteed** consistent with
the floor invariant (`minPhotoDate >= startsAt`, satisfied here with equality). It also lands the
not-started state correctly by construction: a legacy member joined an event that had already begun, so
their cutoff was at or before "now" when they picked it, so the derived `startsAt` is never in the future
and the not-started state never appears for them.

#### Scenario: A fresh join persists startsAt, endsAt, and deletesAt from the loaded details
- **WHEN** a join confirms against a loaded event carrying `startsAt`, `endsAt`, and `deletesAt`
- **THEN** the persisted `EventConfig` carries all three as non-null canonical strings, none invented nor
  defaulted to now

#### Scenario: A legacy config decodes with startsAt defaulted and no event end or deadline
- **WHEN** a config persisted before this change — carrying `eventId`, `name`, `minPhotoDate`,
  `maxPhotoDate`, `direction`, `saveToAlbum` and **no** `startsAt`, `endsAt`, or `deletesAt` — is decoded
- **THEN** it decodes successfully with `startsAt == minPhotoDate`, `endsAt` absent (pending reconcile
  backfill, the "Event ended" marker not yet reached), and `deletesAt` absent (a deadline treated as never
  reached), and the member keeps their event, their QR, and their capture-date range

#### Scenario: An absent deadline never fires the self-leave
- **WHEN** a membership whose `deletesAt` has not been backfilled observes a definitive `NotFound`
- **THEN** the membership is left intact, because the deadline witness cannot be satisfied

#### Scenario: A config with no cutoff still fails to decode
- **WHEN** a config carrying no `minPhotoDate` is decoded
- **THEN** it still fails and reads as *no config*, the cutoff's no-default rule being untouched by this
  change

#### Scenario: Every consumer reads a non-null startsAt
- **WHEN** the persisted membership is read, by the app process or the upload extension process
- **THEN** `startsAt` is a non-null canonical cutoff string, with no nullable branch at any consumer, and
  `endsAt` and `deletesAt` are each either a canonical cutoff string or the absent value pending backfill

### Requirement: The persisted membership's capture-date ceiling is required

A persisted membership (`EventConfig`) SHALL carry a **concrete** capture-date upper bound
(`maxPhotoDate`); it SHALL NOT be absent or unbounded. On a successful details load the ceiling is
`min(chosen, endsAt)` and the event always serves `endsAt`, so a fresh join always yields one. A config
lacking the ceiling SHALL fail to decode (returning the device to the unjoined state) rather than decode to
an unbounded ceiling.

This **reverses** the prior allowance (a pre-ceiling config decoding to an unbounded ceiling pending
reconcile backfill). It is safe only because `decouple-event-window-from-lifetime` ships first and
reconciles every device's ceiling before this change's strict decode is deployed (see this change's
migration gate); it is a deliberate, recorded reversal of `EventConfig`'s decode-safety allowance,
acceptable for the controlled installed base.

#### Scenario: A config without a ceiling does not decode

- **WHEN** an `EventConfig` lacking `maxPhotoDate` is decoded by this change's build
- **THEN** it fails to decode (read as no config); it does not decode to an unbounded ceiling

### Requirement: One details client

The app SHALL have exactly one `GET /events/:eventId` client: the `EventDirectory` port (`:domain`
`ports/`) and its `HttpEventDirectory` implementation in `:adapter:generic:app`. Every consumer of an
event's details SHALL read through it — the join gate's details fetch AND the membership refresh (the
foreground re-fetch, capability `event-link`). There SHALL be no second, looser
event-fetch client: a duplicate client is how producer and consumer semantics drift (the deleted
`EventMetadataSource` accepted responses the gate rejects).

**Folding a fetch result into the persisted membership SHALL be one membership-feature rule** seated in
`:domain` `feature/membership`, named for that need rather than for any single field it touches. The rule
SHALL answer a **sealed outcome** with exactly three arms:

- **refreshed** — the fetch resolved and the event is still the configured one: the rule persists a
  changed name, and backfills any absent window and retention fields, as **one whole-config save** with
  only those fields replaced, never clobbering the persisted cutoff (capability
  `photo-selection-policy`);
- **inconclusive** — the fetch did not resolve definitively (offline, transport failure, non-404 status,
  unparseable body), or it resolved for an event that is no longer the configured one: **nothing is
  persisted and nothing is torn down**, and the last-known values are left unchanged;
- **absent** — the fetch resolved to a definitive `NotFound` **and** the membership's own persisted
  deadline has passed: the membership is torn down (capability `leave-event`).

The name arm SHALL be retained as **convergence on the served name**, not as a fill for a membership that
lacks one: a membership always carries a name (capability `event-link`), so this arm exists so that a
persisted name can still be repaired toward the backend's value, and it is the only path by which a
diverged name could ever be corrected.

The teardown on the **absent** arm SHALL be performed by the rule itself, not by a branch in the calling
flow. Attaching the consequence to the verdict is what makes every trigger reach the same outcome by
construction rather than by separate call sites agreeing. It is also what the flow transcriber's closed
grammar requires: the fetch is network I/O and therefore sits inside an escaping launch, where no `when`
is transcribable and an untranscribable flow fails generation (capability `architecture-diagrams`). This
requirement SHALL hold independently of how many triggers call the rule: it is a property of where the
consequence is attached, not of agreement between call sites.

The distinction between *inconclusive* and *absent* SHALL be preserved end to end. The port already
separates `NotFound` from `Failed`; the effect the flows are given SHALL carry that sealed outcome rather
than collapsing it, because collapsing them is what makes "could not tell" and "definitively gone"
indistinguishable at the only place the difference matters.

The fetch itself SHALL remain coordination in the `flow/` triggers — **`Foreground`, unconditionally, and
no other** — through a `compose/`-built `EventDirectory` effect over this one
client. The trigger SHALL do no more than hand the fetched result to the rule. The `Provision` trigger
SHALL NOT fetch: every provision route (interactive join, `autoJoin`, switch, headless create) has just
loaded or minted the event's details, so a fetch there is redundant by construction, and the membership
it would refresh is current already.

#### Scenario: The membership refresh reads through the details client

- **WHEN** the foreground refresh fetches the configured event
- **THEN** it calls the same `EventDirectory` the join gate uses, and updates the stored membership only
  from a resolved outcome

#### Scenario: Provisioning fetches no details

- **WHEN** an event is provisioned by any route — an interactive join, an `autoJoin` link, a switch, or a
  headless create
- **THEN** no `GET /events/:eventId` is issued by the provision trigger, and the membership is persisted
  from the details the route already carries

#### Scenario: An inconclusive outcome changes nothing

- **WHEN** the details fetch fails on the network, times out, returns a non-404 error, or returns an
  unparseable body during a refresh
- **THEN** the persisted config keeps every last-known value, nothing is torn down, and syncing is
  unaffected

#### Scenario: A stale fetch after a switch stores nothing

- **WHEN** a fetch resolves for an event that is no longer the configured one
- **THEN** the rule answers inconclusive and stores nothing (the departed membership is not resurrected)

#### Scenario: The sealed outcome reaches the flow intact

- **WHEN** the details fetch resolves to `NotFound`
- **THEN** the effect the trigger receives distinguishes that from a failed fetch, rather than presenting
  both as the same no-result

#### Scenario: The consequence is attached to the verdict, not to the caller

- **WHEN** the rule answers the absent verdict
- **THEN** the teardown is performed by the rule itself, so no calling flow contains a branch that could
  reach a different consequence from the same verdict
### Requirement: The join surface shows a live count of the photos that will be shared

The join surface's **loaded** phase SHALL present, within the Share section and beneath the Share switch,
a **live count** of how many of the device's own photos the currently-chosen cutoff would share to the
event, sourced from the shareable-count read-model (capability `join-share-count`). The count SHALL be
recomputed whenever the chosen cutoff changes (Now / Event start / Custom, or a Custom instant) and
whenever the Share switch is toggled, so the number always reflects the pending choice.

- When the count is a positive number `XX`, the row SHALL read `XX photos from your gallery will be
  shared`.
- When the count is **zero** — the legitimate result of the **Now** cutoff, or of no in-scope photos —
  the row SHALL read `0 photos from your gallery will be shared` together with a forward gloss `New photos
  you take will be shared as you go`, so a true zero does not read as a failure.
- While the count is (re)computing, the row SHALL show a brief `counting…` state in place of the number,
  resolving to the number when the computation settles.
- When the Share switch is **off**, the row SHALL be hidden (the Share section already hides the cutoff),
  since a member sharing nothing has no count.
- When the photo-access grant does not permit a count (`DENIED`, or unresolved `NOT_DETERMINED` —
  capability `join-share-count`), the row SHALL be omitted rather than showing a spinner that cannot
  resolve.

The count is rendered on this surface alone. A switch reaches this same surface after its leave, so it
inherits the count against the range the member actually chooses; the switch confirmation that precedes
the leave renders none.

The count is **decision-support on a decision surface** and does not change what confirming does: it
informs the cutoff choice, and confirming still crosses the chosen cutoff and derived direction to
`JoinEvent` unchanged.

#### Scenario: The loaded phase shows the count under the Share switch
- **WHEN** the `JoiningEvent` loaded phase renders with the Share switch on and the cutoff at Event start,
  and photo access permits a count
- **THEN** a row beneath the Share switch reads `XX photos from your gallery will be shared` for the
  count the chosen cutoff admits

#### Scenario: Changing the cutoff updates the count
- **WHEN** the user changes the cutoff choice from Event start to a Custom date reaching further back
- **THEN** the count recomputes and the row shows the new number (briefly showing `counting…` while it
  recomputes)

#### Scenario: A zero count carries the forward gloss
- **WHEN** the chosen cutoff is Now (or no photo is in scope), so the count is zero
- **THEN** the row reads `0 photos from your gallery will be shared` and `New photos you take will be
  shared as you go`

#### Scenario: Turning Share off hides the count
- **WHEN** the user turns the Share switch off
- **THEN** the count row is hidden along with the cutoff choices

#### Scenario: Without a usable photo grant no count is shown
- **WHEN** the loaded phase renders while photo access is `DENIED` or still unresolved
- **THEN** no count row is shown, and no library read is attempted for it

#### Scenario: A switch's count arrives on the join surface, not the confirmation
- **WHEN** a switch confirmation is presented for a different event and photo access permits a count
- **THEN** the confirmation renders no count, and the join surface reached after its leave renders the row
  for the member's chosen range like any other join

### Requirement: The join surface states how long the event's photos are kept

The join surface's **loaded** phase SHALL carry the event's **`deletesAt`**, read from the details body
(capability `api-endpoints`), and SHALL present, before the confirm action, both:

- the **deadline** — the loaded `deletesAt`, rendered as a date, stating when the event's shared photos
  are removed; and
- a **fixed ceiling statement** — that shared photos are deleted within the maximum retention period
  (30 days) of the event's start.

The deadline SHALL be **server-supplied**, never computed on the device. The client SHALL NOT hold a copy
of the retention constant or of the anchor rule: duplicating either would let a create screen or a join
gate confidently promise a date the backend will not honour, and the drift would be silent. Serving the
derived value is what keeps one authority.

This surface is the **only** place the **retention** is stated in the app. The create screen SHALL be
unchanged — the creator reaches this same gate immediately after minting (`CreateEvent` routes the minted
event into the gate a scanned QR uses), so one line serves both the host and every guest. (The joined
layer's "Event ended" marker is a separate statement about the capture window, not retention; its layout
is capability `sync-status-screen`.)

The stated deadline SHALL be the **ceiling**, never a conditional. An event may in fact be deleted
earlier — the scheduled cleanup reclaims an emptied event (capability `scheduled-cleanup`) — but that
reclamation depends on every member's leave notify reaching the backend and is therefore not assured, so
it SHALL NOT be presented as a promise or a qualification on the date.

#### Scenario: The loaded phase states the deadline and the ceiling

- **WHEN** `GET /events/:eventId` returns 200 carrying a `deletesAt`
- **THEN** the join surface shows that date as when the event's shared photos are removed, alongside the
  fixed statement that shared photos are deleted within 30 days of the event's start, before the confirm
  action

#### Scenario: The creator sees the same statement

- **WHEN** a host completes the create form and the minted event is routed into the join gate
- **THEN** the gate shows the same deadline and ceiling statement it shows any scanning guest

#### Scenario: The deadline is not derived on the device

- **WHEN** the join surface renders the deadline
- **THEN** it renders the value the details response supplied, and no client-side constant or anchor
  formula participates in producing it

#### Scenario: Early reclamation is not stated as a condition

- **WHEN** the join surface presents the deadline
- **THEN** it presents it unconditionally, with no clause making the date contingent on other members
  leaving

### Requirement: The join gate never rests in a phase that offers no action

The join gate SHALL NOT come to rest in an in-flight phase. Whenever the work such a phase represents ends,
for **any** reason including a throwable escaping it, the gate SHALL move to a phase that offers the member
an action.

The in-flight phases are `Loading` and `Committing`: by design they pin no button, so a member looking at
either has nothing to tap. Every other phase — `ExplainAccess`, `Ready`, `NotFound`, `LoadFailed`,
`CommitFailed` — offers at least a Cancel.

Without this, a failure during a details load or a commit leaves a dead-end surface for the life of the
process — a full-screen spinner with no control when no event is configured, and an invisible pending join
when one is — recoverable only by force-quitting the app.

Where a commit fails, the phase the gate lands on SHALL be decided by **whether the membership was
persisted**, because a commit writes the config partway through its work and everything after that point is
follow-up the next foreground repeats:

- the config now names the event being joined → the join **landed**; the pending join SHALL be discarded, so
  the member sees the joined screen, which is the truth.
- otherwise → the join did **not** land; the phase SHALL become the retryable commit-failure phase, whose
  Retry re-runs the join.

A failure during the details load SHALL become the retryable load-failure phase, which is what a details
source reporting a transient failure already produces — so a throwing source and a reporting one converge on
the same surface.

#### Scenario: A commit that fails after the membership was persisted lands on the joined screen
- **WHEN** the commit for the pending event fails partway through, after the membership has been persisted
- **THEN** the pending join is discarded and the screen is the joined layer for that event, with no spinner and no dialog left behind

#### Scenario: A commit that fails before the membership was persisted stays retryable
- **WHEN** the commit for the pending event fails before the membership has been persisted
- **THEN** the gate shows the commit-failure phase, whose Retry re-runs the join with the choices already made

#### Scenario: A details load that fails abnormally is retryable
- **WHEN** the details load for the pending event fails by throwing rather than by reporting a failure
- **THEN** the gate shows the load-failure phase with its Retry, exactly as a reported transient failure does

#### Scenario: No in-flight phase outlives its work
- **WHEN** the work behind a `Loading` or `Committing` phase has ended, however it ended
- **THEN** the gate is in a phase that offers the member at least one action
