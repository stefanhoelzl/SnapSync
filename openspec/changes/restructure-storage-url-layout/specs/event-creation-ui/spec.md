## MODIFIED Requirements

### Requirement: Create mints an event then provisions it like a scanned QR

The capability SHALL provide a create use-case that, on `create(name)`, sets `creationStatus` to
`InFlight`, calls the backend `POST /events` with the trimmed name via an injected client, and on a
`201 { eventId, name, createdAt }` funnels the returned `eventId` **and** `name` into the **existing**
provision path — the same `onProvision(previousEventId, newEventId)` switch-reset a scanned deeplink
uses — saving `EventConfig(eventId, name)` **directly** (the create path already has the name, so it
performs **no** `GET /events/:id` fetch — see `deeplink-config`). On any failure (non-2xx, transport, or
parse) it SHALL set `creationStatus` to `Failed(reason)` and SHALL NOT save config. The use-case MUST
NOT inspect `PermissionStatus`.

#### Scenario: Successful create provisions the event with its name
- **WHEN** `create("My Party")` is invoked and the backend returns `201` with `{eventId, name}`
- **THEN** the event is provisioned through the same `onProvision` path as a scanned QR,
  `EventConfig(eventId, name)` is saved (no metadata fetch), config becomes present, and the existing
  join/reconcile flow runs

#### Scenario: Create ignores permission
- **WHEN** `create(name)` is invoked while photo permission is `NOT_DETERMINED` or `DENIED`
- **THEN** the create proceeds (mints + provisions) without inspecting permission, and the missing
  permission surfaces afterward via the joined-layer `NeedsAccess` status line (per `sync-status-screen`)

#### Scenario: A failed create leaves config untouched
- **WHEN** `create(name)` is invoked and the backend request fails (non-2xx, transport, or parse)
- **THEN** `creationStatus` becomes `Failed(reason)`, config is unchanged, and no join is started

### Requirement: HTTP event creator over an injected client

The capability SHALL provide an `EventCreator` HTTP implementation over an injected Ktor `HttpClient`
and a host string (the engine and host are supplied by the composition root, keeping the impl
platform-neutral and testable with `MockEngine`), mirroring `HttpEventFilesSource`. It SHALL
`POST <host>/events` (HTTPS, default ATS) with a JSON body `{ "name": <trimmed name> }`, parse a `201`
body into `{ eventId, name, createdAt }`, and map any non-2xx, transport, or parse error to a failed
result the use-case turns into `Failed`. A `400` SHALL map to the invalid-name reason; any other
non-2xx or transport/parse error SHALL map to the transient/server reason.

#### Scenario: Create posts the name and parses the event
- **WHEN** the client posts to `<host>/events` and the server responds `201` with `{eventId,name,createdAt}`
- **THEN** the parsed `eventId` is returned for provisioning

#### Scenario: A 400 maps to the invalid-name reason
- **WHEN** the server responds `400` to the create request
- **THEN** the result is a failure carrying the invalid-name reason

#### Scenario: A 502 or transport error maps to the transient reason
- **WHEN** the server responds `502`, or the request fails to reach the server, or the body does not parse
- **THEN** the result is a failure carrying the transient/server reason
