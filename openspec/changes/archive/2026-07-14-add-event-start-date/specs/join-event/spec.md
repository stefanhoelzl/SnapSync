## ADDED Requirements

### Requirement: The persisted membership carries the event's start date

The persisted membership state (`EventConfig`) SHALL carry the event's **`startsAt`** alongside the
cutoff, as a **required, non-null** `String` in the canonical cutoff shape. It is what the not-started
state compares against (capability `sync-status-screen`) and what makes the floor auditable on the
device.

A config persisted **before** `startsAt` existed SHALL decode with `startsAt` **defaulted to that
config's `minPhotoDate`**. It SHALL NOT fail to decode.

Defaulting rather than failing is deliberate and is **not** symmetric with `minPhotoDate`'s own
no-default rule. `minPhotoDate`'s harshness buys protection against uploading a whole camera roll;
`startsAt`'s would buy a status line. And the blast radius is severe: `EventConfig` is the **only**
place the `eventId` is held, and the invite QR is derived from it — so a decode failure destroys the
member's event id **and** their QR, with nothing in the app to surface either back. A host who is the
only member yet would be permanently locked out of their own event, its uploaded photos stranded.

`minPhotoDate` SHALL be the default because it is the only value **guaranteed** consistent with the
floor invariant (`minPhotoDate >= startsAt`, satisfied here with equality). It also lands the
not-started state correctly by construction: a legacy member joined an event that had already begun, so
their cutoff was at or before "now" when they picked it, so the derived `startsAt` is never in the future
and the not-started state never appears for them.

#### Scenario: A legacy config decodes with startsAt defaulted to its cutoff
- **WHEN** a config persisted before this change — carrying `eventId`, `name`, `minPhotoDate`,
  `direction`, `saveToAlbum` and **no** `startsAt` — is decoded
- **THEN** it decodes successfully with `startsAt == minPhotoDate`, and the member keeps their event,
  their QR, and their cutoff

#### Scenario: A config with no cutoff still fails to decode
- **WHEN** a config carrying no `minPhotoDate` is decoded
- **THEN** it still fails and reads as *no config*, the cutoff's no-default rule being untouched by this
  change

#### Scenario: Every consumer reads a non-null startsAt
- **WHEN** the persisted membership is read, by the app process or the upload extension process
- **THEN** `startsAt` is a non-null canonical cutoff string, with no nullable branch at any consumer

## MODIFIED Requirements

### Requirement: The confirmation loads and verifies event details first

On entering the pending-join state, the system SHALL fetch the event's details by `GET /events/:eventId`
before offering the confirm action, showing a **loading** phase ("Loading event details…"). The screen
SHALL open immediately on decode (the `eventId` is local) and the load SHALL gate only the confirm, per
these outcomes:

- **200 with a name** → a **loaded** phase showing the event **name** (a **required, non-null** value)
  and carrying the event's **`startsAt`** (both read from the `{ eventId, name, createdAt, startsAt }`
  body), with the confirm action (Join) enabled. The loaded `startsAt` SHALL be the cutoff row's
  **default** *and* its **floor** (see capability `photo-date-cutoff`). `startsAt` is **always present**
  on a 200 — the backend synthesizes it from `createdAt` for markers written before it existed
  (capability `event-creation`) — so the loaded phase SHALL carry it non-null and there is **no**
  seed-from-`createdAt` fallback and **no** seed-to-now fallback;
- **200 without a name** → treated as a **failed** phase with a **Retry** action — a loaded event SHALL
  always carry a name (the backend enforces name-required on create, capability `event-creation`), so a
  nameless 200 is a malformed/transient response, never a loaded phase with a null name;
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
(capability `photo-date-cutoff`). Because the loaded phase carries a non-null name, downstream
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
event's **`startsAt`**, the **clamped** capture-date cutoff — see below and capability
`photo-date-cutoff` — the chosen participation **direction**, **and whether the join opted into an event
album — `saveToAlbum`**, capability `event-album`) and, **when the chosen direction includes upload**
(`Both` or `UploadOnly`), enabling the background-upload producer.

The persisted cutoff SHALL be `max(chosen, startsAt)` — the use-case SHALL apply the event's start date
as a **floor** on whatever cutoff reaches it, and persist the clamped value. The clamp SHALL be applied
in the use-case (not only in the UI) so that **every** entry path is covered — the interactive confirm,
the switch confirm, the retry, and the `autoJoin` path with a deeplink-supplied cutoff alike.

When the chosen direction is `DownloadOnly` the producer SHALL **not** be enabled — the device still
enrolls (the empty manifest makes it a member) and still runs the download machinery, but contributes no
photos. Enrollment SHALL be performed for **all** directions, so a download-only device is an enumerable,
notifiable, event-alive member exactly like a contributor; enrollment SHALL make the device a member
immediately — before any photo upload — by making its manifest object present under
`events/<eventId>/devices/`; a contributing device's real asset manifest is written later by the normal
upload cycle (last-write-wins), **scoped by the persisted cutoff**. The `saveToAlbum` choice SHALL be
persisted for **all** directions (the album is populated by whichever direction(s) sync). A **failed**
enrollment SHALL keep the user on the join surface with an error and a **Retry** action, and SHALL persist
nothing and enable no producer (no half-joined state). The platform effects (the enrollment write and the
producer enable) SHALL be injected so the use-case is pure `commonMain`.

