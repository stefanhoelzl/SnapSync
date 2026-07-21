# event-creation Specification

## Purpose

The backend's **event registry**: `POST /events` mints a server-side event (high-entropy id, name,
creation time) and writes the marker object that makes the event *exist*, plus a metadata route that reports
whether a given event id does.

Before this capability an event was only a client-held UUID — the backend kept no state, possession of the id
was the whole capability, and a listing could not distinguish an empty event from one that was never created.
The marker is what every later event-scoped operation gates on: uploads, listings, and the leave cascade all
ask "does this event exist?" and get a real answer.

This capability is backend-only. The on-device flow that names an event and auto-joins it is
`event-creation-ui`.

Decision record: `changes/archive/2026-06-27-add-event-creation`.
## Requirements
### Requirement: Event creation route

The backend SHALL accept an HTTP `POST` at the path `/events` whose body is a JSON object containing a
`name` **and a `startsAt`**, and on success SHALL respond `201` with a JSON body
`{ eventId, name, createdAt, startsAt, endsAt, capacity }`. The endpoint SHALL be served by the same
Hono application as the upload and list endpoints, so it is available on every deployment target
without separate configuration. A request using any method other than `POST` on `/events` (or a path
that does not match) SHALL yield `404`.

`createdAt` and `startsAt` are **distinct facts** and SHALL NOT be conflated: `createdAt` is
server-minted wall-clock at the moment the marker is written, whereas `startsAt` is the host's statement
of when the event began, supplied by the client and honored verbatim. `endsAt` and `capacity` are
**server-resolved limits** (capability `event-limits`): the client supplies neither — `endsAt` is
computed as `startsAt` plus the configured event duration, and `capacity` is the configured device
capacity, both resolved at mint time.

#### Scenario: Valid create returns the new event

- **WHEN** a `POST /events` arrives with body `{ "name": "Birthday", "startsAt": "2026-07-14T18:00:00Z" }`
- **THEN** the endpoint responds `201` with a JSON body containing `eventId`, `name` (`"Birthday"`),
  `createdAt`, `startsAt` (`"2026-07-14T18:00:00Z"`), `endsAt` (`startsAt` plus the configured
  duration), and `capacity` (the configured device capacity)

#### Scenario: createdAt and startsAt are independent

- **WHEN** a `POST /events` supplies a `startsAt` that differs from the server's current time
- **THEN** the response carries the server-minted `createdAt` **and** the client's `startsAt`
  unchanged, as two separate fields

#### Scenario: Client-supplied limits are ignored

- **WHEN** a `POST /events` body includes an `endsAt` or `capacity` field alongside `name` and
  `startsAt`
- **THEN** the endpoint ignores them and stamps the server-resolved values

#### Scenario: Wrong method on the create path

- **WHEN** a `GET` (or any non-`POST`) is sent to `/events`
- **THEN** the endpoint responds `404` and makes no upstream request

### Requirement: Server-minted event id

The endpoint SHALL generate the `eventId` itself using a UUID generator (`crypto.randomUUID()`),
producing a canonical `8-4-4-4-12` UUID. The endpoint SHALL NOT accept or honor a client-supplied
event id in the request. The returned `eventId` SHALL match the same UUID pattern the upload and list
routes validate.

#### Scenario: Event id is server-generated and canonical

- **WHEN** a valid `POST /events` is processed
- **THEN** the returned `eventId` is a canonical UUID minted by the server

#### Scenario: Client-supplied id is ignored

- **WHEN** a `POST /events` body includes an `eventId` or `id` field alongside `name`
- **THEN** the endpoint ignores it and returns a freshly server-minted `eventId`

### Requirement: Event name validation

The endpoint SHALL parse the request body as JSON and validate `name`: it SHALL trim surrounding
whitespace, require the trimmed value to be non-empty, and require its length to be at most 100
characters. A request whose body is not valid JSON, lacks a `name`, or whose trimmed `name` is empty
or longer than 100 characters SHALL yield `400` and SHALL NOT make any upstream write. The trimmed
value SHALL be the name that is stored and returned.

#### Scenario: Empty or whitespace-only name rejected

