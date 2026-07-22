# event-creation-ui Specification

## MODIFIED Requirements

### Requirement: Create mints an event then provisions it like a scanned QR

The capability SHALL provide a create use-case that, on `create(name, startsAt, endsAt)`, sets
`creationStatus` to `InFlight`, calls the backend `POST /events` with the trimmed name **and the event's
date range** (`startsAt`, `endsAt`) via an injected client, and on a
`201 { eventId, name, createdAt, startsAt, endsAt }` **routes the returned `eventId` into the existing
pending-join gate** — the same gate a scanned deeplink opens (see capability `join-event`) — rather than
provisioning the config directly. The route SHALL be an **auto-routed but not auto-confirmed** pending
join: the creator is taken to the join surface (which fetches the just-minted event's details, shows its
name, and offers the capture-date **range** row defaulting to the full event window
`[startsAt, endsAt]`) and completes the **same** confirm-to-enroll-and-provision flow every joiner uses.

The creator is therefore subject to the **same window as every other member** (capability
`photo-selection-policy`): the range they just declared is the floor and ceiling on their own capture-date
range too. This is not a special case — it falls out of create and scan converging on one gate.

Because the `POST` has already minted the event, the gate holds a **real** `eventId`, performs a real
details fetch, and enrolls normally; the config is saved (with name, `startsAt`, `endsAt`, and the clamped
capture-date range) by the gate's provision step, not by the create use-case — so the minted `endsAt`
flows through the auto-join into the persisted membership. On a successful mint `creationStatus` SHALL
return to `Idle` (the pending join drives the reduction from there); on any failure (non-2xx, transport,
or parse) it SHALL set `creationStatus` to `Failed(reason)`, SHALL NOT open the gate, and SHALL save no
config. A cancelled or abandoned join after a successful mint SHALL leave the minted event as a harmless
member-less marker (no rollback). The use-case MUST NOT inspect `PermissionStatus`.

#### Scenario: Successful create opens the join gate for the minted event
- **WHEN** `create("My Party", "2026-07-14T18:00:00Z", "2026-07-21T23:00:00Z")` is invoked and the backend returns `201` with `{eventId, name, createdAt, startsAt, endsAt}`
- **THEN** the returned `eventId` is routed into the pending-join gate, the join surface loads the event and shows the capture-date range defaulting to the full event window, and the config is provisioned only when the creator confirms

#### Scenario: The minted endsAt provisions into the auto-join
- **WHEN** the creator confirms the auto-routed join for the minted event
- **THEN** the persisted `EventConfig` carries the minted `endsAt` as the event window ceiling, so the
  member's upper bound and the "Event ended" marker both read the host's declared end

#### Scenario: The creator is bound by the window they set
- **WHEN** the creator sets a date range, is routed into the gate, and confirms
- **THEN** the persisted lower bound is `max(chosen, startsAt)` and the persisted upper bound is
  `min(chosen, endsAt)` exactly as for any other joiner — the creator receives no exemption from the
  window

#### Scenario: Create ignores permission
- **WHEN** `create(name, startsAt, endsAt)` is invoked while photo permission is `NOT_DETERMINED` or `DENIED`
- **THEN** the create proceeds (mints + opens the gate) without inspecting permission, and the missing
  permission surfaces afterward via the joined-layer `NeedsAccess` status line (per `sync-status-screen`)

#### Scenario: A failed create leaves config untouched and opens no gate
- **WHEN** `create(name, startsAt, endsAt)` is invoked and the backend request fails (non-2xx, transport, or parse)
- **THEN** `creationStatus` becomes `Failed(reason)`, config is unchanged, and no pending join is opened

#### Scenario: A cancelled join after a mint leaves a harmless marker
- **WHEN** the mint succeeds, the gate opens, and the creator cancels before confirming
- **THEN** no config is saved, the device is not enrolled, and the minted event remains as a member-less marker with no rollback

### Requirement: Create-event screen

