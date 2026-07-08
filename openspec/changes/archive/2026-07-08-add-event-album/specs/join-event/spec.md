## MODIFIED Requirements

### Requirement: The confirmation loads and verifies event details first

On entering the pending-join state, the system SHALL fetch the event's details by `GET /events/:eventId`
before offering the confirm action, showing a **loading** phase ("Loading event details…"). The screen
SHALL open immediately on decode (the `eventId` is local) and the load SHALL gate only the confirm, per
these outcomes:

- **200 with a name** → a **loaded** phase showing the event **name** (a **required, non-null** value)
  and carrying the event's **`createdAt`** (both read from the `{ eventId, name, createdAt }` body), with
  the confirm action (Join) enabled; the loaded `createdAt` SHALL seed the cutoff row's **default** (see
  capability `photo-date-cutoff`);
- **200 without a name** → treated as a **failed** phase with a **Retry** action — a loaded event SHALL
  always carry a name (the backend enforces name-required on create, capability `event-creation`), so a
  nameless 200 is a malformed/transient response, never a loaded phase with a null name;
- **404** → a **blocked** phase ("this invite is invalid or the event no longer exists") with **no**
  confirm action — the details fetch is the event-existence gate;
- **network / non-404 failure** → a **failed** phase with a **Retry** action that re-runs the fetch.

The confirm action SHALL NOT be offered while loading, blocked, or failed. Because the loaded phase
carries a non-null name, downstream provisioning and album titling (capability `event-album`) always have
a name to use.

#### Scenario: Details load and enable confirm
- **WHEN** `GET /events/:eventId` returns 200 with the event name and `createdAt`
- **THEN** the join surface shows the name, seeds the cutoff default from `createdAt`, and offers the Join confirm action

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
confirm SHALL use the **default** cutoff (the loaded event's `createdAt`) unless the deeplink carries an
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
