## MODIFIED Requirements

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

The marker SHALL be **write-once except for `name`**: `eventId`, `createdAt`, `startsAt`, `endsAt`,
`capacity`, and `lifetimeSeconds` SHALL be immutable after creation, and no route SHALL change any of
them. The backend has no owner field and no authentication, so a general mutation route would let anyone
holding the event id retroactively widen every future joiner's default scope — or extend an event's own
limits. The lifecycle (capability `event-limits`) is recomputed from the stored fields on every read
precisely so that no rewrite of those fields is ever needed.

`name` is the **single** exception, writable **only** through the dedicated rename route below
(capability `event-rename`). It is exempt because it touches neither named threat: a name cannot widen a
capture-date scope and cannot extend a lifetime. It is cosmetic to the upload gate, cosmetic to the
extension, and load-bearing for display alone. Any future proposal to make another marker field mutable
SHALL argue against the two threats by name; the exemption granted here does not generalize.

#### Scenario: Create writes the marker

- **WHEN** a valid `POST /events` is processed
- **THEN** the endpoint issues a bunny native Storage `PUT` to `/events/<eventId>/metadata.json`
  carrying the `AccessKey` header and a JSON body of
  `{ eventId, name, createdAt, startsAt, endsAt, capacity, lifetimeSeconds }`

#### Scenario: The marker stamps a duration, not an instant

- **WHEN** the marker written by a create is inspected
- **THEN** it carries `lifetimeSeconds` as a positive integer number of seconds and carries no absolute
  delete-by field

#### Scenario: No route mutates a marker field other than the name

- **WHEN** the backend's routes are enumerated
- **THEN** the rename route is the only one that rewrites `/events/<eventId>/metadata.json` for an event
  that already exists, and it changes `name` alone — so `eventId`, `createdAt`, `startsAt`, `endsAt`,
  `capacity`, and `lifetimeSeconds` remain immutable after creation

#### Scenario: Marker is disjoint from manifests and the byte store

- **WHEN** the marker `/events/<eventId>/metadata.json` exists, a device manifest is stored at
  `/events/<eventId>/devices/<deviceId>.json`, and bytes are stored under `/files/devices/<deviceId>/…`
- **THEN** the three keys are distinct and never collide (an `eventId` is a UUID, so the literal
  `metadata.json` and `devices/` segments never alias a device id or a stored filename)

## ADDED Requirements

### Requirement: Event rename route

The backend SHALL accept an HTTP `PATCH` at the path `/events/:eventId` whose body is a JSON object
containing a `name`, and on success SHALL respond `200` with the same JSON body shape the metadata route
serves for that event, carrying the **stored** (trimmed) name. The route SHALL be served by the same
device-token-gated application as `POST /events`, so an unattested caller cannot reach it. A request
using any method other than `PATCH` or `GET` on that path SHALL yield `404`.

The route SHALL validate `name` with the **same** rule the create route applies: trim surrounding
whitespace, require the trimmed value to be non-empty, and require its length to be at most 100
characters. A body that is not valid JSON, lacks a `name`, or whose trimmed `name` is empty or longer
than 100 characters SHALL yield `400` and SHALL NOT make any upstream write. An `eventId` that is not a
canonical UUID SHALL yield `400`.

The route SHALL resolve the event through the same existence gate the metadata route uses: an event that
is absent or whose marker is incomplete SHALL yield `404` and SHALL NOT make any upstream write; a
non-404 marker read failure SHALL yield `502`.

The route SHALL rewrite the marker with **only** `name` replaced and **every other field verbatim** —
never restamped, never recomputed. Writing the other fields verbatim is what makes a race with the
nightly sweep (capability `scheduled-cleanup`) self-defusing: a rename that re-creates a marker the sweep
has just deleted re-creates it with its original `createdAt`, `startsAt`, and `lifetimeSeconds`, so its
derived delete-by is still in the past and the next sweep reaps it again. Restamping any of those would
resurrect the event for a fresh lifetime.

Concurrent renames SHALL resolve last-write-wins. The storage backend offers no compare-and-set — the
same constraint the device-manifest capacity gate already reads and writes without coordination under —
so no ordering guarantee is available and none is claimed.

The route SHALL apply **no** ownership, role, or creator check. There is no owner field, and possession
of the event id already authorizes uploading into the event and listing every photo in it, so the
device-token gate is the only authorization a rename requires.

#### Scenario: A valid rename rewrites the name and echoes it

- **WHEN** a `PATCH /events/<eventId>` arrives with body `{ "name": "Ana's 30th" }` for an existing event
- **THEN** the endpoint rewrites the marker with `name` `"Ana's 30th"` and responds `200` with the
  event's JSON carrying that name

#### Scenario: Surrounding whitespace is trimmed and the trimmed value is echoed

- **WHEN** a rename body carries `name` `"  Ana's 30th  "`
- **THEN** the stored and returned `name` is `"Ana's 30th"`

#### Scenario: Every other marker field survives verbatim

- **WHEN** a rename is processed for an event with a stored `createdAt`, `startsAt`, `endsAt`,
  `capacity`, and `lifetimeSeconds`
- **THEN** the rewritten marker carries all five byte-identical to their stored values, and the
  event's derived `deletesAt` is unchanged

#### Scenario: An empty, whitespace-only, or over-long name is rejected

- **WHEN** a rename body has `name` absent, empty, whitespace-only, or longer than 100 characters after
  trimming
- **THEN** the endpoint responds `400` and writes nothing upstream

#### Scenario: A non-JSON body is rejected

- **WHEN** a rename body is not valid JSON
- **THEN** the endpoint responds `400` and writes nothing upstream

#### Scenario: An invalid event id is rejected

- **WHEN** a rename targets an event id that is not a canonical UUID
- **THEN** the endpoint responds `400` and makes no upstream request

#### Scenario: A missing event is a 404

- **WHEN** a rename targets an event whose marker is absent or incomplete
- **THEN** the endpoint responds `404` and writes nothing upstream

#### Scenario: An upstream read failure is a 502

- **WHEN** the marker read for a rename fails for a reason other than absence
- **THEN** the endpoint responds `502` and writes nothing upstream

#### Scenario: An unattested caller cannot rename

- **WHEN** a rename arrives without a valid device token
- **THEN** the shared gate refuses it exactly as it refuses an ungated create

#### Scenario: A rename by any member is accepted

- **WHEN** a rename arrives from a device that did not create the event, carrying a valid device token
- **THEN** the endpoint applies it — no ownership check exists