When the UI state is the create layer, the status screen SHALL render a create-event screen composed
from `App*` components: a **host-framed hero** (a HOST AN EVENT eyebrow, the drawn app-mark badge, a
"Start an event" title, and one warm line), a **question heading** ("What's it called?") over an
`AppTextField` for the event name, an **event date-range section** beneath it that states the range's
consequence, a `PrimaryButton` labelled to create the event, and a passive hint that an event can also
be joined by scanning its QR code with the Camera. The Create action SHALL be disabled while the trimmed
name is empty **or** the chosen range is not `start < end`, and the field SHALL accept at most 100
characters. While the UI state is `CreatingEvent`, the screen SHALL show a preparing indicator where the
form was, keeping the **identical** host hero in the same place so the surface reads as the form settling
rather than a new screen (no layout jump). The create layer SHALL NOT show the leave action.

The **date-range section** SHALL render the currently-chosen `[start, end]` range as a readable label
with an **edit affordance**, opening the design system's **dual-handle datetime range picker** (capability
`design-system`), together with a stated-consequence **note** that this range is the event's window —
"Only photos taken during this window are shared" (capability `photo-selection-policy`) — and a **live
humanized duration hint** ("Event lasts 5 days") that updates as the range changes. It SHALL default to
`[now, now + 1 day]`, and that default SHALL be **frozen at the moment the screen first composes** — it
SHALL NOT be re-derived at submit. The label is the screen's whole statement about what will be sent, so a
value that silently drifted between being displayed and being posted would make the screen lie. A
date-range section SHALL always carry a range; there is no unset state.

The range picker SHALL impose **no bounds** other than `start < end`: a start or end may be chosen
arbitrarily far in the past **or** in the future, with **no** duration cap. A future range is a supported
case (creating an event ahead of time), and an early start is how a host brings pre-existing photos into
scope — including how a developer creates an event whose contents reach back to a seeded, distant-past
library.

#### Scenario: The host hero frames the create surface
- **WHEN** the create screen is shown
- **THEN** it leads with the HOST AN EVENT eyebrow, the app-mark badge, the "Start an event" title, and one warm line, above the name question

#### Scenario: The range defaults to now .. now+1d, frozen at composition, and states its consequence
- **WHEN** the create screen first composes at `18:04` and the user then spends ten minutes typing a name
- **THEN** the date-range section still reads `[18:04, tomorrow 18:04]` (and that range is what is posted, not one re-derived when Create was tapped), beside a note that only photos taken during the window are shared

#### Scenario: The duration hint updates live as the range changes
- **WHEN** the user edits the range so it spans five days
- **THEN** the section shows a humanized duration hint reading "Event lasts 5 days"

#### Scenario: Editing the range opens the dual-handle picker and updates the label
- **WHEN** the user activates the date-range section's edit affordance and picks a start and end
- **THEN** the dual-handle range picker closes and the section's label shows the newly-picked range

#### Scenario: The range is unbounded in both directions with only start < end
- **WHEN** the user picks a start years in the past, or an end years in the future
- **THEN** the picker accepts it and the section shows it, no bound being imposed beyond `start < end`

#### Scenario: An invalid range disables Create
- **WHEN** the chosen range has `start >= end`
- **THEN** the Create action is disabled

#### Scenario: Empty name disables Create
- **WHEN** the create screen is shown and the name field is empty or whitespace-only
- **THEN** the Create action is disabled

#### Scenario: A typed name enables Create
- **WHEN** the user types a non-empty name and the chosen range satisfies `start < end`
- **THEN** the Create action is enabled and activating it invokes `EventCreator.create` with the
  trimmed name **and the chosen start and end dates** through the container

#### Scenario: The name field caps at 100 characters
- **WHEN** the user attempts to enter more than 100 characters
- **THEN** the field holds at 100 characters

#### Scenario: The scan hint is present and passive
- **WHEN** the create screen is shown
- **THEN** it displays a passive "scan a QR to join" hint with no button

#### Scenario: Creating keeps the host hero and shows a preparing indicator
- **WHEN** the UI state is `CreatingEvent`
- **THEN** the screen keeps the identical host hero in place and shows a preparing indicator where the input was

### Requirement: Create-event seams and status model