- **WHEN** a `POST /events` body has `name` absent, empty, or only whitespace
- **THEN** the endpoint responds `400` and writes nothing upstream

#### Scenario: Over-long name rejected

- **WHEN** a `POST /events` body has a `name` whose trimmed length exceeds 100 characters
- **THEN** the endpoint responds `400` and writes nothing upstream

#### Scenario: Non-JSON body rejected

- **WHEN** a `POST /events` body is not valid JSON
- **THEN** the endpoint responds `400` and writes nothing upstream

#### Scenario: Surrounding whitespace trimmed

- **WHEN** a `POST /events` body has `name` `"  Birthday  "`
- **THEN** the stored and returned `name` is `"Birthday"`

### Requirement: Event start-date validation

The endpoint SHALL require a `startsAt` field on the `POST /events` body and SHALL validate it against
the **canonical cutoff form** `yyyy-MM-dd'T'HH:mm:ss'Z'` — UTC (`Z`), second precision, no timezone
offset, no fractional seconds. A request whose `startsAt` is absent, is not a string, is the empty
string, or does not match that exact shape SHALL yield `400` and SHALL NOT make any upstream write.

The canonical form is required **at the boundary**, rather than accepted loosely and normalized, because
`startsAt` is consumed directly as a capture-date cutoff: it is compared lexicographically against
PhotoKit `creationDate` and parsed by a bare `NSISO8601DateFormatter` (capability `photo-selection-policy`).
A marker that stores the canonical form is usable as a cutoff with **no** client-side normalization —
unlike `createdAt`, which the backend mints with `new Date().toISOString()` and which therefore always
carries milliseconds.

`startsAt` SHALL NOT be bounded: an event MAY start arbitrarily far in the past **or** in the future.
A future `startsAt` is meaningful — it is how an event is created ahead of time, and it is what the app
renders as its not-started state.

Unboundedness interacts with the event lifetime (capability `event-limits`,
`endsAt = startsAt + duration`), in opposite directions. A `startsAt` more than the duration plus grace
in the past mints an event that is **already expired** — reaped on the first touch, self-defusing —
while a within-window past start is a feature (an event created mid-trip). A far-**future** `startsAt`
extends the marker's total life to `startsAt + duration`, beyond the duration-from-now an event minted
today carries. That extension is accepted while creation is attestation-gated and free; it is
re-examined when duration becomes creator-chosen under paid events.

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

### Requirement: Event marker registry

An event SHALL exist in the registry exactly when the object `/events/<eventId>/metadata.json` is
present in the storage zone (this supersedes the prior `events/<eventId>.json` key). On create the
endpoint SHALL write this marker via a bunny native Storage `PUT` with the `AccessKey` header from
configuration and `Content-Type: application/json`, whose body is the JSON `{ eventId, name,
createdAt, startsAt, endsAt, capacity }` (`createdAt` an ISO-8601 timestamp; `startsAt` and `endsAt`
the canonical cutoff form; `capacity` a positive integer — the limit fields stamped per capability
`event-limits`). The marker SHALL live under the event's own `/events/<eventId>/` prefix, alongside
the per-event device manifests at `/events/<eventId>/devices/<deviceId>.json`. Because an `eventId` is
a UUID, the marker key `/events/<eventId>/metadata.json`, the device-manifest keys
`/events/<eventId>/devices/<deviceId>.json`, and the device-global byte store
`/files/devices/<deviceId>/…` are mutually disjoint and never collide.

The marker SHALL be **write-once**: there is no route by which a stored `startsAt` (or any other marker
field) can be changed after creation. The backend has no owner field and no authentication, so a
mutation route would let anyone holding the event id retroactively widen every future joiner's default
scope — or extend an event's own limits. The lifecycle (capability `event-limits`) is recomputed from
the stored fields on every read precisely so that no rewrite is ever needed.

#### Scenario: Create writes the marker

- **WHEN** a valid `POST /events` is processed
- **THEN** the endpoint issues a bunny native Storage `PUT` to `/events/<eventId>/metadata.json`
  carrying the `AccessKey` header and a JSON body of
  `{ eventId, name, createdAt, startsAt, endsAt, capacity }`

#### Scenario: No route mutates an existing marker

