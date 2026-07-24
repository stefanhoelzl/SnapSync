## MODIFIED Requirements

### Requirement: Event end-date validation

The endpoint SHALL accept an **optional** `endsAt` field on the `POST /events` body. When present it
SHALL be validated against the **canonical cutoff form** `yyyy-MM-dd'T'HH:mm:ss'Z'` — UTC (`Z`), second
precision, no timezone offset, no fractional seconds — SHALL name a **real, round-tripping instant** (the
same instant check `startsAt` receives, rejecting e.g. rolled-over components), SHALL be **strictly
after** `startsAt` (`startsAt < endsAt`), and SHALL be no more than the configured **window maximum**
after it (`endsAt - startsAt <= windowMax`, initial value 30 days; capability `event-limits`). A request
whose `endsAt` is present but is not a string, is the empty string, does not match that exact shape, is
not a real instant, is not strictly after `startsAt`, or exceeds the window maximum SHALL yield `400` and
SHALL NOT make any upstream write.

The canonical form is required **at the boundary** for the same reason as `startsAt`: `endsAt` is
consumed directly as a capture-date ceiling, compared lexicographically and parsed without normalization
(capability `photo-selection-policy`).

The window maximum is a **hard bound, not a pricing lever**. It exists because the event's storage
lifetime is independently bounded (capability `event-limits`): a window longer than the lifetime would
declare captures eligible for upload into an event that no longer exists by then, and a photo that
uploads into nothing is exactly the silent loss the selection policy is built to avoid. The only future
paid-tier lever is `capacity`.

An **absent** `endsAt` is valid and SHALL trigger the fallback `endsAt = startsAt + windowMax`, so an
un-updated client that sends only `startsAt` keeps working. A present, valid `endsAt` SHALL be stored and
returned verbatim.

#### Scenario: A canonical endsAt within the cap is accepted and echoed

- **WHEN** a `POST /events` arrives with body `{ "name": "Party", "startsAt": "2026-07-14T18:00:00Z",
  "endsAt": "2026-07-21T23:00:00Z" }`
- **THEN** the endpoint responds `201` and the stored and returned `endsAt` is exactly
  `2026-07-21T23:00:00Z`

#### Scenario: An absent endsAt is accepted and triggers the fallback

- **WHEN** a `POST /events` body carries a valid `name` and `startsAt` but no `endsAt`
- **THEN** the endpoint responds `201` and stamps `endsAt = startsAt + windowMax`

#### Scenario: A window longer than the maximum is rejected

- **WHEN** a `POST /events` body carries an `endsAt` more than the configured window maximum after
  `startsAt` (for example 31 days, against a 30-day maximum)
- **THEN** the endpoint responds `400` and writes nothing upstream

#### Scenario: A window exactly at the maximum is accepted

- **WHEN** a `POST /events` body carries an `endsAt` exactly the configured window maximum after
  `startsAt`
- **THEN** the endpoint responds `201` and stores it unchanged

#### Scenario: A non-canonical endsAt is rejected

- **WHEN** a `POST /events` body carries an `endsAt` bearing fractional seconds
  (`2026-07-21T23:00:00.000Z`), a timezone offset (`2026-07-21T23:00:00+02:00`), a missing `Z`, or a
  non-timestamp string
- **THEN** the endpoint responds `400` and writes nothing upstream

#### Scenario: An endsAt not after startsAt is rejected

- **WHEN** a `POST /events` body carries an `endsAt` equal to or earlier than `startsAt`
- **THEN** the endpoint responds `400` and writes nothing upstream, because the event window must be
  non-empty (`startsAt < endsAt`)

#### Scenario: An empty endsAt is rejected

- **WHEN** a `POST /events` body carries `endsAt` as the empty string
- **THEN** the endpoint responds `400` and writes nothing upstream

### Requirement: Event marker registry

An event SHALL exist in the registry exactly when the object `/events/<eventId>/metadata.json` is
present in the storage zone. On create the endpoint SHALL write this marker via a bunny native Storage
`PUT` with the `AccessKey` header from configuration and `Content-Type: application/json`, whose body is
the JSON `{ eventId, name, createdAt, startsAt, endsAt, capacity, lifetimeSeconds }` (`createdAt` an
ISO-8601 timestamp; `startsAt` and `endsAt` the canonical cutoff form; `capacity` and `lifetimeSeconds`
positive integers — the limit fields stamped per capability `event-limits`). The marker SHALL live under
the event's own `/events/<eventId>/` prefix, alongside the per-event device manifests at
`/events/<eventId>/devices/<deviceId>.json`. Because an `eventId` is a UUID, the marker key
`/events/<eventId>/metadata.json`, the device-manifest keys
`/events/<eventId>/devices/<deviceId>.json`, and the device-global byte store
`/files/devices/<deviceId>/…` are mutually disjoint and never collide.

