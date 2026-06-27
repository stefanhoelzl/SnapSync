## MODIFIED Requirements

### Requirement: Streaming proxy PUT

The endpoint SHALL accept an HTTP `PUT` at the path template
`/event/<eventId>/file/<filename>` whose request body is opaque binary resource bytes, and SHALL
forward those bytes to bunny native Storage by **streaming** — piping the request body into a single
upstream `PUT` without materializing the whole body in memory (it SHALL NOT buffer the body, e.g. via
`request.bytes()`/`arrayBuffer()`), and without hashing or transforming the body. Exactly **one**
upstream subrequest SHALL be made per upload.

#### Scenario: Body streamed to bunny

- **WHEN** a valid `PUT` to the upload path arrives with a body
- **THEN** the endpoint issues exactly one upstream `PUT` to bunny whose body is the request body
  passed through unchanged (byte-identical), and does not buffer the full body before forwarding

#### Scenario: Non-PUT methods rejected

- **WHEN** a request uses a method other than `PUT` or `OPTIONS` on the upload path
- **THEN** the endpoint responds `404` (no matching route) and makes no upstream request

### Requirement: Object key from the URL path

The endpoint SHALL derive `eventId` and `filename` from the decoded path params of the route
`/event/<eventId>/file/<filename>` (the literal labels `event` and `file` are required). `eventId`
MUST match a UUID pattern; `filename` MUST be a single, non-empty segment containing no path
separator (`/`, encoded or literal) and no `..`. The endpoint SHALL write the object at the bare key
`<eventId>/<filename>` — the URL labels (`event`/`file`) are **not** part of the stored key —
percent-encoding each segment when building the storage request URL so the key stays a single flat
path. A request whose path does not match the route (missing label, wrong depth, or no filename)
SHALL yield `404`; a matched request whose `eventId` is not a UUID or whose `filename` is unsafe
SHALL yield `400`. Neither case SHALL make an upstream request.

#### Scenario: Valid path accepted, bare key composed

- **WHEN** the path is `/event/<uuid>/file/IMG_0001-photo.jpg` with a valid UUID
- **THEN** the request is accepted and the storage key `<uuid>/IMG_0001-photo.jpg` (no labels) is
  composed for the upstream path

#### Scenario: Non-UUID event segment rejected

- **WHEN** the `eventId` segment is not a UUID
- **THEN** the endpoint responds `400` and makes no upstream request

#### Scenario: Unmatched path rejected (incl. empty filename)

- **WHEN** the path does not match the route (missing a label, wrong depth, or no filename — e.g.
  ends in `/file/`)
- **THEN** the endpoint responds `404` and makes no upstream request

#### Scenario: Unsafe filename rejected

- **WHEN** the `filename` segment contains `..` or a separator (`/` or its encoded `%2F`)
- **THEN** the endpoint responds `400` and makes no upstream request

### Requirement: bunny native Storage target and authorization

The endpoint SHALL forward the upload to bunny's native Storage API at
`https://<region-host>/<zone>/<eventId>/<filename>` using `PUT`, attaching the storage zone's
`AccessKey` header (the storage-zone password). It SHALL forward the request's `Content-Type` to
bunny, defaulting to `application/octet-stream` when absent. Authorization of the *caller* is the
possession of the event id alone — the endpoint SHALL NOT require any token and SHALL NOT consult an
event registry. The endpoint SHALL NOT expose or forward the bunny account API key.

#### Scenario: Upstream URL composed from zone, region, and key

- **WHEN** a valid upload is forwarded
- **THEN** the upstream URL is `https://<configured region host>/<configured zone>/<key>` and
  carries the `AccessKey` header from configuration

#### Scenario: Content-Type forwarded or defaulted

- **WHEN** the request carries a `Content-Type`
- **THEN** the same value is sent upstream; **WHEN** it is absent, `application/octet-stream` is sent

#### Scenario: No token required

- **WHEN** a request carries a valid key and body but no authorization token
- **THEN** the upload is accepted (the event id is the capability)

### Requirement: Last-write-wins

The endpoint SHALL perform the upload as a single unconditional `PUT` and SHALL NOT perform an
existence check (`HEAD`/`GET`) before writing; an upload to an existing key overwrites it. Because
the key is `<eventId>/<filename>` (no device level), the same key is reachable by more than one
device; an overwrite is therefore possibly cross-device. As the `filename` embeds the per-device
`localIdentifier`, a cross-device write to the same key is the same physical asset (byte-identical),
and a distinct-asset overwrite requires a `localIdentifier` UUID collision — an accepted trade-off.

#### Scenario: Existing key overwritten

- **WHEN** a `PUT` targets a key that already exists in the zone
- **THEN** the endpoint issues the upstream `PUT` directly (no prior existence check) and the object
  is overwritten
