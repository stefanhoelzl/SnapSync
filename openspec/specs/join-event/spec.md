# join-event Specification

## Purpose

The join gate: scanning a QR or opening a `snapsync://` deeplink no longer joins silently. The app loads and
verifies the event's details, shows an explicit confirmation, and only on confirm enrolls the device with the
backend and provisions the config.

The surface is deliberately a **distinct, extensible `UiState` family** rather than a bare confirm dialog,
because joining is where a member's participation is *configured*, and those options were always going to
accumulate: the capture-date cutoff (`photo-date-cutoff`), the upload/download direction
(`add-join-direction-mode`), and the per-event album opt-in (`event-album`) are all rows on this screen. A
dialog could not have grown them.

Switching events composes leave-then-join, so provisioning a different event cleanly departs the previous
one. `autoJoin` auto-confirms the gate for the device that just created the event, which has already
expressed intent.

Decision record: `changes/archive/2026-07-06-add-event-join-confirmation`.

## Requirements
### Requirement: Joining is gated by an explicit confirmation

The system SHALL NOT provision an event directly from a decoded interactive deeplink. When the
container's `onOpenUrl` decodes a valid `snapsync://` link **without** the `autoJoin` flag, it SHALL
route the decoded `eventId` into a **pending-join** UI state and provision the event **only** after the
user confirms. Decoding, the details fetch, and the confirmation SHALL all occur before any config is
persisted or any upload producer is enabled. A decode **failure** SHALL surface the existing invalid-
link effect and produce no pending-join state.

This gate is the single decode-and-route point shared by both entry doors (the presentation
container and the iOS `SnapSyncRoot.onOpenUrl`); neither door provisions ahead of the gate for the
interactive case.

#### Scenario: A scanned interactive link opens the join confirmation, not a provision
- **WHEN** a valid `snapsync://config?…` link without `autoJoin` is decoded and no event is currently configured
- **THEN** the UI reduces to the `JoiningEvent` family for that `eventId`, and no config is saved and no upload producer is enabled until the user confirms

#### Scenario: A malformed link produces no pending join
- **WHEN** a raw URL fails the structural decode
- **THEN** the invalid-config-link effect is surfaced and no `JoiningEvent` state is entered

### Requirement: The confirmation loads and verifies event details first

On entering the pending-join state, the system SHALL fetch the event's details by `GET /events/:eventId`
before offering the confirm action, showing a **loading** phase ("Loading event details…"). The screen
SHALL open immediately on decode (the `eventId` is local) and the load SHALL gate only the confirm, per
these outcomes:

- **200 with a name** → a **loaded** phase showing the event **name** (a **required, non-null** value)
  and carrying the event's **`createdAt`** (both read from the `{ eventId, name, createdAt }` body), with
  the confirm action (Join) enabled; the loaded `createdAt` SHALL seed the cutoff row's **default** (see
  capability `photo-date-cutoff`). When `createdAt` is **absent or unparseable**, the cutoff row SHALL be
  seeded to **now** rather than left empty;
- **200 without a name** → treated as a **failed** phase with a **Retry** action — a loaded event SHALL
  always carry a name (the backend enforces name-required on create, capability `event-creation`), so a
  nameless 200 is a malformed/transient response, never a loaded phase with a null name;
- **404** → a **blocked** phase ("this invite is invalid or the event no longer exists") with **no**
  confirm action — the details fetch is the event-existence gate;
- **network / non-404 failure** → a **failed** phase with a **Retry** action that re-runs the fetch.

The confirm action SHALL NOT be offered while loading, blocked, or failed. The join surface SHALL hold a
cutoff that is **always present**: the loaded phase's cutoff and the surface's chosen cutoff SHALL both be
non-nullable, so a join with no cutoff is unrepresentable rather than guarded against at confirm time
(capability `photo-date-cutoff`). Because the loaded phase carries a non-null name, downstream
provisioning and album titling (capability `event-album`) always have a name to use.

#### Scenario: Details load and enable confirm
- **WHEN** `GET /events/:eventId` returns 200 with the event name and `createdAt`
- **THEN** the join surface shows the name, seeds the cutoff default from `createdAt`, and offers the Join confirm action

#### Scenario: An absent createdAt seeds the cutoff to now
- **WHEN** `GET /events/:eventId` returns 200 with a name but no `createdAt`, or a `createdAt` that does
  not parse
- **THEN** the cutoff row is seeded to the current instant, the row is never empty, and the Join confirm
  action is offered

#### Scenario: The cutoff row is seeded on first composition and never empty
- **WHEN** the join surface first composes in any phase — including a commit-failure phase reached without
  passing through the loaded phase
