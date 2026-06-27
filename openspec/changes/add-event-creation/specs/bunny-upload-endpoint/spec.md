## ADDED Requirements

### Requirement: Upload gated on event existence

Before streaming the body, the endpoint SHALL determine whether the event exists by reading the event
marker `events/<eventId>.json` (a bunny native Storage `GET` carrying the configured `AccessKey`).
When the marker is absent, the endpoint SHALL respond `404` and SHALL NOT stream the body or issue the
upstream object `PUT`. When the marker is present, the endpoint SHALL proceed with the streamed upload.
A genuine upstream failure reading the marker (any non-`404` error or timeout) SHALL be surfaced as
`5xx` and SHALL NOT be treated as "event absent" (never a `404` for a transient read failure). The
existence read targets the **event marker**, not the object key, so it does not change the
last-write-wins behavior of the object write itself.

#### Scenario: Upload to a non-existent event rejected

- **WHEN** a valid `PUT /event/<uuid>/file/<name>` arrives but the marker `events/<uuid>.json` is absent
- **THEN** the endpoint responds `404`, streams no body, and issues no upstream object `PUT`

#### Scenario: Upload to an existing event proceeds

- **WHEN** a valid `PUT` arrives and the marker `events/<uuid>.json` exists
- **THEN** the endpoint streams the body to the upstream object `PUT` as usual

#### Scenario: Marker read failure is not treated as absence

- **WHEN** the marker read returns a non-`404` upstream error or times out
- **THEN** the endpoint responds `5xx` and does not return `404` or store the object

## MODIFIED Requirements

### Requirement: Streaming proxy PUT

The endpoint SHALL accept an HTTP `PUT` at the path template
`/event/<eventId>/file/<filename>` whose request body is opaque binary resource
bytes, and SHALL forward those bytes to bunny native Storage by **streaming** — piping the request
body into a single upstream `PUT` without materializing the whole body in memory (it SHALL NOT
buffer the body, e.g. via `request.bytes()`/`arrayBuffer()`), and without hashing or transforming
the body. Exactly **one** upstream `PUT` of the body SHALL be made per upload; the only other
upstream call permitted is the single small event-existence marker `GET` from the gating requirement
(no other pre-checks, retries, or fan-out).

#### Scenario: Body streamed to bunny

- **WHEN** a valid `PUT` to the upload path arrives with a body for an existing event
- **THEN** the endpoint issues exactly one upstream `PUT` to bunny whose body is the request body
  passed through unchanged (byte-identical), and does not buffer the full body before forwarding

#### Scenario: Non-PUT methods rejected

- **WHEN** a request uses a method other than `PUT` or `OPTIONS` on the upload path
- **THEN** the endpoint responds `404` (no matching route) and makes no upstream request

### Requirement: bunny native Storage target and authorization

The endpoint SHALL forward the upload to bunny's native Storage API at
`https://<region-host>/<zone>/<eventId>/<filename>` using `PUT`, attaching the storage
zone's `AccessKey` header (the storage-zone password). It SHALL forward the request's `Content-Type`
to bunny, defaulting to `application/octet-stream` when absent. Authorization of the *caller* is the
possession of the event id alone — the endpoint SHALL NOT require any token. The endpoint now consults
the event registry (the marker) to determine **existence** and rejects uploads to a non-existent event
with `404`; consulting the registry is an existence check, not an authorization step — any caller
possessing a valid, existing event id is authorized to upload. The endpoint SHALL NOT expose or
forward the bunny account API key.

#### Scenario: Upstream URL composed from zone, region, and key

- **WHEN** a valid upload is forwarded
- **THEN** the upstream URL is `https://<configured region host>/<configured zone>/<key>` and
  carries the `AccessKey` header from configuration

#### Scenario: Content-Type forwarded or defaulted

- **WHEN** the request carries a `Content-Type`
- **THEN** the same value is sent upstream; **WHEN** it is absent, `application/octet-stream` is sent

#### Scenario: No token required

- **WHEN** a request carries a valid key and body but no authorization token, for an existing event
- **THEN** the upload is accepted (the event id is the capability)

### Requirement: Last-write-wins

The endpoint SHALL perform the object write as a single unconditional `PUT` and SHALL NOT perform an
existence check (`HEAD`/`GET`) **on the object key** `<eventId>/<filename>` before writing; an upload
to an existing key overwrites it. (The event-existence marker `GET` from the gating requirement is a
separate read of `events/<eventId>.json`, not of the object key, and does not make the object write
conditional.) Because the key is `<eventId>/<filename>` (no device level), the same key is reachable
by more than one device; an overwrite is therefore possibly cross-device. As the `filename` embeds the
per-device `localIdentifier`, a cross-device write to the same key is the same physical asset
(byte-identical), and a distinct-asset overwrite requires a `localIdentifier` UUID collision — an
accepted trade-off.

#### Scenario: Existing key overwritten

- **WHEN** a `PUT` targets a key that already exists in the zone, for an existing event
- **THEN** the endpoint issues the upstream object `PUT` directly (no prior existence check on the
  object key) and the object is overwritten
