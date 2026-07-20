# event-creation-ui Specification

## Purpose

The in-app **create-event** flow: the user names an event, the app mints it against the backend
(`POST /events`) and then provisions the result through exactly the same path a scanned QR would take, so
the creating device auto-joins. Defines the app-side seams (`EventCreator` command port,
`CreationStatusSource` state port, `CreationStatus`), the HTTP creator over an injected client, and the
create screen that becomes the app's no-event layer.

Before it, the backend could mint events (`event-creation`) but the app could only *join* one someone else
handed over by QR. This capability is what makes SnapSync a tool you can start an event with, not only be
invited into — and it is where the product's framing is pinned: the copy describes **sharing photos to an
event**, never personal backup.

Decision record: `changes/archive/2026-06-27-add-event-creation-ui`.
## Requirements
### Requirement: Create-event seams and status model

The create feature (`:domain`'s `feature/creation` zone, package `app.snapsync.feature.creation`) SHALL define a command port and a
state port. The state port is consumed by the presentation container directly (reads observe feature
read-models); the command port is consumed by presentation **only through the injected `flow/`
user-tap command bundle** (`UserCommands.create`, built in `compose/` over the feature's
`EventCreator` — spec `module-architecture`, "Commands cross one door"): the container SHALL NOT
take or reference an `EventCreator` itself.

- `EventCreator` (command port): `fun create(name: String, startsAt: String)` — fire-and-forget. It MUST
  NOT return a value and MUST NOT suspend; the outcome arrives exclusively via `CreationStatusSource`.
  `startsAt` SHALL be a **canonical cutoff string** (`yyyy-MM-dd'T'HH:mm:ss'Z'`, capability
  `photo-selection-policy`), already converted from the user's local pick by the caller — so the capability
  needs no clock, no timezone, and no dependency on the cutoff codec.
- `CreationStatusSource` (state port): exposes `creationStatus: StateFlow<CreationStatus>`, a
  level-triggered holder whose current value is always available synchronously; every emission is the
  whole truth.

`CreationStatus` SHALL have exactly three shapes: `Idle`, `InFlight`, and `Failed(reason)` where
`reason` distinguishes an invalid-name rejection from a transient/server failure (so the screen can
show the right copy). There SHALL be no `Succeeded` value — a successful create provisions config,
which moves the reduction off the create layer.

No new failure reason SHALL be added for an invalid `startsAt`. The app always sends a canonical value
(it comes from a picker, converted through the one cutoff codec), so a `startsAt`-shaped `400` is
unreachable from this client; inventing user-facing copy for a state no user can reach would be dead
surface. The existing single `400` → invalid-name mapping stands.

#### Scenario: Outcome arrives only via the state port
- **WHEN** `EventCreator.create(name, startsAt)` is invoked
- **THEN** `create` itself communicates nothing, and the in-flight and terminal outcomes are observed
  as emissions of `CreationStatusSource.creationStatus`

#### Scenario: The creator receives an already-canonical start date
- **WHEN** `EventCreator.create` is invoked
- **THEN** its `startsAt` argument is already in the canonical `yyyy-MM-dd'T'HH:mm:ss'Z'` shape, the
  capability performing no clock read and no timezone conversion of its own

#### Scenario: Status models in-flight and failure but never success
- **WHEN** the creation status is inspected across a create attempt
- **THEN** it holds `InFlight` during the request and `Failed(reason)` on failure, and a success is
  represented by config becoming present (not by a `CreationStatus` value)

### Requirement: Create mints an event then provisions it like a scanned QR

The capability SHALL provide a create use-case that, on `create(name, startsAt)`, sets `creationStatus`
to `InFlight`, calls the backend `POST /events` with the trimmed name **and the start date** via an
injected client, and on a `201 { eventId, name, createdAt, startsAt }` **routes the returned `eventId`
into the existing pending-join gate** — the same gate a scanned deeplink opens (see capability
`join-event`) — rather than provisioning the config directly. The route SHALL be an **auto-routed but not
auto-confirmed** pending join: the creator is taken to the join surface (which fetches the just-minted
event's details, shows its name, and offers the cutoff selector defaulting to the event's `startsAt`) and
completes the **same** confirm-to-enroll-and-provision flow every joiner uses.

The creator is therefore subject to the **same floor as every other member** (capability
`photo-selection-policy`): the start date they just chose is the floor on their own cutoff too. This is not a
special case — it falls out of create and scan converging on one gate.

Because the `POST` has already minted the event, the gate holds a **real** `eventId`, performs a real
details fetch, and enrolls normally; the config is saved (with name, `startsAt`, and the clamped cutoff)
by the gate's provision step, not by the create use-case. On a successful mint `creationStatus` SHALL
return to `Idle` (the pending join drives the reduction from there); on any failure (non-2xx, transport,
or parse) it SHALL set `creationStatus` to `Failed(reason)`, SHALL NOT open the gate, and SHALL save no
config. A cancelled or abandoned join after a successful mint SHALL leave the minted event as a harmless
member-less marker (no rollback). The use-case MUST NOT inspect `PermissionStatus`.

#### Scenario: Successful create opens the join gate for the minted event
- **WHEN** `create("My Party", "2026-07-14T18:00:00Z")` is invoked and the backend returns `201` with `{eventId, name, createdAt, startsAt}`
- **THEN** the returned `eventId` is routed into the pending-join gate, the join surface loads the event and shows the cutoff selector defaulting to the event's start, and the config is provisioned only when the creator confirms

#### Scenario: The creator is bound by the floor they set
- **WHEN** the creator sets a start date, is routed into the gate, and confirms
- **THEN** the persisted cutoff is `max(chosen, startsAt)` exactly as for any other joiner — the creator
  receives no exemption from the floor

#### Scenario: Create ignores permission
- **WHEN** `create(name, startsAt)` is invoked while photo permission is `NOT_DETERMINED` or `DENIED`
- **THEN** the create proceeds (mints + opens the gate) without inspecting permission, and the missing
  permission surfaces afterward via the joined-layer `NeedsAccess` status line (per `sync-status-screen`)

#### Scenario: A failed create leaves config untouched and opens no gate
- **WHEN** `create(name, startsAt)` is invoked and the backend request fails (non-2xx, transport, or parse)
- **THEN** `creationStatus` becomes `Failed(reason)`, config is unchanged, and no pending join is opened

#### Scenario: A cancelled join after a mint leaves a harmless marker
- **WHEN** the mint succeeds, the gate opens, and the creator cancels before confirming
- **THEN** no config is saved, the device is not enrolled, and the minted event remains as a member-less marker with no rollback

### Requirement: HTTP event creator over an injected client

The capability SHALL provide an `EventCreator` HTTP implementation over an injected Ktor `HttpClient`
and a host string (the engine and host are supplied by the composition root, keeping the impl
platform-neutral and testable with `MockEngine`), mirroring `HttpEventFilesSource`. It SHALL
`POST <host>/events` (HTTPS, default ATS) with a JSON body `{ "name": <trimmed name>, "startsAt":
<canonical start date> }`, parse a `201` body into `{ eventId, name, createdAt, startsAt }`, and map any
non-2xx, transport, or parse error to a failed result the use-case turns into `Failed`. A `400` SHALL map
to the invalid-name reason; any other non-2xx or transport/parse error SHALL map to the transient/server
reason. The `startsAt` SHALL be sent **verbatim** — the client SHALL NOT reformat, re-zone, or re-derive
it, the canonical shape being the caller's contract.

#### Scenario: Create posts the name and the start date
- **WHEN** the client is asked to create `"My Party"` starting `2026-07-14T18:00:00Z`
- **THEN** the request body is exactly `{"name":"My Party","startsAt":"2026-07-14T18:00:00Z"}`

#### Scenario: Create parses the event
- **WHEN** the server responds `201` with `{eventId, name, createdAt, startsAt}`
- **THEN** the parsed `eventId` is returned for provisioning

#### Scenario: A 400 maps to the invalid-name reason
- **WHEN** the server responds `400` to the create request
- **THEN** the result is a failure carrying the invalid-name reason

#### Scenario: A 502 or transport error maps to the transient reason
- **WHEN** the server responds `502`, or the request fails to reach the server, or the body does not parse
- **THEN** the result is a failure carrying the transient/server reason

### Requirement: Config-absent reduces to the create layer

The presentation reduction SHALL gate the top rung on config presence: whenever `config == null` **and no
interactive join is pending**, the screen SHALL reduce to the create layer derived from `creationStatus`,
**regardless of permission or snapshot** — `InFlight` SHALL reduce to `UiState.CreatingEvent`;
`Failed(reason)` SHALL reduce to the create-input state carrying the matching inline error; `Idle` SHALL
reduce to the create-input state with no error. When `config == null` **and an interactive join is
pending** — whether from a scanned deeplink or an auto-routed create — the screen SHALL reduce to the
`JoiningEvent` family for that pending join (see capability `join-event`), which takes precedence over the
`creationStatus`-derived create layer. Only when `config` is present do the existing rungs (permission →
join → sync hero, see `sync-status-screen`) apply. The reduction MUST depend only on the latest source
values; the container's initial UI state SHALL be computed from the sources' current values at
construction.

#### Scenario: No config and no pending join shows the create input
- **WHEN** config is `null`, no join is pending, creation status is `Idle`, and permission is `GRANTED`, `DENIED`, or `NOT_DETERMINED`
- **THEN** the UI state is the create-input state, not a permission, join, or sync state

#### Scenario: A pending join takes precedence over the create layer
- **WHEN** config is `null` and an interactive join is pending (from a scan or an auto-routed create)
- **THEN** the UI state is the `JoiningEvent` family for that pending join, not the create-input or `CreatingEvent` state

#### Scenario: In-flight create shows the creating state
- **WHEN** config is `null`, no join is pending, and creation status is `InFlight`
- **THEN** the UI state is `UiState.CreatingEvent`

#### Scenario: Failed create shows the input with an inline error
- **WHEN** config is `null`, no join is pending, and creation status is `Failed(reason)`
- **THEN** the UI state is the create-input state carrying the inline error matching the reason

#### Scenario: Config present leaves the create layer
- **WHEN** config becomes present after a confirmed join
- **THEN** the reduction leaves the create layer and the existing permission/join/sync rungs apply

### Requirement: Create-event screen

When the UI state is the create layer, the status screen SHALL render a create-event screen composed
from `App*` components: a **host-framed hero** (a HOST AN EVENT eyebrow, the drawn app-mark badge, a
"Start an event" title, and one warm line), a **question heading** ("What's it called?") over an
`AppTextField` for the event name, an **event start-date section** beneath it that states the start's
consequence, a `PrimaryButton` labelled to create the event, and a passive hint that an event can also
be joined by scanning its QR code with the Camera. The Create action SHALL be disabled while the trimmed
name is empty, and the field SHALL accept at most 100 characters. While the UI state is `CreatingEvent`,
the screen SHALL show a preparing indicator where the form was, keeping the **identical** host hero in
the same place so the surface reads as the form settling rather than a new screen (no layout jump). The
create layer SHALL NOT show the leave action.

The **start-date section** SHALL render the currently-chosen start as a readable label with an **edit
affordance**, opening the design system's date/time picker (capability `design-system`), together with a
stated-consequence **note** that this start is the earliest cutoff any guest can pick — "Only photos
taken after this time are shared" (capability `photo-selection-policy`). It SHALL default to **now**, and
that default SHALL be **frozen at the moment the screen first composes** — it SHALL NOT be re-derived at
submit. The label is the screen's whole statement about what will be sent, so a value that silently
drifted between being displayed and being posted would make the screen lie. A start-date section SHALL
always carry a value; there is no unset state.

The start-date picker SHALL impose **no bounds**: a start may be chosen arbitrarily far in the past
**or** in the future. A future start is a supported case (creating an event ahead of time), and an early
start is how a host brings pre-existing photos into scope — including how a developer creates an event
whose contents reach back to a seeded, distant-past library.

#### Scenario: The host hero frames the create surface
- **WHEN** the create screen is shown
- **THEN** it leads with the HOST AN EVENT eyebrow, the app-mark badge, the "Start an event" title, and one warm line, above the name question

#### Scenario: The start section defaults to now, frozen at composition, and states its consequence
- **WHEN** the create screen first composes at `18:04` and the user then spends ten minutes typing a name
- **THEN** the start section still reads `18:04` (and `18:04` is the value posted, not the instant Create was tapped), beside a note that only photos after this time are shared

#### Scenario: Editing the start opens the picker and updates the label
- **WHEN** the user activates the start section's edit affordance and picks a date and time
- **THEN** the picker closes and the section's label shows the newly-picked start

#### Scenario: The start is unbounded in both directions
- **WHEN** the user picks a start years in the past, or one in the future
- **THEN** the picker accepts it and the section shows it, no bound being imposed

#### Scenario: Empty name disables Create
- **WHEN** the create screen is shown and the name field is empty or whitespace-only
- **THEN** the Create action is disabled

#### Scenario: A typed name enables Create
- **WHEN** the user types a non-empty name
- **THEN** the Create action is enabled and activating it invokes `EventCreator.create` with the
  trimmed name **and the chosen start date** through the container

#### Scenario: The name field caps at 100 characters
- **WHEN** the user attempts to enter more than 100 characters
- **THEN** the field holds at 100 characters

#### Scenario: The scan hint is present and passive
- **WHEN** the create screen is shown
- **THEN** it displays a passive "scan a QR to join" hint with no button

#### Scenario: Creating keeps the host hero and shows a preparing indicator
- **WHEN** the UI state is `CreatingEvent`
- **THEN** the screen keeps the identical host hero in place and shows a preparing indicator where the input was

### Requirement: Create screen owns the event-link intent and one inline error surface

The container SHALL expose an `onCreateEvent(name: String, startsAt: LocalDateTime)` intent that converts
the picked **local** date-time into the canonical cutoff string via the injected time source (capability
`photo-selection-policy` — the same `CutoffFormatter` the join surface already uses, so there is exactly one
origin of "now" and one local→UTC conversion in the app) and calls `EventCreator.create`. The container
SHALL retain the `onOpenUrl(raw: String)` intent that decodes an incoming event link via the
`event-link` decoder and, on success, provisions it (the QR-join path is unchanged).

The create screen SHALL render a single error surface as an **error banner above the Create action** —
never as a reddened name field. The name field is client-guarded on both knowable rules (empty → Create
disabled; over-length → the field caps at 100), so a returned failure is a **submission** failure, not
the current name being malformed; reddening the field would falsely blame the host's typing. The one
banner serves two causes: a `Failed(reason)` create error (sticky until the next create attempt) and a
transient, self-clearing invalid-link error surfaced when `onOpenUrl` receives a URL the decoder rejects
— exposed as the container host's own presentation-owned `transientError` read-model `StateFlow` (the
set-then-clear choreography lives in presentation; the untested shell renders the value verbatim and
decides nothing — spec `module-architecture`, "Commands cross one door": interaction state is
presentation-owned). An invalid link MUST NOT change persisted config.

