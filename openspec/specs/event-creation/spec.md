# event-creation Specification

## Purpose
TBD - created by archiving change add-event-creation. Update Purpose after archive.
## Requirements
### Requirement: Event creation route

The backend SHALL accept an HTTP `POST` at the path `/events` whose body is a JSON object containing a
`name`, and on success SHALL respond `201` with a JSON body `{ eventId, name, createdAt }`. The
endpoint SHALL be served by the same Hono application as the upload and list endpoints, so it is
available on every deployment target without separate configuration. A request using any method other
than `POST` on `/events` (or a path that does not match) SHALL yield `404`.

#### Scenario: Valid create returns the new event

- **WHEN** a `POST /events` arrives with body `{ "name": "Birthday" }`
- **THEN** the endpoint responds `201` with a JSON body containing `eventId`, `name` (`"Birthday"`),
  and `createdAt`

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

### Requirement: Event marker registry

An event SHALL exist in the registry exactly when the object `/events/<eventId>/metadata.json` is
present in the storage zone (this supersedes the prior `events/<eventId>.json` key). On create the
endpoint SHALL write this marker via a bunny native Storage `PUT` with the `AccessKey` header from
configuration and `Content-Type: application/json`, whose body is the JSON `{ eventId, name,
createdAt }` (`createdAt` an ISO-8601 timestamp). The marker SHALL live under the event's own
`/events/<eventId>/` prefix, alongside the per-event device manifests at
`/events/<eventId>/devices/<deviceId>.json`. Because an `eventId` is a UUID, the marker key
`/events/<eventId>/metadata.json`, the device-manifest keys `/events/<eventId>/devices/<deviceId>.json`,
and the device-global byte store `/files/devices/<deviceId>/…` are mutually disjoint and never collide.

#### Scenario: Create writes the marker

- **WHEN** a valid `POST /events` is processed
- **THEN** the endpoint issues a bunny native Storage `PUT` to `/events/<eventId>/metadata.json`
  carrying the `AccessKey` header and a JSON body of `{ eventId, name, createdAt }`

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
- **THEN** the endpoint responds `201` with `{ eventId, name, createdAt }`

#### Scenario: Marker store fails

- **WHEN** the marker `PUT` returns an error, times out, or aborts
- **THEN** the endpoint responds `502` and reports no created event

### Requirement: Event metadata and existence route

The backend SHALL accept an HTTP `GET` at the path `/events/<eventId>` (the literal label `events`
required) and return the event's metadata. `eventId` MUST match a UUID pattern; a matched request
whose `eventId` is not a UUID SHALL yield `400` and make no upstream request. The endpoint SHALL read
the marker `/events/<eventId>/metadata.json` and, when present, respond `200` with its contents
`{ eventId, name, createdAt }`; when the marker is absent, respond `404`. A genuine upstream failure
reading the marker (not a `404`) SHALL be surfaced as `502`. This route is the canonical existence
check; the same `/events/<eventId>/metadata.json` read backs the existence gate the device-manifest
write enforces.

#### Scenario: Existing event returns metadata

- **WHEN** a `GET /events/<uuid>` arrives for an event whose marker exists
- **THEN** the endpoint reads `/events/<uuid>/metadata.json` and responds `200` with
  `{ eventId, name, createdAt }`

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