The create feature (`:domain`'s `feature/creation` zone, package `app.snapsync.feature.creation`) SHALL define a command port and a
state port. The state port is consumed by the presentation container directly (reads observe feature
read-models); the command port is consumed by presentation **only through the injected `flow/`
user-tap command bundle** (`UserCommands.create`, built in `compose/` over the feature's
`EventCreator` — spec `module-architecture`, "Commands cross one door"): the container SHALL NOT
take or reference an `EventCreator` itself.

- `EventCreator` (command port): `fun create(name: String, startsAt: String, endsAt: String)` —
  fire-and-forget. It MUST NOT return a value and MUST NOT suspend; the outcome arrives exclusively via
  `CreationStatusSource`. Both `startsAt` and `endsAt` SHALL be **canonical cutoff strings**
  (`yyyy-MM-dd'T'HH:mm:ss'Z'`, capability `photo-selection-policy`), already converted from the user's
  local picks by the caller — so the capability needs no clock, no timezone, and no dependency on the
  cutoff codec. `endsAt` is the event's window ceiling and its server lifetime (capability `event-limits`);
  the caller SHALL pass a range satisfying `startsAt < endsAt`.
- `CreationStatusSource` (state port): exposes `creationStatus: StateFlow<CreationStatus>`, a
  level-triggered holder whose current value is always available synchronously; every emission is the
  whole truth.

`CreationStatus` SHALL have exactly three shapes: `Idle`, `InFlight`, and `Failed(reason)` where
`reason` distinguishes an invalid-name rejection from a transient/server failure (so the screen can
show the right copy). There SHALL be no `Succeeded` value — a successful create provisions config,
which moves the reduction off the create layer.

No new failure reason SHALL be added for an invalid `startsAt` **or `endsAt`**. The app always sends
canonical values from a picker that also enforces `startsAt < endsAt`, so a date-shaped `400` (bad
`startsAt`, bad `endsAt`, or `startsAt >= endsAt`) is unreachable from this client; inventing user-facing
copy for a state no user can reach would be dead surface. The existing single `400` → invalid-name mapping
stands.

#### Scenario: Outcome arrives only via the state port
- **WHEN** `EventCreator.create(name, startsAt, endsAt)` is invoked
- **THEN** `create` itself communicates nothing, and the in-flight and terminal outcomes are observed
  as emissions of `CreationStatusSource.creationStatus`

#### Scenario: The creator receives an already-canonical date range
- **WHEN** `EventCreator.create` is invoked
- **THEN** its `startsAt` and `endsAt` arguments are already in the canonical `yyyy-MM-dd'T'HH:mm:ss'Z'`
  shape with `startsAt < endsAt`, the capability performing no clock read and no timezone conversion of
  its own

#### Scenario: Status models in-flight and failure but never success
- **WHEN** the creation status is inspected across a create attempt
- **THEN** it holds `InFlight` during the request and `Failed(reason)` on failure, and a success is
  represented by config becoming present (not by a `CreationStatus` value)

### Requirement: HTTP event creator over an injected client

The capability SHALL provide an `EventCreator` HTTP implementation over an injected Ktor `HttpClient`
and a host string (the engine and host are supplied by the composition root, keeping the impl
platform-neutral and testable with `MockEngine`), mirroring `HttpEventFilesSource`. It SHALL
`POST <host>/events` (HTTPS, default ATS) with a JSON body
`{ "name": <trimmed name>, "startsAt": <canonical start date>, "endsAt": <canonical end date> }`, parse a
`201` body into `{ eventId, name, createdAt, startsAt, endsAt }`, and map any non-2xx, transport, or parse
error to a failed result the use-case turns into `Failed`. A `400` SHALL map to the invalid-name reason;
any other non-2xx or transport/parse error SHALL map to the transient/server reason. Both `startsAt` and
`endsAt` SHALL be sent **verbatim** — the client SHALL NOT reformat, re-zone, or re-derive them, the
canonical shape being the caller's contract.

#### Scenario: Create posts the name and the date range
- **WHEN** the client is asked to create `"My Party"` from `2026-07-14T18:00:00Z` to `2026-07-21T23:00:00Z`
- **THEN** the request body is exactly `{"name":"My Party","startsAt":"2026-07-14T18:00:00Z","endsAt":"2026-07-21T23:00:00Z"}`

#### Scenario: Create parses the event
- **WHEN** the server responds `201` with `{eventId, name, createdAt, startsAt, endsAt}`
- **THEN** the parsed `eventId` is returned for provisioning

#### Scenario: A 400 maps to the invalid-name reason
- **WHEN** the server responds `400` to the create request
- **THEN** the result is a failure carrying the invalid-name reason

#### Scenario: A 502 or transport error maps to the transient reason
- **WHEN** the server responds `502`, or the request fails to reach the server, or the body does not parse
- **THEN** the result is a failure carrying the transient/server reason
