# bunny upload endpoint Specification

## Purpose

A streaming proxy upload endpoint on bunny.net Edge Scripting (Deno + Hono): the iOS
background-upload extension PUTs a photo resource's bytes to it, and it streams them into a bunny
**native** Storage zone under a device-partitioned key (`files/devices/<deviceId>/<filename>`). It
replaces v1's on-device presigning — no SigV4, no UNSIGNED-PAYLOAD; the device holds no storage
credential. The caller is authorized by an App Attest device token (capability `device-attestation`);
the device id in the path addresses the write rather than authorizing it, and the device-manifest route
additionally consults the event marker for existence.

**Why a proxy rather than presigned PUTs.** The OS-driven upload job fixes its destination URL at
job-creation time and the *system*, not the app, reads and sends the bytes — so the device never sees them
and cannot compute a payload hash. Whether a bunny S3-compatible presigned PUT would accept
`UNSIGNED-PAYLOAD` was the pivot's top risk. Proxying removes the question entirely: the **edge** writes to
storage with its `AccessKey`, and the device performs an ordinary PUT with no signature at all. The accepted
cost is that the edge sees the bytes in transit.

Decision record: `changes/archive/2026-06-22-add-bunny-upload-endpoint` (the proxy pivot),
`changes/archive/2026-07-06-restructure-storage-url-layout` (the current key layout).
## Requirements
### Requirement: Streaming proxy PUT

The endpoint SHALL accept an HTTP `PUT` on **two** write routes and, for each, forward the request
body to bunny native Storage by **streaming** — piping the request body into a single upstream `PUT`
without materializing the whole body in memory (it SHALL NOT buffer the body, e.g. via
`request.bytes()`/`arrayBuffer()`), and without hashing or transforming the body:

- (a) the **photo-byte** route `PUT /api/v1/files/devices/<deviceId>/<filename>`, whose body is opaque binary
  resource bytes; and
- (b) the **device-manifest** route `PUT /api/v1/events/<eventId>/devices/<deviceId>`, whose body is a JSON
  device manifest.

The v1 byte routes `PUT /event/<eventId>/file/<filename>` and `PUT /files/device/<deviceId>/<filename>`
are **retired** (no longer routed). For each accepted write, exactly **one** upstream `PUT` of the body
SHALL be made; the only other upstream calls permitted are the device-manifest route's gate reads —
the single small event-existence marker `GET` and the single `devices/` directory listing the limits
gate requires (capability `event-limits`) — the byte route makes no marker read and no listing (no
other pre-checks, retries, or fan-out). When the gate refuses the write — the marker is absent, or the
event is at capacity and the device is new — those two gate reads are the only upstream calls made and
no upstream object `PUT` of the body follows. No route deletes anything on touch (capability
`event-limits`), so a refusal never costs an upstream write of any kind.

#### Scenario: Byte body streamed to bunny

- **WHEN** a valid `PUT /api/v1/files/devices/<deviceId>/<filename>` arrives with a body
- **THEN** the endpoint issues exactly one upstream `PUT` to bunny whose body is the request body
  passed through unchanged (byte-identical), and does not buffer the full body before forwarding

#### Scenario: Manifest body streamed to bunny

- **WHEN** a valid `PUT /api/v1/events/<eventId>/devices/<deviceId>` arrives with a JSON body for an existing
  event, from a device the limits gate admits
- **THEN** the endpoint issues exactly one upstream `PUT` to bunny whose body is the request body
  passed through unchanged, and does not buffer the full body before forwarding

#### Scenario: Non-PUT methods rejected

- **WHEN** a request uses a method other than `PUT` or `OPTIONS` on a write path
- **THEN** the endpoint responds `404` (no matching route) and makes no upstream request

### Requirement: Object key from the URL path

The endpoint SHALL derive each route's params from the decoded path and write each object at a bare
storage key from which the URL labels are dropped:

- **Byte route** `/api/v1/files/devices/<deviceId>/<filename>` (the literal labels `files` and
  `devices` are
  required): `deviceId` MUST match a UUID pattern; `filename` MUST be a single, non-empty segment
  containing no path separator (`/`, encoded `%2F`, or literal) and no `..`. The object SHALL be
  written at the bare key `files/devices/<deviceId>/<filename>` — the label ordering of the URL is
  preserved in the key — percent-encoding each segment when building the storage request URL so the
  key stays a single flat path.
- **Device-manifest route** `/api/v1/events/<eventId>/devices/<deviceId>` (the literal labels `events`
  and `devices` are required): `eventId` and `deviceId` MUST each match a UUID pattern. The object SHALL be
  written at the bare key `events/<eventId>/devices/<deviceId>.json` with `Content-Type:
  application/json`.

A request whose path does not match either route (missing a label, wrong depth, or no final segment)
SHALL yield `404`; a matched request whose UUID param is not a UUID, or whose `filename` is unsafe,
SHALL yield `400`. Neither case SHALL make an upstream request.

#### Scenario: Valid byte path accepted, device-namespace key composed

- **WHEN** the path is `/api/v1/files/devices/<uuid>/IMG_0001-photo.jpg` with a valid UUID
- **THEN** the request is accepted and the storage key `files/devices/<uuid>/IMG_0001-photo.jpg` is
  composed for the upstream path

#### Scenario: Valid manifest path accepted, json key composed

- **WHEN** the path is `/api/v1/events/<eventUuid>/devices/<deviceUuid>` with valid UUIDs
- **THEN** the request is accepted and the storage key `events/<eventUuid>/devices/<deviceUuid>.json`
  with `Content-Type: application/json` is composed for the upstream path

#### Scenario: Non-UUID segment rejected

- **WHEN** the `deviceId` (byte route) or `eventId`/`deviceId` (manifest route) segment is not a UUID
- **THEN** the endpoint responds `400` and makes no upstream request

#### Scenario: Unmatched path rejected

- **WHEN** the path does not match either route (missing a label, wrong depth, or no final segment —
  e.g. ends in `/files/`)
- **THEN** the endpoint responds `404` and makes no upstream request

#### Scenario: Unsafe filename rejected

- **WHEN** the byte route's `filename` segment contains `..` or a separator (`/` or its encoded `%2F`)
- **THEN** the endpoint responds `400` and makes no upstream request

### Requirement: bunny native Storage target and authorization

The endpoint SHALL forward each write to bunny's native Storage API at `https://<region-host>/<zone>/<key>`
using `PUT`, attaching the storage zone's `AccessKey` header (the storage-zone password), where
`<key>` is the bare key derived per the key requirement (`files/devices/<deviceId>/<filename>` for the
byte route, `events/<eventId>/devices/<deviceId>.json` for the device-manifest route). It SHALL forward
the request's `Content-Type` to bunny, defaulting to `application/octet-stream` when absent (the
device-manifest route carries `application/json`). The caller SHALL hold a valid App Attest device token
(capability `device-attestation`); see the token requirement below, which is the authorization step.
Beyond that token, addressing is self-asserted: a `/api/v1/files/devices/<deviceId>/` write names its
own device
id, and the device-manifest route consults the event marker for **existence** only (see the
device-manifest gate requirement) — an existence check, not a second authorization step. The endpoint
SHALL NOT expose or forward the bunny account API key.

#### Scenario: Upstream URL composed from zone, region, and key

- **WHEN** a valid write is forwarded
- **THEN** the upstream URL is `https://<configured region host>/<configured zone>/<key>` and carries
  the `AccessKey` header from configuration

#### Scenario: Content-Type forwarded or defaulted

- **WHEN** the request carries a `Content-Type`
- **THEN** the same value is sent upstream; **WHEN** it is absent, `application/octet-stream` is sent

#### Scenario: A write without a token is rejected

