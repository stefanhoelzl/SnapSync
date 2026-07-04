## MODIFIED Requirements

### Requirement: Streaming proxy PUT

The endpoint SHALL accept an HTTP `PUT` on **two** write routes and, for each, forward the request
body to bunny native Storage by **streaming** — piping the request body into a single upstream `PUT`
without materializing the whole body in memory (it SHALL NOT buffer the body, e.g. via
`request.bytes()`/`arrayBuffer()`), and without hashing or transforming the body:

- (a) the **photo-byte** route `PUT /devices/<deviceId>/files/<filename>`, whose body is opaque binary
  resource bytes; and
- (b) the **device-manifest** route `PUT /event/<eventId>/device/<deviceId>`, whose body is a JSON
  device manifest.

The v1 byte routes `PUT /event/<eventId>/file/<filename>` and `PUT /files/device/<deviceId>/<filename>`
are **retired** (no longer routed). For each accepted write, exactly **one** upstream `PUT` of the body
SHALL be made; the only other upstream call permitted is the single small event-existence marker `GET`
on the device-manifest route's gate — the byte route makes no marker read (no other pre-checks,
retries, or fan-out).

#### Scenario: Byte body streamed to bunny

- **WHEN** a valid `PUT /devices/<deviceId>/files/<filename>` arrives with a body
- **THEN** the endpoint issues exactly one upstream `PUT` to bunny whose body is the request body
  passed through unchanged (byte-identical), and does not buffer the full body before forwarding

#### Scenario: Manifest body streamed to bunny

- **WHEN** a valid `PUT /event/<eventId>/device/<deviceId>` arrives with a JSON body for an existing
  event
- **THEN** the endpoint issues exactly one upstream `PUT` to bunny whose body is the request body
  passed through unchanged, and does not buffer the full body before forwarding

#### Scenario: Non-PUT methods rejected

- **WHEN** a request uses a method other than `PUT` or `OPTIONS` on a write path
- **THEN** the endpoint responds `404` (no matching route) and makes no upstream request

### Requirement: Object key from the URL path

The endpoint SHALL derive each route's params from the decoded path and write each object at a bare
storage key from which the URL labels are dropped:

- **Byte route** `/devices/<deviceId>/files/<filename>` (the literal labels `devices` and `files` are
  required): `deviceId` MUST match a UUID pattern; `filename` MUST be a single, non-empty segment
  containing no path separator (`/`, encoded `%2F`, or literal) and no `..`. The object SHALL be
  written at the bare key `devices/<deviceId>/files/<filename>` — the label ordering of the URL is
  preserved in the key — percent-encoding each segment when building the storage request URL so the
  key stays a single flat path.
- **Device-manifest route** `/event/<eventId>/device/<deviceId>` (the literal labels `event` and
  `device` are required): `eventId` and `deviceId` MUST each match a UUID pattern. The object SHALL be
  written at the bare key `events/<eventId>/device/<deviceId>.json` with `Content-Type:
  application/json`.

A request whose path does not match either route (missing a label, wrong depth, or no final segment)
SHALL yield `404`; a matched request whose UUID param is not a UUID, or whose `filename` is unsafe,
SHALL yield `400`. Neither case SHALL make an upstream request.

#### Scenario: Valid byte path accepted, device-namespace key composed

- **WHEN** the path is `/devices/<uuid>/files/IMG_0001-photo.jpg` with a valid UUID
- **THEN** the request is accepted and the storage key `devices/<uuid>/files/IMG_0001-photo.jpg` is
  composed for the upstream path

#### Scenario: Valid manifest path accepted, json key composed

- **WHEN** the path is `/event/<eventUuid>/device/<deviceUuid>` with valid UUIDs
- **THEN** the request is accepted and the storage key `events/<eventUuid>/device/<deviceUuid>.json`
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
`<key>` is the bare key derived per the key requirement (`devices/<deviceId>/files/<filename>` for the
byte route, `events/<eventId>/device/<deviceId>.json` for the device-manifest route). It SHALL forward
the request's `Content-Type` to bunny, defaulting to `application/octet-stream` when absent (the
device-manifest route carries `application/json`). Authorization of the *caller* is structural, not
token-based — the endpoint SHALL NOT require any token. The byte route is **ungated**: possession of
the edge host alone authorizes a `/devices/<deviceId>/files/` write (the device id is self-asserted).
The device-manifest route consults the event marker for **existence** only (see the device-manifest
gate requirement); that is an existence check, not an authorization step — any caller possessing a
valid, existing event id is authorized to write the manifest. The endpoint SHALL NOT expose or forward
the bunny account API key.

