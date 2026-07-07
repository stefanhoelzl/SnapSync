## MODIFIED Requirements

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

The surface SHALL remain structured so further future join options (album selection, save-to album) can
be added as rows without reshaping the surface or the state. Cancel SHALL discard the pending join and
return to the base screen (the create layer when no event is configured).

#### Scenario: The join screen renders the loaded event with the direction row, cutoff row, Join and Cancel
- **WHEN** the `JoiningEvent` state is in its loaded phase
- **THEN** the full-screen surface shows the event name, the direction segmented control (default Both), the cutoff row (default from `createdAt`, an "Only from now" shortcut, a manual date+time picker), and Join / Cancel actions

#### Scenario: The chosen direction and cutoff cross on confirm
- **WHEN** the user adjusts the direction and cutoff rows and taps Join
- **THEN** the chosen direction and cutoff are passed through the confirm intent into `JoinEvent`

#### Scenario: Download-only disables the cutoff row
- **WHEN** the user selects **Download only** on the direction segmented control
- **THEN** the cutoff row is rendered disabled (visible but not editable), and selecting **Both** or **Upload only** re-enables it

#### Scenario: Cancel discards the pending join
- **WHEN** the user cancels on the join surface with no event configured
- **THEN** the pending join is discarded and the UI returns to the create layer

### Requirement: Confirming enrolls the device, then provisions

The `JoinEvent` use-case SHALL, on confirm, **first** enroll the device by writing a **register-only,
empty** device manifest (no assets) via `PUT /events/:eventId/devices/:deviceId`, and **only on a
successful (201) enrollment** commit the join by saving the config (`eventId`, the loaded name, the
chosen capture-date cutoff — see capability `photo-date-cutoff` — **and the chosen participation
direction**) and, **when the chosen direction includes upload** (`Both` or `UploadOnly`), enabling the
background-upload producer. When the chosen direction is `DownloadOnly` the producer SHALL **not** be
enabled — the device still enrolls (the empty manifest makes it a member) and still runs the download
machinery, but contributes no photos. Enrollment SHALL be performed for **all** directions, so a
download-only device is an enumerable, notifiable, event-alive member exactly like a contributor;
enrollment SHALL make the device a member immediately — before any photo upload — by making its manifest
object present under `events/<eventId>/devices/`; a contributing device's real asset manifest is written
later by the normal upload cycle (last-write-wins), **scoped by the persisted cutoff**. A **failed**
enrollment SHALL keep the user on the join surface with an error and a **Retry** action, and SHALL persist
nothing and enable no producer (no half-joined state). The platform effects (the enrollment write and the
producer enable) SHALL be injected so the use-case is pure `commonMain`.

#### Scenario: Confirm enrolls with an empty manifest, then commits with the direction and cutoff
- **WHEN** the user confirms with direction `Both` and `PUT /events/:eventId/devices/:deviceId` with an empty manifest returns 201
- **THEN** the config is saved with the event id, name, cutoff, and direction `Both`, the upload producer is enabled, and the UI reduces to `Joined`

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

### Requirement: The autoJoin flag auto-confirms the gate

When a decoded deeplink carries `autoJoin = true`, the system SHALL run the **same** gate — decode,
fetch details, and (when already joined to a different event) leave-then-join — but SHALL **auto-fire**
the confirm once details reach the loaded phase, rather than waiting for a user tap. The auto-fired
confirm SHALL use the **default** cutoff (the loaded event's `createdAt`) unless the deeplink carries an
explicit dev/test cutoff (see capability `deeplink-config`), in which case that value SHALL be used; and
SHALL use the **default** direction **Both** unless the deeplink carries an explicit dev/test `direction`
override (`both`/`upload`/`download`, capability `deeplink-config`), in which case that direction SHALL
be used. This keeps the headless developer launch path working (it cannot tap a confirm control) and
lets it force a specific cutoff to observe date filtering and a specific direction to exercise
upload-only / download-only behavior on device. Because the auto path has no interactive surface, a load
failure (404 or network) or a failed enrollment SHALL **abort and log** rather than parking on a
retryable error state.

#### Scenario: autoJoin provisions without a tap, using the createdAt default cutoff and Both direction
- **WHEN** a deeplink with `autoJoin = true` and no explicit cutoff or direction is decoded and its details load successfully
- **THEN** the confirm is auto-fired with the cutoff defaulting to the loaded `createdAt` and the direction defaulting to `Both` — the enrollment and provision run with no user interaction

#### Scenario: autoJoin honors an explicit dev/test cutoff
- **WHEN** a deeplink with `autoJoin = true` carries an explicit dev/test cutoff and its details load
- **THEN** the auto-fired confirm provisions with that explicit cutoff

#### Scenario: autoJoin honors an explicit dev/test direction override
- **WHEN** a deeplink with `autoJoin = true` carries `direction = download` and its details load
- **THEN** the auto-fired confirm provisions with direction `DownloadOnly` (the producer is not enabled)

#### Scenario: autoJoin still leaves an existing event
- **WHEN** a deeplink with `autoJoin = true` for a different event is decoded while already joined
- **THEN** the existing event is left first and the new event is joined, without any confirmation UI

#### Scenario: autoJoin aborts on failure instead of showing Retry
- **WHEN** the details fetch returns 404 (or the enrollment fails) on an `autoJoin` launch
- **THEN** the flow aborts and logs, presenting no retryable error surface
