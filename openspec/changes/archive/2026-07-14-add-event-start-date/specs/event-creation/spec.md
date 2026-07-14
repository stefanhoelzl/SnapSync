## ADDED Requirements

### Requirement: Event start-date validation

The endpoint SHALL require a `startsAt` field on the `POST /events` body and SHALL validate it against
the **canonical cutoff form** `yyyy-MM-dd'T'HH:mm:ss'Z'` — UTC (`Z`), second precision, no timezone
offset, no fractional seconds. A request whose `startsAt` is absent, is not a string, is the empty
string, or does not match that exact shape SHALL yield `400` and SHALL NOT make any upstream write.

The canonical form is required **at the boundary**, rather than accepted loosely and normalized, because
`startsAt` is consumed directly as a capture-date cutoff: it is compared lexicographically against
PhotoKit `creationDate` and parsed by a bare `NSISO8601DateFormatter` (capability `photo-date-cutoff`).
A marker that stores the canonical form is usable as a cutoff with **no** client-side normalization —
unlike `createdAt`, which the backend mints with `new Date().toISOString()` and which therefore always
carries milliseconds.

`startsAt` SHALL NOT be bounded: an event MAY start arbitrarily far in the past **or** in the future.
A future `startsAt` is meaningful — it is how an event is created ahead of time, and it is what the app
renders as its not-started state.

The value SHALL be stored and returned verbatim.

#### Scenario: A canonical startsAt is accepted and echoed
- **WHEN** a `POST /events` arrives with body `{ "name": "Party", "startsAt": "2026-07-14T18:00:00Z" }`
- **THEN** the endpoint responds `201` and the stored and returned `startsAt` is exactly
  `2026-07-14T18:00:00Z`

#### Scenario: A missing startsAt is rejected
- **WHEN** a `POST /events` body carries a valid `name` but no `startsAt`
- **THEN** the endpoint responds `400` and writes nothing upstream

#### Scenario: A non-canonical startsAt is rejected
- **WHEN** a `POST /events` body carries a `startsAt` bearing fractional seconds
  (`2026-07-14T18:00:00.000Z`), a timezone offset (`2026-07-14T18:00:00+02:00`), a missing `Z`, or a
  non-timestamp string
- **THEN** the endpoint responds `400` and writes nothing upstream

#### Scenario: An empty startsAt is rejected
- **WHEN** a `POST /events` body carries `startsAt` as the empty string
- **THEN** the endpoint responds `400` and writes nothing upstream, because an empty cutoff admits every
  asset (`creationDate >= ""` holds for all) and would silently restore whole-library scope

#### Scenario: A future startsAt is accepted
- **WHEN** a `POST /events` carries a `startsAt` later than the server's current time
- **THEN** the endpoint responds `201` and stores it unchanged, the event being created ahead of time

#### Scenario: A far-past startsAt is accepted
- **WHEN** a `POST /events` carries a `startsAt` years in the past
- **THEN** the endpoint responds `201` and stores it unchanged — the value is a floor on the event's
  contents, and bounding it is not the backend's concern

## MODIFIED Requirements

### Requirement: Event creation route

The backend SHALL accept an HTTP `POST` at the path `/events` whose body is a JSON object containing a
`name` **and a `startsAt`**, and on success SHALL respond `201` with a JSON body
`{ eventId, name, createdAt, startsAt }`. The endpoint SHALL be served by the same Hono application as
the upload and list endpoints, so it is available on every deployment target without separate
configuration. A request using any method other than `POST` on `/events` (or a path that does not match)
SHALL yield `404`.

`createdAt` and `startsAt` are **distinct facts** and SHALL NOT be conflated: `createdAt` is
server-minted wall-clock at the moment the marker is written, whereas `startsAt` is the host's statement
of when the event began, supplied by the client and honored verbatim.

#### Scenario: Valid create returns the new event

- **WHEN** a `POST /events` arrives with body `{ "name": "Birthday", "startsAt": "2026-07-14T18:00:00Z" }`
- **THEN** the endpoint responds `201` with a JSON body containing `eventId`, `name` (`"Birthday"`),
  `createdAt`, and `startsAt` (`"2026-07-14T18:00:00Z"`)

#### Scenario: createdAt and startsAt are independent

- **WHEN** a `POST /events` supplies a `startsAt` that differs from the server's current time
- **THEN** the response carries the server-minted `createdAt` **and** the client's `startsAt`
  unchanged, as two separate fields

#### Scenario: Wrong method on the create path

- **WHEN** a `GET` (or any non-`POST`) is sent to `/events`
- **THEN** the endpoint responds `404` and makes no upstream request

### Requirement: Event marker registry