#### Scenario: Confirm clamps the cutoff to the event's start
- **WHEN** the user confirms a join to an event whose `startsAt` is `2026-07-14T18:00:00Z` with a chosen
  cutoff of `2026-07-14T12:00:00Z` and enrollment returns 201
- **THEN** the saved config carries `minPhotoDate = 2026-07-14T18:00:00Z` and `startsAt =
  2026-07-14T18:00:00Z`

#### Scenario: A cutoff above the floor is persisted unchanged
- **WHEN** the user confirms with a chosen cutoff of `2026-07-14T21:00:00Z` against a `startsAt` of
  `2026-07-14T18:00:00Z`
- **THEN** the saved config carries `minPhotoDate = 2026-07-14T21:00:00Z` and `startsAt =
  2026-07-14T18:00:00Z`

#### Scenario: The clamp lives in the use-case, so every path is covered
- **WHEN** a cutoff reaches `JoinEvent` from any entry path — interactive confirm, switch confirm, retry,
  or an `autoJoin` deeplink override
- **THEN** the same `max(chosen, startsAt)` clamp is applied before the config is saved

#### Scenario: Confirm persists the album choice
- **WHEN** the user confirms with `saveToAlbum = true` and enrollment returns 201
- **THEN** the saved config carries `saveToAlbum = true` alongside the event id, name, startsAt, cutoff, and direction

#### Scenario: Confirm enrolls with an empty manifest, then commits with the direction and cutoff
- **WHEN** the user confirms with direction `Both` and `PUT /events/:eventId/devices/:deviceId` with an empty manifest returns 201
- **THEN** the config is saved with the event id, name, `startsAt`, the clamped cutoff, direction `Both`, and the chosen `saveToAlbum`, the upload producer is enabled, and the UI reduces to `Joined`

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

### Requirement: The join surface is a distinct, extensible UiState family

The capability SHALL own a `JoiningEvent` `UiState` family (carrying the `eventId` and a details phase
of loading / loaded-with-name / not-found / failed) and the full-screen "Join event" screen that
renders it, built from `App*` components on `ScreenLayout` (no Material 3 in any `App*` signature). In its
**loaded** phase the surface SHALL present, in addition to the event name and the confirm (Join) / Cancel
actions:

- a **participation-direction row**: a three-way segmented control choosing one of **Both** /
  **Upload only** / **Download only**, defaulting to **Both**. The chosen direction SHALL cross the
  container to `JoinEvent` on confirm. There is no invalid "neither" option — the segmented control
  always has exactly one selection.
- a **capture-date cutoff row**, which SHALL be a **two-preset selector** — **Now** and **Event start** —
  **defaulting to Event start**, together with a **label rendering the resulting instant** so the member
  always sees the value they are committing to (capability `photo-date-cutoff`). The free date+time
  picker is **removed** from this surface (see the REMOVED requirement below).
  - When the event's `startsAt` is **in the future**, the **Now** preset SHALL be rendered **disabled**:
    it would clamp to the same instant as **Event start** (`max(now, startsAt) == startsAt`), and a
    control that visibly does nothing is worse than one that is plainly unavailable.
  - The accepted cost of collapsing this row to two presets, recorded so it is not later rediscovered as
    a defect: a **late-arriving guest has no exact answer**. For a party that started at 18:00 and a
    guest joining at 21:00, **Event start** sweeps in photos they took earlier that day and **Now** drops
    the party photos they have already taken. There is no third option, and none is offered.
  - Because the cutoff scopes **uploads only**, the cutoff row SHALL be rendered **disabled** when the
    selected direction is **Download only** (visible but inert), and enabled otherwise. The chosen cutoff
    SHALL cross the container to `JoinEvent` on confirm.
- a **save-to-album row**: a checkbox ("Save event photos to an album"), an `App*` component,
  **defaulting to unchecked** (opt-in), offered in **all three** directions. When checked, the event's
  synced photos are gathered into a PhotoKit album titled after the event (capability `event-album`). The
  chosen `saveToAlbum` value SHALL cross the container to `JoinEvent` on confirm.

Cancel SHALL discard the pending join and return to the base screen (the create layer when no event is
configured).

#### Scenario: The join screen renders the loaded event with the direction, cutoff selector, and album rows, Join and Cancel
- **WHEN** the `JoiningEvent` state is in its loaded phase for an event that has already started
- **THEN** the full-screen surface shows the event name, the direction segmented control (default Both), the two-preset cutoff selector (default **Event start**) with the resulting instant as a label, the save-to-album checkbox (default unchecked), and Join / Cancel actions

