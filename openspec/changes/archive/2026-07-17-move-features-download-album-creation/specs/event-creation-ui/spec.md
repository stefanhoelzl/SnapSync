# event-creation-ui — delta for move-features-download-album-creation

## MODIFIED Requirements

### Requirement: Create-event seams and status model

The create feature (`:domain`'s `feature/creation` zone, package `app.snapsync.feature.creation`) SHALL define a command port and a
state port, consumed separately by the presentation container (mirroring the
`PhotoAccessRequester` / `PhotoAccessStatusSource` split):

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

### Requirement: Create performs no event fetch of its own

The create feature's only HTTP surface SHALL be the `POST /events` create client. It SHALL NOT carry a
`GET /events/:id` client of any kind: the create response already returns the event's name and
`startsAt`, and every details/name fetch — including the scan-path name fill the deleted
`EventMetadataSource` used to serve — goes through capability `join-event`'s single
`EventDirectory` client (see its "One details client" requirement).

#### Scenario: The capability exposes only the create call

- **WHEN** the capability's HTTP clients are inspected
- **THEN** the only route it calls is `POST /events`, and event details are obtained through
  the `EventDirectory` port (implemented by `:adapter:generic`'s `HttpEventDirectory`)