An event SHALL exist in the registry exactly when the object `/events/<eventId>/metadata.json` is
present in the storage zone (this supersedes the prior `events/<eventId>.json` key). On create the
endpoint SHALL write this marker via a bunny native Storage `PUT` with the `AccessKey` header from
configuration and `Content-Type: application/json`, whose body is the JSON `{ eventId, name,
createdAt, startsAt }` (`createdAt` an ISO-8601 timestamp; `startsAt` the canonical cutoff form). The
marker SHALL live under the event's own `/events/<eventId>/` prefix, alongside the per-event device
manifests at `/events/<eventId>/devices/<deviceId>.json`. Because an `eventId` is a UUID, the marker key
`/events/<eventId>/metadata.json`, the device-manifest keys `/events/<eventId>/devices/<deviceId>.json`,
and the device-global byte store `/files/devices/<deviceId>/…` are mutually disjoint and never collide.

The marker SHALL be **write-once**: there is no route by which a stored `startsAt` (or any other marker
field) can be changed after creation. The backend has no owner field and no authentication, so a
mutation route would let anyone holding the event id retroactively widen every future joiner's default
scope.

#### Scenario: Create writes the marker

- **WHEN** a valid `POST /events` is processed
- **THEN** the endpoint issues a bunny native Storage `PUT` to `/events/<eventId>/metadata.json`
  carrying the `AccessKey` header and a JSON body of `{ eventId, name, createdAt, startsAt }`

#### Scenario: No route mutates an existing marker

- **WHEN** the backend's routes are enumerated
- **THEN** none of them rewrites `/events/<eventId>/metadata.json` for an event that already exists, so
  `startsAt` is immutable after creation

#### Scenario: Marker is disjoint from manifests and the byte store

- **WHEN** the marker `/events/<eventId>/metadata.json` exists, a device manifest is stored at
  `/events/<eventId>/devices/<deviceId>.json`, and bytes are stored under `/files/devices/<deviceId>/…`
- **THEN** the three keys are distinct and never collide (an `eventId` is a UUID, so the literal
  `metadata.json` and `devices/` segments never alias a device id or a stored filename)

### Requirement: Faithful create outcome

The endpoint SHALL respond `201` **only** when bunny confirms the marker was stored. Any upstream
error, timeout, or aborted write SHALL be surfaced as `502`, and the endpoint SHALL NEVER respond
`201` for an unconfirmed marker write. The `AccessKey` and the bunny account API key SHALL NOT be
exposed in any response.

#### Scenario: Marker store confirmed

- **WHEN** bunny confirms the marker `PUT`
- **THEN** the endpoint responds `201` with `{ eventId, name, createdAt, startsAt }`

#### Scenario: Marker store fails

- **WHEN** the marker `PUT` returns an error, times out, or aborts
- **THEN** the endpoint responds `502` and reports no created event

### Requirement: Event metadata and existence route

The backend SHALL accept an HTTP `GET` at the path `/events/<eventId>` (the literal label `events`
required) and return the event's metadata. `eventId` MUST match a UUID pattern; a matched request
whose `eventId` is not a UUID SHALL yield `400` and make no upstream request. The endpoint SHALL read
the marker `/events/<eventId>/metadata.json` and, when present, respond `200` with its contents
`{ eventId, name, createdAt, startsAt }`; when the marker is absent, respond `404`. A genuine upstream
failure reading the marker (not a `404`) SHALL be surfaced as `502`. This route is the canonical
existence check; the same `/events/<eventId>/metadata.json` read backs the existence gate the
device-manifest write enforces.

A marker written **before** `startsAt` existed SHALL be patched **at read**: when the stored marker
carries no `startsAt`, the endpoint SHALL respond with `startsAt` set to the marker's `createdAt`. The
response's `startsAt` is therefore **always present**, so no client carries a nullable start date and
every downstream type stays total. The synthesis happens at read only — the stored object is not
rewritten (the marker is write-once).

#### Scenario: Existing event returns metadata

- **WHEN** a `GET /events/<uuid>` arrives for an event whose marker exists and carries a `startsAt`
- **THEN** the endpoint reads `/events/<uuid>/metadata.json` and responds `200` with
  `{ eventId, name, createdAt, startsAt }`

#### Scenario: A legacy marker's startsAt is synthesized from createdAt

- **WHEN** a `GET /events/<uuid>` reads a marker written before this change, carrying
  `{ eventId, name, createdAt }` and no `startsAt`
- **THEN** the endpoint responds `200` with `startsAt` equal to that marker's `createdAt`, and does not
  rewrite the stored object

#### Scenario: Unknown event yields 404

- **WHEN** a `GET /events/<uuid>` arrives for an event whose marker `/events/<uuid>/metadata.json`
  is absent
- **THEN** the endpoint responds `404`

#### Scenario: Non-UUID event id rejected

- **WHEN** the `eventId` segment of `GET /events/<id>` is not a UUID
- **THEN** the endpoint responds `400` and makes no upstream request

#### Scenario: Upstream failure surfaced

- **WHEN** reading the marker `/events/<uuid>/metadata.json` returns a non-404 upstream error or
  times out
- **THEN** the endpoint responds `502`

#### Scenario: Existence gate reads the same marker

- **WHEN** the device-manifest write checks that its target event exists
- **THEN** it reads `/events/<eventId>/metadata.json` (the canonical marker), proceeding only when
  it is present
