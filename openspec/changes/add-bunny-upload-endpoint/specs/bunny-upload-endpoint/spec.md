## ADDED Requirements

### Requirement: Streaming proxy PUT

The endpoint SHALL accept an HTTP `PUT` at the path template
`/event/<eventId>/device/<deviceId>/file/<filename>` whose request body is opaque binary resource
bytes, and SHALL forward those bytes to bunny native Storage by **streaming** — piping the request
body into a single upstream `PUT` without materializing the whole body in memory (it SHALL NOT
buffer the body, e.g. via `request.bytes()`/`arrayBuffer()`), and without hashing or transforming
the body. Exactly **one** upstream subrequest SHALL be made per upload.

#### Scenario: Body streamed to bunny

- **WHEN** a valid `PUT` to the upload path arrives with a body
- **THEN** the endpoint issues exactly one upstream `PUT` to bunny whose body is the request body
  passed through unchanged (byte-identical), and does not buffer the full body before forwarding

#### Scenario: Non-PUT methods rejected

- **WHEN** a request uses a method other than `PUT` or `OPTIONS` on the upload path
- **THEN** the endpoint responds `404` (no matching route) and makes no upstream request

### Requirement: Object key from the URL path

The endpoint SHALL derive the object key from the request URL path, which SHALL match the template
`/event/<eventId>/device/<deviceId>/file/<filename>` (the literal labels `event`, `device`, and
`file` are required). `eventId` and `deviceId` MUST each match a UUID pattern; `filename` is a
single path segment that the client percent-encodes and the endpoint SHALL forward to bunny as
received (it SHALL NOT re-encode or decode it). From these the endpoint SHALL compose the **storage
key** as the bare `<eventId>/<deviceId>/<filename>` — the URL labels (`event`/`device`/`file`) are
**not** part of the stored key. A request whose path does not match the template SHALL yield `404`;
a path that matches the template but whose `eventId` or `deviceId` is not a UUID, or whose
`filename` segment is empty or contains `..`, SHALL yield `400`. Neither case SHALL make an upstream
request.

#### Scenario: Valid path accepted, bare key composed

- **WHEN** the path is `/event/<uuid>/device/<uuid>/file/IMG_0001-photo.jpg` with two valid UUIDs
- **THEN** the request is accepted and the storage key `<uuid>/<uuid>/IMG_0001-photo.jpg` (no
  labels) is composed for the upstream path

#### Scenario: Non-UUID event or device segment rejected

- **WHEN** the `eventId` or `deviceId` segment is not a UUID
- **THEN** the endpoint responds `400` and makes no upstream request

#### Scenario: Unmatched path rejected

- **WHEN** the path does not match `/event/<eventId>/device/<deviceId>/file/<filename>` (missing a
  label, wrong depth, or no filename)
- **THEN** the endpoint responds `404` and makes no upstream request

#### Scenario: Traversal or empty filename rejected

- **WHEN** the `filename` segment is empty or contains `..`
- **THEN** the endpoint responds `400` and makes no upstream request

### Requirement: bunny native Storage target and authorization

The endpoint SHALL forward the upload to bunny's native Storage API at
`https://<region-host>/<zone>/<eventId>/<deviceId>/<filename>` using `PUT`, attaching the storage
zone's `AccessKey` header (the storage-zone password). It SHALL forward the request's `Content-Type`
to bunny, defaulting to `application/octet-stream` when absent. Authorization of the *caller* is the
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
existence check (`HEAD`/`GET`) before writing; an upload to an existing key overwrites it.

#### Scenario: Existing key overwritten

- **WHEN** a `PUT` targets a key that already exists in the zone
- **THEN** the endpoint issues the upstream `PUT` directly (no prior existence check) and the object
  is overwritten

### Requirement: Faithful outcome propagation

The endpoint SHALL return a `2xx` status **only** when bunny confirms the object was stored. Any
upstream failure, timeout, aborted stream, or partial write SHALL be surfaced as a `5xx` status; the
endpoint SHALL NEVER return `2xx` for an unconfirmed or partial upload. (A false success would, under
the engine's retry-forever policy, strand a truncated object permanently.)

#### Scenario: Upstream success propagated

- **WHEN** bunny returns a success status confirming the stored object
- **THEN** the endpoint responds `2xx`

#### Scenario: Upstream failure propagated as 5xx

- **WHEN** bunny returns an error, the request times out, or the stream aborts mid-upload
- **THEN** the endpoint responds `5xx` and never `2xx`

### Requirement: OPTIONS preflight falls back to plain PUT

The endpoint SHALL respond to an `OPTIONS` request such that the iOS background uploader proceeds
with a plain, single-shot (non-resumable) `PUT` — i.e. it SHALL NOT advertise resumable-upload
support. (Server-side resumable uploads are a deferred future capability.)

#### Scenario: OPTIONS does not advertise resumable

- **WHEN** an `OPTIONS` request is received for an upload path
- **THEN** the response does not advertise resumable-upload support, signaling a plain `PUT` path

### Requirement: Environment-only configuration, fail-closed

The endpoint SHALL read the storage zone name, host, and `AccessKey` exclusively from Edge Script
environment variables; no secret SHALL appear in source. Configuration SHALL be validated **once at
startup**; a missing or blank required variable SHALL cause startup to fail (the parse throws), so a
misconfigured deployment does not serve and never uploads against a wrong or unauthenticated target.
The validated config is injected into the request handler, which therefore has no configuration
failure path.

#### Scenario: Missing config fails the boot

- **WHEN** a required configuration value (zone, host, or `AccessKey`) is absent or blank at startup
- **THEN** config parsing throws, the endpoint does not start, and no upload is ever attempted

## Assumptions (unverified on device)

This section is **non-normative**. The endpoint's bunny-facing behavior above is verified by the
`Deno.test` suite against a mocked upstream. The **iOS-facing** surface is frozen here but cannot be
exercised in a backend-only change; these assumptions are the iOS follow-up's first job and mirror
docs/design.md §8:

- **OPTIONS fallback.** That the iOS background uploader, given the non-resumable OPTIONS response,
  falls back to a plain `PUT` against this custom origin (raw S3 verified to need no preflight;
  unverified here).
- **Accepted success codes.** Which `2xx` code(s) the background uploader treats as success.
- **Large-payload budget.** That the largest Live-Photo paired-video completes within the 30 s
  **CPU** budget (expected: yes — pass-through is I/O-bound) and within any **undocumented
  wall-clock/idle timeout** on a long-held streaming request. If violated, the fix is enabling
  server-side resumable uploads.
