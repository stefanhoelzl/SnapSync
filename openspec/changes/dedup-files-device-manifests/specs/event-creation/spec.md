## MODIFIED Requirements

### Requirement: Event marker registry

An event SHALL exist in the registry exactly when the object `/events/<eventId>/metadata.json` is
present in the storage zone (this supersedes the prior `events/<eventId>.json` key). On create the
endpoint SHALL write this marker via a bunny native Storage `PUT` with the `AccessKey` header from
configuration and `Content-Type: application/json`, whose body is the JSON `{ eventId, name,
createdAt }` (`createdAt` an ISO-8601 timestamp). The marker SHALL live under the event's own
`/events/<eventId>/` prefix, alongside the per-event device manifests at
`/events/<eventId>/device/<deviceId>.json`. Because an `eventId` is a UUID, the marker key
`/events/<eventId>/metadata.json`, the device-manifest keys `/events/<eventId>/device/<deviceId>.json`,
and the device-global byte store `/files/<deviceId>/…` are mutually disjoint and never collide.

#### Scenario: Create writes the marker

- **WHEN** a valid `POST /event` is processed
- **THEN** the endpoint issues a bunny native Storage `PUT` to `/events/<eventId>/metadata.json`
  carrying the `AccessKey` header and a JSON body of `{ eventId, name, createdAt }`

#### Scenario: Marker is disjoint from manifests and the byte store

- **WHEN** the marker `/events/<eventId>/metadata.json` exists, a device manifest is stored at
  `/events/<eventId>/device/<deviceId>.json`, and bytes are stored under `/files/<deviceId>/…`
- **THEN** the three keys are distinct and never collide (an `eventId` is a UUID, so the literal
  `metadata.json` and `device/` segments never alias a device id or a stored filename)

### Requirement: Event metadata and existence route

The backend SHALL accept an HTTP `GET` at the path `/event/<eventId>` (the literal label `event`
required) and return the event's metadata. `eventId` MUST match a UUID pattern; a matched request
whose `eventId` is not a UUID SHALL yield `400` and make no upstream request. The endpoint SHALL read
the marker `/events/<eventId>/metadata.json` and, when present, respond `200` with its contents
`{ eventId, name, createdAt }`; when the marker is absent, respond `404`. A genuine upstream failure
reading the marker (not a `404`) SHALL be surfaced as `502`. This route is the canonical existence
check; the same `/events/<eventId>/metadata.json` read backs the existence gate the device-manifest
write enforces.

#### Scenario: Existing event returns metadata

- **WHEN** a `GET /event/<uuid>` arrives for an event whose marker exists
- **THEN** the endpoint reads `/events/<uuid>/metadata.json` and responds `200` with
  `{ eventId, name, createdAt }`

#### Scenario: Unknown event yields 404

- **WHEN** a `GET /event/<uuid>` arrives for an event whose marker `/events/<uuid>/metadata.json`
  is absent
- **THEN** the endpoint responds `404`

#### Scenario: Non-UUID event id rejected

- **WHEN** the `eventId` segment of `GET /event/<id>` is not a UUID
- **THEN** the endpoint responds `400` and makes no upstream request

#### Scenario: Upstream failure surfaced

- **WHEN** reading the marker `/events/<uuid>/metadata.json` returns a non-404 upstream error or
  times out
- **THEN** the endpoint responds `502`

#### Scenario: Existence gate reads the same marker

- **WHEN** the device-manifest write checks that its target event exists
- **THEN** it reads `/events/<eventId>/metadata.json` (the canonical marker), proceeding only when
  it is present
