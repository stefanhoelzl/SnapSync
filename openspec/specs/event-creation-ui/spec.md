# event-creation-ui Specification

## Purpose
TBD - created by archiving change add-event-creation-ui. Update Purpose after archive.
## Requirements
### Requirement: Create-event seams and status model

The app-side create capability (`:capability:event-creation-ui`) SHALL define a command port and a
state port, consumed separately by the presentation container (mirroring the
`PermissionRequester` / `PermissionStatusSource` split):

- `EventCreator` (command port): `fun create(name: String)` — fire-and-forget. It MUST NOT return a
  value and MUST NOT suspend; the outcome arrives exclusively via `CreationStatusSource`.
- `CreationStatusSource` (state port): exposes `creationStatus: StateFlow<CreationStatus>`, a
  level-triggered holder whose current value is always available synchronously; every emission is the
  whole truth.

`CreationStatus` SHALL have exactly three shapes: `Idle`, `InFlight`, and `Failed(reason)` where
`reason` distinguishes an invalid-name rejection from a transient/server failure (so the screen can
show the right copy). There SHALL be no `Succeeded` value — a successful create provisions config,
which moves the reduction off the create layer.

#### Scenario: Outcome arrives only via the state port
- **WHEN** `EventCreator.create(name)` is invoked
- **THEN** `create` itself communicates nothing, and the in-flight and terminal outcomes are observed
  as emissions of `CreationStatusSource.creationStatus`

#### Scenario: Status models in-flight and failure but never success
- **WHEN** the creation status is inspected across a create attempt
- **THEN** it holds `InFlight` during the request and `Failed(reason)` on failure, and a success is
  represented by config becoming present (not by a `CreationStatus` value)

### Requirement: Create mints an event then provisions it like a scanned QR

The capability SHALL provide a create use-case that, on `create(name)`, sets `creationStatus` to
`InFlight`, calls the backend `POST /event` with the trimmed name via an injected client, and on a
`201 { eventId, name, createdAt }` funnels the returned `eventId` **and** `name` into the **existing**
provision path — the same `onProvision(previousEventId, newEventId)` switch-reset a scanned deeplink
uses — saving `EventConfig(eventId, name)` **directly** (the create path already has the name, so it
performs **no** `GET /event/:id` fetch — see `deeplink-config`). On any failure (non-2xx, transport, or
parse) it SHALL set `creationStatus` to `Failed(reason)` and SHALL NOT save config. The use-case MUST
NOT inspect `PermissionStatus`.

#### Scenario: Successful create provisions the event with its name
- **WHEN** `create("My Party")` is invoked and the backend returns `201` with `{eventId, name}`
- **THEN** the event is provisioned through the same `onProvision` path as a scanned QR,
  `EventConfig(eventId, name)` is saved (no metadata fetch), config becomes present, and the existing
  join/reconcile flow runs

#### Scenario: Create ignores permission
- **WHEN** `create(name)` is invoked while photo permission is `NOT_DETERMINED` or `DENIED`
- **THEN** the create proceeds (mints + provisions) without inspecting permission, and the missing
  permission surfaces afterward via the joined-layer `NeedsAccess` status line (per `sync-status-screen`)

#### Scenario: A failed create leaves config untouched
- **WHEN** `create(name)` is invoked and the backend request fails (non-2xx, transport, or parse)
- **THEN** `creationStatus` becomes `Failed(reason)`, config is unchanged, and no join is started

### Requirement: HTTP event creator over an injected client

The capability SHALL provide an `EventCreator` HTTP implementation over an injected Ktor `HttpClient`
and a host string (the engine and host are supplied by the composition root, keeping the impl
platform-neutral and testable with `MockEngine`), mirroring `HttpEventFilesSource`. It SHALL
`POST <host>/event` (HTTPS, default ATS) with a JSON body `{ "name": <trimmed name> }`, parse a `201`
body into `{ eventId, name, createdAt }`, and map any non-2xx, transport, or parse error to a failed
result the use-case turns into `Failed`. A `400` SHALL map to the invalid-name reason; any other
non-2xx or transport/parse error SHALL map to the transient/server reason.

#### Scenario: Create posts the name and parses the event
- **WHEN** the client posts to `<host>/event` and the server responds `201` with `{eventId,name,createdAt}`
- **THEN** the parsed `eventId` is returned for provisioning

#### Scenario: A 400 maps to the invalid-name reason
- **WHEN** the server responds `400` to the create request
- **THEN** the result is a failure carrying the invalid-name reason

#### Scenario: A 502 or transport error maps to the transient reason
- **WHEN** the server responds `502`, or the request fails to reach the server, or the body does not parse
- **THEN** the result is a failure carrying the transient/server reason

### Requirement: Config-absent reduces to the create layer