- **WHEN** a request carries a valid path and body but no authorization token
- **THEN** it is rejected with `401` and nothing is forwarded upstream — the device id addresses the
  write, it does not authorize it

### Requirement: Last-write-wins

The endpoint SHALL perform each object write as a single unconditional `PUT` and SHALL NOT perform an
existence check (`HEAD`/`GET`) **on the object key** before writing; a write to an existing key
overwrites it. (The device-manifest route's event-existence marker `GET` is a separate read of
`events/<eventId>/metadata.json`, not of the object key, and does not make the object write
conditional.) The byte key is device-partitioned (`files/devices/<deviceId>/<filename>`), so a given
key is reachable only by the device that owns that partition; an overwrite is therefore same-device,
and because the `filename` embeds the per-device `localIdentifier` it targets the same physical asset
(byte-identical re-upload). The device-manifest key (`events/<eventId>/devices/<deviceId>.json`) is
rewritten in full each cycle, so the latest write wins with no read-modify-write and no lost update.

#### Scenario: Existing byte key overwritten

- **WHEN** a `PUT` targets a `files/devices/<deviceId>/<filename>` key that already exists in the zone
- **THEN** the endpoint issues the upstream object `PUT` directly (no prior existence check on the
  object key) and the object is overwritten

#### Scenario: Existing manifest key overwritten

- **WHEN** a `PUT` targets a `events/<eventId>/devices/<deviceId>.json` key that already exists, for an
  existing event
- **THEN** the endpoint issues the upstream object `PUT` directly (no prior existence check on the
  object key) and the manifest is overwritten with the new full-state snapshot

### Requirement: Faithful outcome propagation