- **THEN** the cutoff row carries a value (the loaded default, else now), and the confirm/retry action
  passes that value on, there being no representable state in which it could pass none

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
chosen capture-date cutoff — see capability `photo-date-cutoff` — the chosen participation **direction**,
**and whether the join opted into an event album — `saveToAlbum`**, capability `event-album`) and, **when
the chosen direction includes upload** (`Both` or `UploadOnly`), enabling the background-upload producer.
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

#### Scenario: Confirm persists the album choice
- **WHEN** the user confirms with `saveToAlbum = true` and enrollment returns 201
- **THEN** the saved config carries `saveToAlbum = true` alongside the event id, name, cutoff, and direction

#### Scenario: Confirm enrolls with an empty manifest, then commits with the direction and cutoff
- **WHEN** the user confirms with direction `Both` and `PUT /events/:eventId/devices/:deviceId` with an empty manifest returns 201
- **THEN** the config is saved with the event id, name, cutoff, direction `Both`, and the chosen `saveToAlbum`, the upload producer is enabled, and the UI reduces to `Joined`

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
device is not currently in — a first join (no config) or a switch **to a different** `eventId`. A
re-scan or re-provision of the event the device is **already** joined to SHALL be a no-op that does
**not** re-write the empty manifest, so a real asset manifest previously written by the upload cycle is
never clobbered back to empty. This is consistent with `event-rejoin-reconciliation`'s no-op on
re-provision of the already-joined event.

#### Scenario: Re-scanning the current event does not clobber the manifest
- **WHEN** a deeplink for the event the device is already joined to is decoded
- **THEN** no enrollment PUT is issued and the device's existing manifest is left untouched

#### Scenario: Switching to a different event enrolls
- **WHEN** the confirmed target `eventId` differs from the currently configured one
- **THEN** the enrollment PUT for the new event is issued as part of the join

### Requirement: Switching events composes leave then join