- **WHEN** the backend's routes are enumerated
- **THEN** none of them rewrites `/events/<eventId>/metadata.json` for an event that already exists, so
  `startsAt`, `endsAt`, and `capacity` are immutable after creation

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
- **THEN** the endpoint responds `201` with `{ eventId, name, createdAt, startsAt, endsAt, capacity }`

#### Scenario: Marker store fails

- **WHEN** the marker `PUT` returns an error, times out, or aborts
- **THEN** the endpoint responds `502` and reports no created event

### Requirement: Event metadata and existence route

The backend SHALL accept an HTTP `GET` at the path `/events/<eventId>` (the literal label `events`
required) and return the event's metadata. `eventId` MUST match a UUID pattern; a matched request
whose `eventId` is not a UUID SHALL yield `400` and make no upstream request. The endpoint SHALL read
the marker `/events/<eventId>/metadata.json` and, when present and the event is not expired, respond
`200` with its contents `{ eventId, name, createdAt, startsAt, endsAt, capacity }`; when the marker is
absent, respond `404`. A genuine upstream failure reading the marker (not a `404`) SHALL be surfaced
as `502`. This route is the canonical existence check; the same `/events/<eventId>/metadata.json` read
backs the existence gate the device-manifest write enforces.

The route SHALL pass the event-limits lifecycle check (capability `event-limits`) like every
event-scoped route: a marker whose grace period has elapsed — including a legacy marker missing
`endsAt`/`capacity` — is never served; it is reaped on touch and answered `404`. This supersedes the
former read-time `startsAt` synthesis for legacy markers: a marker old enough to lack `startsAt` also
lacks `endsAt`, so it is expired by definition and reaped rather than patched. The response's
`startsAt`, `endsAt`, and `capacity` are therefore **always present** on a `200`, so no client
carries a nullable field and every downstream type stays total.

#### Scenario: Existing event returns metadata

- **WHEN** a `GET /events/<uuid>` arrives for an event whose marker exists, carries its limit fields,
  and is not expired
- **THEN** the endpoint reads `/events/<uuid>/metadata.json` and responds `200` with
  `{ eventId, name, createdAt, startsAt, endsAt, capacity }`

#### Scenario: A legacy marker is reaped, not patched

- **WHEN** a `GET /events/<uuid>` reads a marker written before the limit fields existed
- **THEN** the event is treated as expired (capability `event-limits`): reaped on this touch and
  answered `404` — no field is synthesized and the stored object is not patched

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

### Requirement: Event routes require a device token

`POST /events` SHALL require a valid device token (capability `device-attestation`) in
`Authorization: Bearer`. A request without one SHALL be rejected with `401`, and no event marker SHALL be
written. Gating creation is the point: an ungated `POST /events` lets a stranger mint unbounded event
markers in the storage zone.

`GET /events/<eventId>` (the metadata/existence read) SHALL **not** require a token: a request that
carries a valid token and a request that carries none SHALL both be served the marker's metadata (or
`404`). The read is authorized by `eventId`-possession alone — the no-app download page (capability
`web-event-download`) fetches the event name over this route from a browser that holds no attestation.
Existence-probing by a tokenless caller is an accepted consequence of opening this read (decision record:
`changes/archive/2026-07-21-web-event-download`).

Attestation is the **only** creation gate today — creation is free for every attested device. A future
paid-events change would attach its payment/authorization check **on this route**: creation is the one
moment an event's tier is decided, and the tier's substance (capacity, duration) is already stamped at
mint from values that can become creator-chosen with no enforcement change (capability `event-limits`).
Nothing else in the API is shaped by payment.

#### Scenario: Unauthenticated creation is refused

- **WHEN** `POST /events` arrives with no valid token
- **THEN** the endpoint responds `401` and writes no marker

#### Scenario: Unauthenticated metadata read is served

- **WHEN** `GET /events/<eventId>` arrives with no valid token
- **THEN** the endpoint reads the marker and responds with its metadata (`200`) or `404` — not `401`

#### Scenario: An attested device creates an event unchanged

- **WHEN** `POST /events` carries a valid token and a valid name
- **THEN** an event is minted and returned exactly as before