#### Scenario: Now is disabled before the event starts
- **WHEN** the loaded event's `startsAt` is in the future
- **THEN** the **Now** preset is rendered disabled, **Event start** remains selected, and the label shows the event's start instant

#### Scenario: Selecting Now snaps the cutoff to the current instant
- **WHEN** the event has already started and the user selects the **Now** preset
- **THEN** the label updates to the current instant, and that is the cutoff passed on confirm

#### Scenario: The chosen direction, cutoff, and album choice cross on confirm
- **WHEN** the user adjusts the direction, cutoff selector, and save-to-album rows and taps Join
- **THEN** the chosen direction, cutoff, and `saveToAlbum` value are passed through the confirm intent into `JoinEvent`

#### Scenario: The album checkbox is offered in every direction
- **WHEN** the user selects any of Both / Upload only / Download only
- **THEN** the save-to-album checkbox remains available and its choice is honored

#### Scenario: Download-only disables the cutoff row
- **WHEN** the user selects **Download only** on the direction segmented control
- **THEN** the cutoff selector is rendered disabled (visible but not editable), and selecting **Both** or **Upload only** re-enables it

#### Scenario: Cancel discards the pending join
- **WHEN** the user cancels on the join surface with no event configured
- **THEN** the pending join is discarded and the UI returns to the create layer

### Requirement: The autoJoin flag auto-confirms the gate

When a decoded deeplink carries `autoJoin = true`, the system SHALL run the **same** gate — decode,
fetch details, and (when already joined to a different event) leave-then-join — but SHALL **auto-fire**
the confirm once details reach the loaded phase, rather than waiting for a user tap. The auto-fired
confirm SHALL use the **default** cutoff (the loaded event's **`startsAt`** — never an absent cutoff,
capability `photo-date-cutoff`) unless the deeplink carries an explicit dev/test cutoff (see capability
`deeplink-config`), in which case that value SHALL be used **subject to the floor**: the persisted cutoff
is `max(override, startsAt)`, so a deeplink cutoff can raise a membership above the event's start but
never lower it below. SHALL use the **default** direction **Both** unless the deeplink carries an explicit
dev/test `direction` override (`both`/`upload`/`download`, capability `deeplink-config`), in which case
that direction SHALL be used; and SHALL use the **default** album choice **off** unless the deeplink
carries an explicit dev/test `saveToAlbum` override (capability `deeplink-config`), in which case that
value SHALL be used. This keeps the headless developer launch path working (it cannot tap a confirm
control) and lets it force a direction and album choice on device; to exercise date filtering against a
distant-past library, the developer SHALL create the event with an early `startsAt` (the create screen's
picker is unbounded) rather than relying on an unclamped override. Because the auto path has no
interactive surface, a load failure (404 or network) or a failed enrollment SHALL **abort and log** rather
than parking on a retryable error state.

#### Scenario: autoJoin provisions without a tap, using startsAt as the cutoff, Both direction, and album off
- **WHEN** a deeplink with `autoJoin = true` and no explicit cutoff, direction, or album override is decoded and its details load successfully
- **THEN** the confirm is auto-fired with the cutoff defaulting to the loaded `startsAt`, the direction defaulting to `Both`, and `saveToAlbum` defaulting to off

#### Scenario: autoJoin honors an explicit dev/test cutoff above the floor
- **WHEN** a deeplink with `autoJoin = true` carries an explicit dev/test cutoff **later** than the event's `startsAt` and its details load
- **THEN** the auto-fired confirm provisions with that explicit cutoff

#### Scenario: autoJoin clamps an explicit dev/test cutoff below the floor
- **WHEN** a deeplink with `autoJoin = true` carries an explicit dev/test cutoff **earlier** than the event's `startsAt`
- **THEN** the auto-fired confirm provisions with `startsAt`, so a hostile QR cannot auto-join at a wider scope than the event itself allows

#### Scenario: autoJoin honors an explicit dev/test direction override
- **WHEN** a deeplink with `autoJoin = true` carries `direction = download` and its details load
- **THEN** the auto-fired confirm provisions with direction `DownloadOnly` (the producer is not enabled)

#### Scenario: autoJoin honors an explicit dev/test saveToAlbum override
- **WHEN** a deeplink with `autoJoin = true` carries `saveToAlbum = true` and its details load
- **THEN** the auto-fired confirm provisions with `saveToAlbum = true`, so a headless launch exercises album placement

#### Scenario: autoJoin still leaves an existing event
- **WHEN** a deeplink with `autoJoin = true` for a different event is decoded while already joined
- **THEN** the existing event is left first and the new event is joined, without any confirmation UI

#### Scenario: autoJoin aborts on failure instead of showing Retry
- **WHEN** the details fetch returns 404 (or the enrollment fails) on an `autoJoin` launch
- **THEN** the flow aborts and logs, presenting no retryable error surface
