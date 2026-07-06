## MODIFIED Requirements

### Requirement: The confirmation loads and verifies event details first

On entering the pending-join state, the system SHALL fetch the event's details by `GET /events/:eventId`
before offering the confirm action, showing a **loading** phase ("Loading event details…"). The screen
SHALL open immediately on decode (the `eventId` is local) and the load SHALL gate only the confirm, per
these outcomes:

- **200** → a **loaded** phase showing the event **name** and carrying the event's **`createdAt`** (both
  read from the `{ eventId, name, createdAt }` body), with the confirm action (Join) enabled; the loaded
  `createdAt` SHALL seed the cutoff row's **default** (see capability `photo-date-cutoff`);
- **404** → a **blocked** phase ("this invite is invalid or the event no longer exists") with **no**
  confirm action — the details fetch is the event-existence gate;
- **network / non-404 failure** → a **failed** phase with a **Retry** action that re-runs the fetch.

The confirm action SHALL NOT be offered while loading, blocked, or failed.

#### Scenario: Details load and enable confirm
- **WHEN** `GET /events/:eventId` returns 200 with the event name and `createdAt`
- **THEN** the join surface shows the name, seeds the cutoff default from `createdAt`, and offers the Join confirm action

#### Scenario: A missing event blocks the join
- **WHEN** `GET /events/:eventId` returns 404
- **THEN** the join surface shows an invalid/expired-invite message and offers no Join action

#### Scenario: A load failure is retryable
- **WHEN** `GET /events/:eventId` fails on the network or returns a non-404 error
- **THEN** the join surface shows a load-failure message and a Retry action that re-runs the fetch

### Requirement: Confirming enrolls the device, then provisions

The `JoinEvent` use-case SHALL, on confirm, **first** enroll the device by writing a **register-only,
empty** device manifest (no assets) via `PUT /events/:eventId/devices/:deviceId`, and **only on a
successful (201) enrollment** commit the join by saving the config (`eventId`, the loaded name, **and the
chosen capture-date cutoff** — see capability `photo-date-cutoff`) and enabling the background-upload
producer. The cutoff persisted at this step is the value shown in the confirm surface's cutoff row (its
`createdAt`-seeded default, the "only from now" instant, or a manual pick). Enrollment SHALL make the
device an enumerable, notifiable member of the event immediately — before any photo upload — by making
its manifest object present under `events/<eventId>/devices/`; the device's real asset manifest is written
later by the normal upload cycle (last-write-wins), **scoped by the persisted cutoff**. A **failed**
enrollment SHALL keep the user on the join surface with an error and a **Retry** action, and SHALL persist
nothing and enable no producer (no half-joined state). The platform effects (the enrollment write and the
producer enable) SHALL be injected so the use-case is pure `commonMain`.

#### Scenario: Confirm enrolls with an empty manifest, then commits with the cutoff
- **WHEN** the user confirms and `PUT /events/:eventId/devices/:deviceId` with an empty manifest returns 201
- **THEN** the config is saved with the event id, name, and the chosen cutoff, the upload producer is enabled, and the UI reduces to `Joined`

#### Scenario: A failed enrollment does not join
- **WHEN** the user confirms and the enrollment PUT fails
- **THEN** no config is saved and no producer is enabled, and the join surface shows an error with a Retry action

#### Scenario: Enrollment makes the device a member before any upload
- **WHEN** enrollment succeeds against an event with no prior manifest for this device
- **THEN** the device's manifest object exists under `events/<eventId>/devices/` so the event enumerates and can notify it, even though no photo bytes have been uploaded yet

### Requirement: The join surface is a distinct, extensible UiState family

The capability SHALL own a `JoiningEvent` `UiState` family (carrying the `eventId` and a details phase
of loading / loaded-with-name / not-found / failed) and the full-screen "Join event" screen that
renders it, built from `App*` components on `ScreenLayout` (no Material 3 in any `App*` signature). In its
**loaded** phase the surface SHALL present, in addition to the event name and the confirm (Join) / Cancel
actions, a **capture-date cutoff row**: a prefilled cutoff value (defaulting to the loaded `createdAt`),
an **"Only from now"** shortcut that snaps the value to the current instant, and a manual **date+time**
picker (via the design system's date/time component) for any value, with bounds unrestricted. The chosen
cutoff SHALL cross the container to `JoinEvent` on confirm. The surface SHALL remain structured so further
future join options (upload/download direction, album selection, save-to album) can be added as rows
without reshaping the surface or the state. Cancel SHALL discard the pending join and return to the base
screen (the create layer when no event is configured).

#### Scenario: The join screen renders the loaded event with the cutoff row, Join and Cancel
- **WHEN** the `JoiningEvent` state is in its loaded phase
- **THEN** the full-screen surface shows the event name, the cutoff row (default from `createdAt`, an "Only from now" shortcut, a manual date+time picker), and Join / Cancel actions

#### Scenario: The chosen cutoff crosses on confirm
- **WHEN** the user adjusts the cutoff row and taps Join
- **THEN** the chosen cutoff is passed through the confirm intent into `JoinEvent`

#### Scenario: Cancel discards the pending join
- **WHEN** the user cancels on the join surface with no event configured
- **THEN** the pending join is discarded and the UI returns to the create layer

### Requirement: The autoJoin flag auto-confirms the gate

When a decoded deeplink carries `autoJoin = true`, the system SHALL run the **same** gate — decode,
fetch details, and (when already joined to a different event) leave-then-join — but SHALL **auto-fire**
the confirm once details reach the loaded phase, rather than waiting for a user tap. The auto-fired
confirm SHALL use the **default** cutoff (the loaded event's `createdAt`), unless the deeplink carries an
explicit dev/test cutoff (see capability `deeplink-config`), in which case that value SHALL be used. This
keeps the headless developer launch path working (it cannot tap a confirm control) and lets it force a
specific cutoff to observe date filtering. Because the auto path has no interactive surface, a load
failure (404 or network) or a failed enrollment SHALL **abort and log** rather than parking on a
retryable error state.

#### Scenario: autoJoin provisions without a tap, using the createdAt default cutoff
- **WHEN** a deeplink with `autoJoin = true` and no explicit cutoff is decoded and its details load successfully
- **THEN** the confirm is auto-fired with the cutoff defaulting to the loaded `createdAt` — the enrollment and provision run with no user interaction

#### Scenario: autoJoin honors an explicit dev/test cutoff
- **WHEN** a deeplink with `autoJoin = true` carries an explicit dev/test cutoff and its details load
- **THEN** the auto-fired confirm provisions with that explicit cutoff

#### Scenario: autoJoin still leaves an existing event
- **WHEN** a deeplink with `autoJoin = true` for a different event is decoded while already joined
- **THEN** the existing event is left first and the new event is joined, without any confirmation UI

#### Scenario: autoJoin aborts on failure instead of showing Retry
- **WHEN** the details fetch returns 404 (or the enrollment fails) on an `autoJoin` launch
- **THEN** the flow aborts and logs, presenting no retryable error surface