#### Scenario: The container converts the local pick to the canonical shape
- **WHEN** `onCreateEvent` receives a local date-time from the screen
- **THEN** it converts it through the injected time source into `yyyy-MM-dd'T'HH:mm:ss'Z'` and passes
  that string to `EventCreator.create`, the screen never handling a cutoff string itself

#### Scenario: Create failure shows a sticky banner above the action, not a red field
- **WHEN** a create attempt fails and the reduction returns to the create-input state
- **THEN** the failure copy shows in the error banner above Create (not on the name field) and persists
  until the next create attempt (which re-enters `InFlight` and clears it)

#### Scenario: Invalid link flashes a transient banner and changes nothing
- **WHEN** `onOpenUrl` receives a URL the decoder rejects
- **THEN** a transient invalid-link error is surfaced in the same banner, persisted config is
  unchanged, and the error self-clears

#### Scenario: A valid event link still joins from the create screen
- **WHEN** `onOpenUrl` receives a structurally-valid `https://<link domain>/join#…` URL while the create screen is shown
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

### Requirement: Create performs no event fetch of its own

The create feature's only HTTP surface SHALL be the `POST /events` create client. It SHALL NOT carry a
`GET /events/:id` client of any kind: the create response already returns the event's name and
`startsAt`, and every details/name fetch — including the scan-path name fill the deleted
`EventMetadataSource` used to serve — goes through capability `join-event`'s single
`EventDirectory` client (see its "One details client" requirement).

#### Scenario: The capability exposes only the create call

- **WHEN** the capability's HTTP clients are inspected
- **THEN** the only route it calls is `POST /events`, and event details are obtained through
  the `EventDirectory` port (implemented by `:adapter:generic:app`'s `HttpEventDirectory`)