`lifetimeSeconds` is a **duration**, never an absolute delete-by instant. Stamping the duration keeps the
per-event value immutable against a later configuration change while leaving the anchor it is measured
from (`max(createdAt, startsAt)`, capability `event-limits`) in shared code, so the anchor policy can be
corrected without rewriting a single stored marker.

The marker SHALL be **write-once**: there is no route by which a stored `startsAt` (or any other marker
field) can be changed after creation. The backend has no owner field and no authentication, so a
mutation route would let anyone holding the event id retroactively widen every future joiner's default
scope — or extend an event's own limits. The lifecycle (capability `event-limits`) is recomputed from
the stored fields on every read precisely so that no rewrite is ever needed.

#### Scenario: Create writes the marker

- **WHEN** a valid `POST /events` is processed
- **THEN** the endpoint issues a bunny native Storage `PUT` to `/events/<eventId>/metadata.json`
  carrying the `AccessKey` header and a JSON body of
  `{ eventId, name, createdAt, startsAt, endsAt, capacity, lifetimeSeconds }`

#### Scenario: The marker stamps a duration, not an instant

- **WHEN** the marker written by a create is inspected
- **THEN** it carries `lifetimeSeconds` as a positive integer number of seconds and carries no absolute
  delete-by field

#### Scenario: No route mutates an existing marker

- **WHEN** the backend's routes are enumerated
- **THEN** none of them rewrites `/events/<eventId>/metadata.json` for an event that already exists, so
  `startsAt`, `endsAt`, `capacity`, and `lifetimeSeconds` are immutable after creation

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
- **THEN** the endpoint responds `201` with
  `{ eventId, name, createdAt, startsAt, endsAt, capacity, deletesAt }`

#### Scenario: Marker store fails

- **WHEN** the marker `PUT` returns an error, times out, or aborts
- **THEN** the endpoint responds `502` and reports no created event

### Requirement: Event metadata and existence route

The backend SHALL accept an HTTP `GET` at the path `/events/<eventId>` (the literal label `events`
required) and return the event's metadata. `eventId` MUST match a UUID pattern; a matched request
whose `eventId` is not a UUID SHALL yield `400` and make no upstream request. The endpoint SHALL read
the marker `/events/<eventId>/metadata.json` and, when present and complete, respond `200` with
`{ eventId, name, createdAt, startsAt, endsAt, capacity, deletesAt }`; when the marker is absent,
respond `404`. A genuine upstream failure reading the marker (not a `404`) SHALL be surfaced as `502`.
This route is the canonical existence check; the same `/events/<eventId>/metadata.json` read backs the
existence gate the device-manifest write enforces.

`deletesAt` SHALL be the **derived** delete-by instant (`max(createdAt, startsAt) + lifetimeSeconds`,
capability `event-limits`) rendered in the canonical cutoff form, computed per response and never read
from a stored field. Serving it — rather than serving the lifetime and the anchor for a client to combine
— keeps the anchor policy in one place and means no client ever holds a copy of the lifetime constant.

The route SHALL apply the lifecycle check (capability `event-limits`): a marker missing `startsAt`,
`endsAt`, or `capacity`, or carrying an unparseable field, is **gone** and answered `404` — no field is
synthesized and the stored object is not patched. The route SHALL NOT delete anything on touch, and
SHALL serve an event past its derived `deletesAt` normally until the scheduled cleanup removes it. The
response's `startsAt`, `endsAt`, `capacity`, and `deletesAt` are therefore **always present** on a `200`,
so no client carries a nullable field and every downstream type stays total.

#### Scenario: Existing event returns metadata including the derived delete-by

- **WHEN** a `GET /events/<uuid>` arrives for an event whose marker exists and carries its limit fields
- **THEN** the endpoint reads `/events/<uuid>/metadata.json` and responds `200` with
  `{ eventId, name, createdAt, startsAt, endsAt, capacity, deletesAt }`, where `deletesAt` is
  `max(createdAt, startsAt) + lifetimeSeconds` in canonical cutoff form

#### Scenario: An event past its window is served normally

- **WHEN** a `GET /events/<uuid>` arrives after the event's `endsAt` has passed but before its
  `deletesAt`
- **THEN** the endpoint responds `200` with the full metadata — the window is not a lifecycle input

#### Scenario: An event past its delete-by is still served until the sweep runs

- **WHEN** a `GET /events/<uuid>` arrives for an event whose derived `deletesAt` has passed, before the
  next scheduled cleanup
- **THEN** the endpoint responds `200` and deletes nothing — deletion belongs solely to the sweep

#### Scenario: An incomplete marker is 404, not patched

- **WHEN** a `GET /events/<uuid>` reads a marker written before the limit fields existed
- **THEN** the endpoint responds `404` — no field is synthesized and the stored object is not patched

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