The system SHALL, when a deeplink for a **different** event is decoded while an event is already
configured, present a leave-style confirmation (of the form "Leave the current event and join the new
one?") carried as a `pendingSwitch` on the `Joined` state, and this confirmation SHALL be details-gated like a first join
(it shows the new event's fetched name and blocks on a 404). On confirm, the system SHALL run
**`leaveEvent.leave()` then `joinEvent.join(newEventId)`** — composing the existing `LeaveEvent`
use-case without modifying it, so the switch inherits any backend behavior `leave()` later gains. If
the leave succeeds but the join then fails, the device is transiently in **no event**; the surface
SHALL show an error and a **Retry** that re-runs **only** the join for the remembered target.

#### Scenario: Switch confirmation shows the new event and composes leave then join
- **WHEN** a link for a different event is decoded while joined, its details load, and the user confirms the switch
- **THEN** `leaveEvent.leave()` runs first and then `joinEvent.join(newEventId)`, leaving the device joined to the new event

#### Scenario: A join that fails after a successful leave is retryable
- **WHEN** the leave step succeeds but the subsequent join enrollment fails
- **THEN** the device is in no event and the surface offers a Retry that re-runs only the join for the pending target

#### Scenario: The switch never edits the leave use-case
- **WHEN** the switch path runs
- **THEN** it invokes `LeaveEvent.leave()` as-is (the composition adds no leave behavior of its own)

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
- a **capture-date cutoff row**: a prefilled cutoff value (defaulting to the loaded `createdAt`), an
  **"Only from now"** shortcut that snaps the value to the current instant, and a manual **date+time**
  picker (via the design system's date/time component) for any value, with bounds unrestricted. Because
  the cutoff scopes **uploads only**, the cutoff row SHALL be rendered **disabled** when the selected
  direction is **Download only** (visible but inert), and enabled otherwise. The chosen cutoff SHALL
  cross the container to `JoinEvent` on confirm.
- a **save-to-album row**: a checkbox ("Save event photos to an album"), an `App*` component,
  **defaulting to unchecked** (opt-in), offered in **all three** directions. When checked, the event's
  synced photos are gathered into a PhotoKit album titled after the event (capability `event-album`). The
  chosen `saveToAlbum` value SHALL cross the container to `JoinEvent` on confirm.

Cancel SHALL discard the pending join and return to the base screen (the create layer when no event is
configured).

#### Scenario: The join screen renders the loaded event with the direction, cutoff, and album rows, Join and Cancel
- **WHEN** the `JoiningEvent` state is in its loaded phase
- **THEN** the full-screen surface shows the event name, the direction segmented control (default Both), the cutoff row, the save-to-album checkbox (default unchecked), and Join / Cancel actions

#### Scenario: The chosen direction, cutoff, and album choice cross on confirm
- **WHEN** the user adjusts the direction, cutoff, and save-to-album rows and taps Join
- **THEN** the chosen direction, cutoff, and `saveToAlbum` value are passed through the confirm intent into `JoinEvent`

#### Scenario: The album checkbox is offered in every direction
- **WHEN** the user selects any of Both / Upload only / Download only
- **THEN** the save-to-album checkbox remains available and its choice is honored

#### Scenario: Download-only disables the cutoff row
- **WHEN** the user selects **Download only** on the direction segmented control
- **THEN** the cutoff row is rendered disabled (visible but not editable), and selecting **Both** or **Upload only** re-enables it

#### Scenario: Cancel discards the pending join
- **WHEN** the user cancels on the join surface with no event configured
- **THEN** the pending join is discarded and the UI returns to the create layer

### Requirement: The autoJoin flag auto-confirms the gate

When a decoded deeplink carries `autoJoin = true`, the system SHALL run the **same** gate — decode,
fetch details, and (when already joined to a different event) leave-then-join — but SHALL **auto-fire**
the confirm once details reach the loaded phase, rather than waiting for a user tap. The auto-fired
confirm SHALL use the **default** cutoff (the loaded event's `createdAt`, or **now** when that is absent
or unparseable — never an absent cutoff, capability `photo-date-cutoff`) unless the deeplink carries an
explicit dev/test cutoff (see capability `deeplink-config`), in which case that value SHALL be used;
SHALL use the **default** direction **Both** unless the deeplink carries an explicit dev/test `direction`
override (`both`/`upload`/`download`, capability `deeplink-config`), in which case that direction SHALL
be used; and SHALL use the **default** album choice **off** unless the deeplink carries an explicit
dev/test `saveToAlbum` override (capability `deeplink-config`), in which case that value SHALL be used.
This keeps the headless developer launch path working (it cannot tap a confirm control) and lets it force
a specific cutoff, direction, and album choice on device. Because the auto path has no interactive
surface, a load failure (404 or network) or a failed enrollment SHALL **abort and log** rather than
parking on a retryable error state.

#### Scenario: autoJoin provisions without a tap, using the default cutoff, Both direction, and album off
- **WHEN** a deeplink with `autoJoin = true` and no explicit cutoff, direction, or album override is decoded and its details load successfully
- **THEN** the confirm is auto-fired with the cutoff defaulting to the loaded `createdAt`, the direction defaulting to `Both`, and `saveToAlbum` defaulting to off

#### Scenario: autoJoin falls back to now when the event carries no createdAt
- **WHEN** a deeplink with `autoJoin = true` and no explicit cutoff is decoded, and the loaded event
  carries no `createdAt` (or an unparseable one)
- **THEN** the auto-fired confirm provisions with the current instant as the cutoff, never an absent cutoff

#### Scenario: autoJoin honors an explicit dev/test cutoff
- **WHEN** a deeplink with `autoJoin = true` carries an explicit dev/test cutoff and its details load
- **THEN** the auto-fired confirm provisions with that explicit cutoff

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

### Requirement: The join gate explains photo access before the first system dialog

The join gate SHALL show a full-screen **photo-access explainer** before the photo-library permission
dialog is ever raised, and the system dialog SHALL be reachable from that surface **only** via its
confirm action.

The explainer SHALL be a phase of the `JoiningEvent` family (not a dialog, not a separate `UiState`),
entered from the details fetch: when the fetch resolves to a loaded event, the gate SHALL enter the
**explain-access** phase instead of the loaded/confirm phase exactly when **both** hold:

- **no event is currently configured** (a *first* join — a switch, which is confirmed over the joined
  layer, SHALL never explain), and
- photo permission is `NOT_DETERMINED`.

Permission SHALL be read as a **snapshot at the moment the phase is chosen**, not observed — the phase
advances only by user action, so a permission change while the explainer is on screen SHALL NOT move it.

`GRANTED` and `DENIED` SHALL both go straight to the loaded/confirm phase. `DENIED` is excluded because
iOS raises the photo dialog at most once: from `DENIED` a request is a silent no-op, so an explainer
promising a dialog that cannot appear would be false. A `DENIED` joiner instead meets the joined layer's
existing `NeedsAccess` Settings affordance (capability `sync-status-screen`).

The explainer's **confirm** ("I understand") SHALL call `PermissionRequester.request()` and advance to
the loaded/confirm phase in the same action. Because `request()` returns nothing and cannot suspend
(capability `permission-gate`), the phase SHALL advance immediately rather than await an outcome; the
system dialog lands over the confirm surface. The explainer's **cancel** SHALL discard the pending join
exactly as cancel does in every other phase — no enrollment, no config, no producer.

The explainer SHALL NOT auto-request: the system dialog fires only from the confirm action (CTA-only
priming). No other join-gate phase SHALL raise it.

The copy SHALL state that photos the user takes are shared automatically with everyone in the event,
SHALL state that the permission is also what saves other members' photos into the user's library, and
SHALL state that only photos taken after the capture-date the user chooses next are shared. It MUST NOT
describe the app's function as "backing up" the user's library (consistent with `event-creation-ui`),
and MUST NOT claim a scope wider than the membership can produce.

Because a created event routes into the **same** gate (`onEventCreated` → the pending-join state), the
explainer SHALL cover a first-time creator and a first-time scanner identically, with no create-path
surface of its own.

#### Scenario: A first join with permission never asked explains before the dialog
- **WHEN** the details fetch resolves to a loaded event, no event is configured, and photo permission is `NOT_DETERMINED`
- **THEN** the gate enters the explain-access phase, no permission request has been made, and the loaded/confirm surface is not yet shown

#### Scenario: The confirm requests permission and advances to the confirm surface
- **WHEN** the user takes the explainer's confirm action
- **THEN** `PermissionRequester.request()` is called exactly once and the gate advances to the loaded/confirm phase carrying the same event name and default cutoff

#### Scenario: Cancelling the explainer abandons the join
- **WHEN** the user cancels on the explain-access phase
- **THEN** the pending join is discarded, no device is enrolled, no config is saved, no upload producer is enabled, and the UI returns to the create layer

#### Scenario: Already-granted access skips the explainer
- **WHEN** the details fetch resolves to a loaded event and photo permission is `GRANTED`
- **THEN** the gate enters the loaded/confirm phase directly and no explainer is shown

#### Scenario: Previously-denied access skips the explainer
- **WHEN** the details fetch resolves to a loaded event and photo permission is `DENIED`
- **THEN** the gate enters the loaded/confirm phase directly, no explainer is shown, and no permission request is made

#### Scenario: A switch never explains
- **WHEN** a deeplink for a different event is decoded while an event is already configured, and photo permission is `NOT_DETERMINED`
- **THEN** the switch confirmation is presented in its existing form and the explain-access phase is never entered

#### Scenario: A created event's first join explains too
- **WHEN** a freshly created event is routed into the pending-join gate, no event is configured, and photo permission is `NOT_DETERMINED`
- **THEN** the same explain-access phase is entered, with no create-specific surface

#### Scenario: The explainer never auto-requests
- **WHEN** the explain-access phase is rendered
- **THEN** no permission request is made until the user takes the confirm action

### Requirement: The cutoff row is seeded from the loaded default, not from the mount-time phase

The join surface's capture-date row SHALL be prefilled from the **loaded** `createdAt` default, and the
seed SHALL be taken from the first phase that carries one — **not** from whichever phase the surface
happened to first render.

The surface is mounted at the **loading** phase (the pending join is created before the details fetch is
issued), so no default exists at first render; the explain-access phase, when shown, carries the default
ahead of the confirm phase. A seed evaluated once at first render therefore falls through to *now* and is
never revisited, silently defaulting every real join to now and defeating "a prefilled cutoff value
(defaulting to the loaded `createdAt`)".

The seed SHALL be taken **once**. The commit phases (committing, commit-failed) carry no default, so a
re-seed on every phase change would discard a value the user had chosen, which a retry must reuse. A
surface entered directly at a phase that never carries a default SHALL keep *now* — the safe direction,
since *now* shares too few photos (which a re-join fixes) whereas the opposite error cannot be undone.

#### Scenario: The cutoff is seeded from the loaded default across the real phase sequence
- **WHEN** the join surface advances loading → explain-access → confirm, with a loaded `createdAt` default
- **THEN** the capture-date row shows the loaded `createdAt`, not the current time

#### Scenario: A user's chosen cutoff survives a failed commit
- **WHEN** the user changes the capture-date, confirms, the commit fails, and they retry
- **THEN** the retry carries the cutoff the user chose, not a re-seeded default and not now

#### Scenario: A surface with no loaded default keeps now
- **WHEN** the join surface renders a phase that carries no `createdAt` default, and none was ever loaded
- **THEN** the capture-date is the current time, never absent and never whole-library

