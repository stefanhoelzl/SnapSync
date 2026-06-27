# bunny upload endpoint Specification

## Purpose

A streaming proxy upload endpoint on bunny.net Edge Scripting (Deno + Hono): the iOS
background-upload extension PUTs a photo resource's bytes to it, and it streams them into a bunny
**native** Storage zone under a per-event key. It replaces v1's on-device presigning —
no SigV4, no UNSIGNED-PAYLOAD; the device holds no storage credential (the event id is the
capability). Authoritative design: docs/design.md §3.1 (keys), §4 (storage/auth).
## Requirements
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