The presentation reduction SHALL gate the top rung on config presence: whenever `config == null`, the
screen SHALL reduce to the create layer derived from `creationStatus`, **regardless of permission,
join status, or snapshot** — `InFlight` SHALL reduce to `UiState.CreatingEvent`; `Failed(reason)` SHALL
reduce to the create-input state carrying the matching inline error; `Idle` SHALL reduce to the
create-input state with no error. Only when `config` is present do the existing rungs (permission →
join → sync hero, see `sync-status-screen`) apply. The reduction MUST depend only on the latest source
values; the container's initial UI state SHALL be computed from the sources' current values at
construction.

#### Scenario: No config shows the create input regardless of permission
- **WHEN** config is `null`, creation status is `Idle`, and permission is `GRANTED`, `DENIED`, or `NOT_DETERMINED`
- **THEN** the UI state is the create-input state, not a permission, join, or sync state

#### Scenario: In-flight create shows the creating state
- **WHEN** config is `null` and creation status is `InFlight`
- **THEN** the UI state is `UiState.CreatingEvent`

#### Scenario: Failed create shows the input with an inline error
- **WHEN** config is `null` and creation status is `Failed(reason)`
- **THEN** the UI state is the create-input state carrying the inline error matching the reason

#### Scenario: Config present leaves the create layer
- **WHEN** config becomes present after a successful create
- **THEN** the reduction leaves the create layer and the existing permission/join/sync rungs apply

### Requirement: Create-event screen

When the UI state is the create layer, the status screen SHALL render a create-event screen composed
from `App*` components within `ScreenLayout`: an `AppTextField` for the event name, a `PrimaryButton`
labelled to create the event, and a passive hint that an event can also be joined by scanning its QR
code with the Camera. The Create action SHALL be disabled while the trimmed name is empty, and the
field SHALL accept at most 100 characters. The create-input state SHALL carry an optional inline error
line rendered beneath the input. While the UI state is `CreatingEvent`, the screen SHALL show a
preparing indicator and no input. The create layer SHALL NOT show the leave action.

#### Scenario: Empty name disables Create
- **WHEN** the create screen is shown and the name field is empty or whitespace-only
- **THEN** the Create action is disabled

#### Scenario: A typed name enables Create
- **WHEN** the user types a non-empty name
- **THEN** the Create action is enabled and activating it invokes `EventCreator.create` with the
  trimmed name through the container

#### Scenario: The name field caps at 100 characters
- **WHEN** the user attempts to enter more than 100 characters
- **THEN** the field holds at 100 characters

#### Scenario: The scan hint is present and passive
- **WHEN** the create screen is shown
- **THEN** it displays a passive "scan a QR to join" hint with no button

#### Scenario: Creating shows a preparing indicator
- **WHEN** the UI state is `CreatingEvent`
- **THEN** the screen shows a preparing indicator and hides the input

### Requirement: Create screen owns the deeplink intent and one inline error surface

The container SHALL expose an `onCreateEvent(name: String)` intent that calls `EventCreator.create`,
and SHALL retain the `onOpenUrl(raw: String)` intent that decodes an incoming deeplink via the
`deeplink-config` decoder and, on success, provisions it (the QR-join path is unchanged). The create
screen SHALL render a single inline error region serving two causes: a `Failed(reason)` create error
(sticky until the next create attempt) and a transient, self-clearing invalid-deeplink error emitted
when `onOpenUrl` receives a URL the decoder rejects. An invalid deeplink MUST NOT change persisted
config.

#### Scenario: Create failure shows a sticky inline error
- **WHEN** a create attempt fails and the reduction returns to the create-input state
- **THEN** the inline error shows the failure copy and persists until the next create attempt (which
  re-enters `InFlight` and clears it)

#### Scenario: Invalid deeplink flashes a transient error and changes nothing
- **WHEN** `onOpenUrl` receives a URL the decoder rejects
- **THEN** a transient invalid-link error is surfaced on the create screen, persisted config is
  unchanged, and the error self-clears

#### Scenario: A valid deeplink still joins from the create screen
- **WHEN** `onOpenUrl` receives a structurally-valid `snapsync://config?…` URL while the create screen is shown
- **THEN** the decoded config is provisioned (saved) and the existing join flow runs

### Requirement: Sharing framing in create and status copy

The create-layer and joined-layer user-facing copy SHALL frame the app as **sharing/syncing event
photos**, not as personal photo backup. Copy SHALL NOT describe the app's function as "backing up" the
user's library; it SHALL use "sync"/"share" language. (Exact strings are an implementation concern;
this requirement pins the framing, not the wording.)

#### Scenario: Copy avoids backup framing
- **WHEN** the create screen or the joined status line renders its descriptive copy
- **THEN** the copy frames the action as sharing/syncing event photos and does not describe it as
  backing up the user's photo library

