## ADDED Requirements

### Requirement: Event creation route

The backend SHALL accept an HTTP `POST` at the path `/event` whose body is a JSON object containing a
`name`, and on success SHALL respond `201` with a JSON body `{ eventId, name, createdAt }`. The
endpoint SHALL be served by the same Hono application as the upload and list endpoints, so it is
available on every deployment target without separate configuration. A request using any method other
than `POST` on `/event` (or a path that does not match) SHALL yield `404`.

#### Scenario: Valid create returns the new event

- **WHEN** a `POST /event` arrives with body `{ "name": "Birthday" }`
- **THEN** the endpoint responds `201` with a JSON body containing `eventId`, `name` (`"Birthday"`),
  and `createdAt`

#### Scenario: Wrong method on the create path

- **WHEN** a `GET` (or any non-`POST`) is sent to `/event`
- **THEN** the endpoint responds `404` and makes no upstream request

### Requirement: Server-minted event id

The endpoint SHALL generate the `eventId` itself using a UUID generator (`crypto.randomUUID()`),
producing a canonical `8-4-4-4-12` UUID. The endpoint SHALL NOT accept or honor a client-supplied
event id in the request. The returned `eventId` SHALL match the same UUID pattern the upload and list
routes validate.

#### Scenario: Event id is server-generated and canonical

- **WHEN** a valid `POST /event` is processed
- **THEN** the returned `eventId` is a canonical UUID minted by the server

#### Scenario: Client-supplied id is ignored

- **WHEN** a `POST /event` body includes an `eventId` or `id` field alongside `name`
- **THEN** the endpoint ignores it and returns a freshly server-minted `eventId`

### Requirement: Event name validation

The endpoint SHALL parse the request body as JSON and validate `name`: it SHALL trim surrounding
whitespace, require the trimmed value to be non-empty, and require its length to be at most 100
characters. A request whose body is not valid JSON, lacks a `name`, or whose trimmed `name` is empty
or longer than 100 characters SHALL yield `400` and SHALL NOT make any upstream write. The trimmed
value SHALL be the name that is stored and returned.

#### Scenario: Empty or whitespace-only name rejected

- **WHEN** a `POST /event` body has `name` absent, empty, or only whitespace
- **THEN** the endpoint responds `400` and writes nothing upstream

#### Scenario: Over-long name rejected

- **WHEN** a `POST /event` body has a `name` whose trimmed length exceeds 100 characters
- **THEN** the endpoint responds `400` and writes nothing upstream

#### Scenario: Non-JSON body rejected

- **WHEN** a `POST /event` body is not valid JSON
- **THEN** the endpoint responds `400` and writes nothing upstream

#### Scenario: Surrounding whitespace trimmed

- **WHEN** a `POST /event` body has `name` `"  Birthday  "`
- **THEN** the stored and returned `name` is `"Birthday"`

### Requirement: Event marker registry

An event SHALL exist in the registry exactly when the object `events/<eventId>.json` is present in
the storage zone. On create the endpoint SHALL write this marker via a bunny native Storage `PUT`
with the `AccessKey` header from configuration and `Content-Type: application/json`, whose body is the
JSON `{ eventId, name, createdAt }` (`createdAt` an ISO-8601 timestamp). The marker SHALL live under
the `events/` prefix, which is disjoint from any event's photo directory `<eventId>/` (an `eventId`
is a UUID and never the literal `events`), so the marker never appears in a per-event file listing and
never collides with a stored photo.

#### Scenario: Create writes the marker

- **WHEN** a valid `POST /event` is processed
- **THEN** the endpoint issues a bunny native Storage `PUT` to `events/<eventId>.json` carrying the
  `AccessKey` header and a JSON body of `{ eventId, name, createdAt }`

#### Scenario: Marker is disjoint from the photo directory

- **WHEN** the marker `events/<eventId>.json` exists and photos are stored under `<eventId>/`
- **THEN** a LIST of `<eventId>/` does not include the marker

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

The backend SHALL accept an HTTP `GET` at the path `/event/<eventId>` (the literal label `event`
required) and return the event's metadata. `eventId` MUST match a UUID pattern; a matched request
whose `eventId` is not a UUID SHALL yield `400` and make no upstream request. The endpoint SHALL read
the marker `events/<eventId>.json` and, when present, respond `200` with its contents
`{ eventId, name, createdAt }`; when the marker is absent, respond `404`. A genuine upstream failure
reading the marker (not a `404`) SHALL be surfaced as `502`. This route is the canonical
existence check used by the list and upload gates.

#### Scenario: Existing event returns metadata

- **WHEN** a `GET /event/<uuid>` arrives for an event whose marker exists
- **THEN** the endpoint responds `200` with `{ eventId, name, createdAt }`

#### Scenario: Unknown event yields 404

- **WHEN** a `GET /event/<uuid>` arrives for an event whose marker is absent
- **THEN** the endpoint responds `404`

#### Scenario: Non-UUID event id rejected

- **WHEN** the `eventId` segment of `GET /event/<id>` is not a UUID
- **THEN** the endpoint responds `400` and makes no upstream request

#### Scenario: Upstream failure surfaced

- **WHEN** reading the marker returns a non-404 upstream error or times out
- **THEN** the endpoint responds `502`
