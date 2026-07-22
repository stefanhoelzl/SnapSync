# join-event Specification

## MODIFIED Requirements

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

The persisted membership state (`EventConfig`) SHALL carry the event's **`startsAt`** and its
**`endsAt`** alongside the capture-date range, in the canonical cutoff shape. On a **successful details
load**, both are **required and non-invented** — read from the loaded event's `{ startsAt, endsAt }` body,
never defaulted to now or to any client-side guess. `startsAt` is what the not-started state compares
against (capability `sync-status-screen`) and the floor on the range's lower bound; `endsAt` is what the
"Event ended" marker compares against (capability `sync-status-screen`) and the ceiling on the range's
upper bound. Both make the window auditable on the device.

A config persisted **before** `startsAt` existed SHALL decode with `startsAt` **defaulted to that
config's `minPhotoDate`**. It SHALL NOT fail to decode.

A config persisted **before** `endsAt` existed SHALL decode with `endsAt` **absent (treated as
unbounded)** rather than failing, so nothing is silently dropped mid-upgrade; reconcile backfills it to the
event's end (capability `event-rejoin-reconciliation`). Every consumer SHALL treat an absent `endsAt` as
**no ceiling** until it is backfilled.

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

#### Scenario: A fresh join persists both startsAt and endsAt from the loaded details
- **WHEN** a join confirms against a loaded event carrying `startsAt` and `endsAt`
- **THEN** the persisted `EventConfig` carries both as non-null canonical strings, neither invented nor
  defaulted to now

#### Scenario: A legacy config decodes with startsAt defaulted to its cutoff and no ceiling
- **WHEN** a config persisted before this change — carrying `eventId`, `name`, `minPhotoDate`,
  `direction`, `saveToAlbum` and **no** `startsAt` or `endsAt` — is decoded
- **THEN** it decodes successfully with `startsAt == minPhotoDate` and `endsAt` absent (an unbounded
  ceiling pending reconcile backfill), and the member keeps their event, their QR, and their cutoff

#### Scenario: A config with no cutoff still fails to decode
- **WHEN** a config carrying no `minPhotoDate` is decoded
- **THEN** it still fails and reads as *no config*, the cutoff's no-default rule being untouched by this
  change

#### Scenario: Every consumer reads a non-null startsAt
- **WHEN** the persisted membership is read, by the app process or the upload extension process
- **THEN** `startsAt` is a non-null canonical cutoff string, with no nullable branch at any consumer, and
  `endsAt` is either a canonical cutoff string or the absent (unbounded) value pending backfill