The endpoint SHALL return a `2xx` status **only** when bunny confirms the object was stored. Any
upstream failure, timeout, aborted stream, or partial write SHALL be surfaced as a `5xx` status; the
endpoint SHALL NEVER return `2xx` for an unconfirmed or partial upload. (A false success would, under
the engine's retry-forever policy, strand a truncated object permanently.)

The `2xx` the endpoint returns SHALL be one the iOS background uploader accepts as success **as
delivered through the bunny CDN pull zone** — a success code the uploader rejects would strand the
resource retrying forever even though the object is durably stored, which is the same harm as a false
failure and is invisible to the endpoint.

#### Scenario: Upstream success propagated

- **WHEN** bunny returns a success status confirming the stored object
- **THEN** the endpoint responds `2xx`

#### Scenario: Upstream failure propagated as 5xx

- **WHEN** bunny returns an error, the request times out, or the stream aborts mid-upload
- **THEN** the endpoint responds `5xx` and never `2xx`

#### Scenario: The device-visible success code is accepted by the uploader

- **WHEN** the iOS background uploader completes a `PUT` against the device-facing origin and the
  endpoint's `2xx` reaches it through the pull zone
- **THEN** the uploader treats the upload as successful and does not retry the resource

### Requirement: OPTIONS preflight falls back to plain PUT

The endpoint SHALL respond to an `OPTIONS` request such that the iOS background uploader proceeds with
a plain, single-shot (non-resumable) `PUT` — i.e. it SHALL NOT advertise resumable-upload support.
(Server-side resumable uploads are a deferred future capability.)

This SHALL hold **as observed by the device through the bunny CDN pull zone that fronts the Edge
Script**, not merely at the script's origin. The pull zone is free to answer or rewrite `OPTIONS`
itself, so the script's own response is not on its own sufficient: what the requirement constrains is
the response the **device** receives from the device-facing origin.

#### Scenario: OPTIONS does not advertise resumable

- **WHEN** an `OPTIONS` request is received for an upload path
- **THEN** the response does not advertise resumable-upload support, signaling a plain `PUT` path

#### Scenario: The device-visible OPTIONS response, through the CDN, yields a plain PUT

- **WHEN** the iOS background uploader preflights an upload path at the device-facing origin, and that
  preflight is answered by the pull zone rather than by the script
- **THEN** the response still advertises no resumable-upload support, and the uploader proceeds with a
  plain, single-shot `PUT` that the endpoint stores

### Requirement: Device manifest write gated on event existence

Before streaming the body of a `PUT /api/v1/events/<eventId>/devices/<deviceId>`, the endpoint SHALL
determine whether the event exists by reading the event marker `events/<eventId>/metadata.json` (a
bunny native Storage `GET` carrying the configured `AccessKey`) and SHALL pass the event-limits
capacity gate (capability `event-limits`), which additionally lists `events/<eventId>/devices/` to
classify the writing device as **known** (an active `<deviceId>.json` or departed
`<deviceId>.left.json` exists — a member's manifest update, or a rejoin reusing its own slot) or
**new** (neither exists). The gate SHALL resolve, in this order:

- marker absent — never created, or incomplete and therefore **gone** (capability `event-limits`) →
  `404`, nothing streamed, no upstream object `PUT`;
- the device is **new** and the ever-enrolled device count (active plus departed — leaving frees no
  slot) has reached the marker's `capacity` → `409`, nothing streamed;
- otherwise (a known device; a new device within capacity) → the endpoint SHALL proceed with the
  streamed manifest write.

**Capacity is the only refusal.** The gate SHALL NOT reject on time under any condition: while the
event exists, a device MAY enroll however long after the marker's `endsAt` it arrives, because a guest
who scans days late still holds in-window captures that belong in the event. The endpoint SHALL delete
nothing on touch.

A genuine upstream failure reading the marker or the listing (any non-`404` error or timeout) SHALL
be surfaced as `502` and SHALL NEVER be treated as "event absent" or "full" (never a `404`/`409` for a
transient read failure). This gate applies **only** to the device-manifest route; the byte route
`PUT /api/v1/files/devices/<deviceId>/<filename>` reads no marker, makes no listing, and is ungated.

#### Scenario: Manifest write to a non-existent event rejected

- **WHEN** a valid `PUT /api/v1/events/<uuid>/devices/<deviceUuid>` arrives but the marker
  `events/<uuid>/metadata.json` is absent
- **THEN** the endpoint responds `404`, streams no body, and issues no upstream object `PUT`

#### Scenario: Manifest write by a known device proceeds

- **WHEN** a valid `PUT /api/v1/events/<uuid>/devices/<deviceUuid>` arrives, the marker exists, and the
  device already has an active or departed manifest
- **THEN** the endpoint streams the JSON body to the upstream object `PUT` as usual

#### Scenario: First enrollment within capacity proceeds

- **WHEN** a valid `PUT /api/v1/events/<uuid>/devices/<deviceUuid>` arrives for an existing event from a
  device with no manifest in either form, and the ever-enrolled count is below the marker's `capacity`
- **THEN** the endpoint streams the JSON body to the upstream object `PUT` as usual

#### Scenario: A new device at capacity is rejected

- **WHEN** a valid `PUT /api/v1/events/<uuid>/devices/<deviceUuid>` arrives for an existing event from a
  device with no manifest in either form, and the ever-enrolled count (active plus departed) has
  reached the marker's `capacity`
- **THEN** the endpoint responds `409`, streams no body, and issues no upstream object `PUT`

#### Scenario: A new device enrolling after the event's window still proceeds

- **WHEN** a valid `PUT /api/v1/events/<uuid>/devices/<deviceUuid>` arrives from a device with no manifest in
  either form, long after the marker's `endsAt`, while the event still exists and is under capacity
- **THEN** the endpoint streams the JSON body to the upstream object `PUT` as usual — the gate makes
  no time-based refusal

#### Scenario: Marker or listing read failure is not treated as absence

- **WHEN** the marker read or the `devices/` listing returns a non-`404` upstream error or times out
- **THEN** the endpoint responds `502` and does not return `404` or `409`, and does not store the
  object

#### Scenario: Byte route makes no marker read

- **WHEN** a valid `PUT /api/v1/files/devices/<deviceId>/<filename>` arrives
- **THEN** the endpoint streams the body without reading any event marker and without listing any
  devices (the byte route is ungated)

### Requirement: Writes require a device token

Both write routes SHALL require a valid device token (capability `device-attestation`) in
`Authorization: Bearer`: the byte route `PUT /api/v1/files/devices/<deviceId>/<filename>` and the
device-manifest route `PUT /api/v1/events/<eventId>/devices/<deviceId>`. A request without one SHALL be rejected
with `401`, and the endpoint SHALL NOT stream the body, SHALL NOT read the event marker, and SHALL NOT
issue any upstream object `PUT`.

The byte route remains **ungated on event existence** — it still reads no marker, because bytes are
device-partitioned and event-independent. What changes is *who may write*, not *what is checked about the
event*. The token gate SHALL be applied **before** the event-existence gate on the manifest route, so an
unauthenticated caller cannot probe which events exist.

The token check SHALL cost no storage read: it is a signature verification, so the streaming upload path
gains no round-trip.

#### Scenario: An unauthenticated byte upload is refused before any streaming

- **WHEN** a `PUT /api/v1/files/devices/<uuid>/<name>` arrives with no valid token
- **THEN** the endpoint responds `401`, streams no body, and issues no upstream object `PUT`

#### Scenario: An attested byte upload proceeds unchanged

- **WHEN** a `PUT /api/v1/files/devices/<uuid>/<name>` carries a valid token
- **THEN** the body is streamed into one bunny native `PUT` exactly as before, with the same faithful
  `201`/`502` outcome

#### Scenario: An unauthenticated manifest write cannot probe event existence

- **WHEN** a `PUT /api/v1/events/<uuid>/devices/<uuid>` arrives with no valid token
- **THEN** the endpoint responds `401` without reading the event marker, so the response does not reveal
  whether the event exists

#### Scenario: OPTIONS remains reachable without a token

- **WHEN** the iOS uploader preflights an upload path with `OPTIONS` and sends no token
- **THEN** the preflight is answered as before, advertising no resumable upload, and the uploader proceeds
  with a plain `PUT`

## Verified on device (non-normative)

This section is **non-normative**. It records that the endpoint's **iOS-facing** surface — which could
never be exercised while bunny dropped iOS's zero-window upload SYNs, and which was therefore frozen
here as a list of assumptions — has now been measured on a physical iPhone SE2 (iOS 26.5) against the
live Edge Script, through its CDN pull zone
(`changes/archive/2026-07-14-migrate-runtime-to-bunny`, group 4):

- **OPTIONS fallback — confirmed, and the pull zone no longer shadows it.** `OPTIONS` returns the
  script's own `204` with `allow: PUT, OPTIONS` (`cdn-requestpullcode: 204` — the CDN forwards to origin
  and relays). The earlier finding that a BunnyCDN pull zone answers `OPTIONS` itself with a generic
  `200`, shadowing the script's handler (`changes/archive/2026-06-26-migrate-ios-upload-to-bunny`), is
  **stale**; bunny changed that behavior.
- **Accepted success codes — confirmed.** The OS-driven uploader accepts the endpoint's `201`: both
  resources of a real asset completed with `attempt=0` (first try, no retry).
- **Large-payload budget — confirmed.** A 4.5 MB Live-Photo paired video streamed through the CDN and
  the Edge Script into storage on the first attempt. Edge Scripting's 30 s budget is **CPU**, so a
  pass-through stream is cheap; the real ceiling is the **pull zone's 60 s request timeout**, which a
  Live Photo's ~2–3 s video does not approach. Server-side resumable uploads remain **deferred**.
- **Both upload tiers — confirmed.** The OS-driven PhotoKit extension (iOS ≥26.1) and the app-driven
  background-`URLSession` pump (iOS 18–26.0) each land uploads against this endpoint.