#### Scenario: Upstream URL composed from zone, region, and key

- **WHEN** a valid write is forwarded
- **THEN** the upstream URL is `https://<configured region host>/<configured zone>/<key>` and carries
  the `AccessKey` header from configuration

#### Scenario: Content-Type forwarded or defaulted

- **WHEN** the request carries a `Content-Type`
- **THEN** the same value is sent upstream; **WHEN** it is absent, `application/octet-stream` is sent

#### Scenario: No token required

- **WHEN** a request carries a valid path and body but no authorization token
- **THEN** the write is accepted (the byte route is ungated; the manifest route requires only an
  existing event id)

### Requirement: Last-write-wins

The endpoint SHALL perform each object write as a single unconditional `PUT` and SHALL NOT perform an
existence check (`HEAD`/`GET`) **on the object key** before writing; a write to an existing key
overwrites it. (The device-manifest route's event-existence marker `GET` is a separate read of
`events/<eventId>/metadata.json`, not of the object key, and does not make the object write
conditional.) The byte key is device-partitioned (`devices/<deviceId>/files/<filename>`), so a given
key is reachable only by the device that owns that partition; an overwrite is therefore same-device,
and because the `filename` embeds the per-device `localIdentifier` it targets the same physical asset
(byte-identical re-upload). The device-manifest key (`events/<eventId>/device/<deviceId>.json`) is
rewritten in full each cycle, so the latest write wins with no read-modify-write and no lost update.

#### Scenario: Existing byte key overwritten

- **WHEN** a `PUT` targets a `devices/<deviceId>/files/<filename>` key that already exists in the zone
- **THEN** the endpoint issues the upstream object `PUT` directly (no prior existence check on the
  object key) and the object is overwritten

#### Scenario: Existing manifest key overwritten

- **WHEN** a `PUT` targets a `events/<eventId>/device/<deviceId>.json` key that already exists, for an
  existing event
- **THEN** the endpoint issues the upstream object `PUT` directly (no prior existence check on the
  object key) and the manifest is overwritten with the new full-state snapshot

### Requirement: Device manifest write gated on event existence

Before streaming the body of a `PUT /event/<eventId>/device/<deviceId>`, the endpoint SHALL determine
whether the event exists by reading the event marker `events/<eventId>/metadata.json` (a bunny native
Storage `GET` carrying the configured `AccessKey`). When the marker is absent, the endpoint SHALL
respond `404` and SHALL NOT stream the body or issue the upstream object `PUT`. When the marker is
present, the endpoint SHALL proceed with the streamed manifest write. A genuine upstream failure
reading the marker (any non-`404` error or timeout) SHALL be surfaced as `502` and SHALL NEVER be
treated as "event absent" (never a `404` for a transient read failure). This gate applies **only** to
the device-manifest route; the byte route `PUT /devices/<deviceId>/files/<filename>` reads no marker and
is ungated.

#### Scenario: Manifest write to a non-existent event rejected

- **WHEN** a valid `PUT /event/<uuid>/device/<deviceUuid>` arrives but the marker
  `events/<uuid>/metadata.json` is absent
- **THEN** the endpoint responds `404`, streams no body, and issues no upstream object `PUT`

#### Scenario: Manifest write to an existing event proceeds

- **WHEN** a valid `PUT /event/<uuid>/device/<deviceUuid>` arrives and the marker
  `events/<uuid>/metadata.json` exists
- **THEN** the endpoint streams the JSON body to the upstream object `PUT` as usual

#### Scenario: Marker read failure is not treated as absence

- **WHEN** the marker read returns a non-`404` upstream error or times out
- **THEN** the endpoint responds `502` and does not return `404` or store the object

#### Scenario: Byte route makes no marker read

- **WHEN** a valid `PUT /devices/<deviceId>/files/<filename>` arrives
- **THEN** the endpoint streams the body without reading any event marker (the byte route is ungated)
