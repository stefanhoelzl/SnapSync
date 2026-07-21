# bunny-upload-endpoint Delta

## MODIFIED Requirements

### Requirement: Streaming proxy PUT

The endpoint SHALL accept an HTTP `PUT` on **two** write routes and, for each, forward the request
body to bunny native Storage by **streaming** — piping the request body into a single upstream `PUT`
without materializing the whole body in memory (it SHALL NOT buffer the body, e.g. via
`request.bytes()`/`arrayBuffer()`), and without hashing or transforming the body:

- (a) the **photo-byte** route `PUT /files/devices/<deviceId>/<filename>`, whose body is opaque binary
  resource bytes; and
- (b) the **device-manifest** route `PUT /events/<eventId>/devices/<deviceId>`, whose body is a JSON
  device manifest.

The v1 byte routes `PUT /event/<eventId>/file/<filename>` and `PUT /files/device/<deviceId>/<filename>`
are **retired** (no longer routed). For each accepted write, exactly **one** upstream `PUT` of the body
SHALL be made; the only other upstream calls permitted are the device-manifest route's gate reads —
the single small event-existence marker `GET` and the single `devices/` directory listing the limits
gate requires (capability `event-limits`) — the byte route makes no marker read and no listing (no
other pre-checks, retries, or fan-out). When the gate finds the event expired, the reap it triggers is
`event-limits`' own contract and not part of this write budget — the write itself is then refused, so
no upstream object `PUT` of the body is made.

#### Scenario: Byte body streamed to bunny

- **WHEN** a valid `PUT /files/devices/<deviceId>/<filename>` arrives with a body
- **THEN** the endpoint issues exactly one upstream `PUT` to bunny whose body is the request body
  passed through unchanged (byte-identical), and does not buffer the full body before forwarding

#### Scenario: Manifest body streamed to bunny

- **WHEN** a valid `PUT /events/<eventId>/devices/<deviceId>` arrives with a JSON body for an existing
  live event, from a device the limits gate admits
- **THEN** the endpoint issues exactly one upstream `PUT` to bunny whose body is the request body
  passed through unchanged, and does not buffer the full body before forwarding

#### Scenario: Non-PUT methods rejected

- **WHEN** a request uses a method other than `PUT` or `OPTIONS` on a write path
- **THEN** the endpoint responds `404` (no matching route) and makes no upstream request

### Requirement: Device manifest write gated on event existence

Before streaming the body of a `PUT /events/<eventId>/devices/<deviceId>`, the endpoint SHALL
determine whether the event exists by reading the event marker `events/<eventId>/metadata.json` (a
bunny native Storage `GET` carrying the configured `AccessKey`) and SHALL pass the event-limits
lifecycle and capacity gate (capability `event-limits`), which additionally lists
`events/<eventId>/devices/` to classify the writing device as **known** (an active `<deviceId>.json`
or departed `<deviceId>.left.json` exists) or **new** (neither exists). The gate SHALL resolve, in
this order:

- marker absent → `404`, nothing streamed, no upstream object `PUT`;
- event **expired** (past its grace period, or a legacy marker without limit fields) → the expiry
  reap is triggered and the request answered `404`, nothing streamed;
- event in **grace** and the device is **new** → `410`, nothing streamed;
- event **live**, the device is **new**, and the ever-enrolled device count has reached the marker's
  `capacity` → `409`, nothing streamed;
- otherwise (a known device in any non-expired state; a new device within capacity while live) →
  the endpoint SHALL proceed with the streamed manifest write.

A genuine upstream failure reading the marker or the listing (any non-`404` error or timeout) SHALL
be surfaced as `502` and SHALL NEVER be treated as "event absent", "over", or "full" (never a
`404`/`409`/`410` for a transient read failure). This gate applies **only** to the device-manifest
route; the byte route `PUT /files/devices/<deviceId>/<filename>` reads no marker, makes no listing,
and is ungated.

#### Scenario: Manifest write to a non-existent event rejected

- **WHEN** a valid `PUT /events/<uuid>/devices/<deviceUuid>` arrives but the marker
  `events/<uuid>/metadata.json` is absent
- **THEN** the endpoint responds `404`, streams no body, and issues no upstream object `PUT`

#### Scenario: Manifest write to a live event by a known device proceeds

- **WHEN** a valid `PUT /events/<uuid>/devices/<deviceUuid>` arrives, the marker exists, the event is
  not expired, and the device already has an active or departed manifest
- **THEN** the endpoint streams the JSON body to the upstream object `PUT` as usual

#### Scenario: First enrollment within capacity proceeds

- **WHEN** a valid `PUT /events/<uuid>/devices/<deviceUuid>` arrives for a live event from a device
  with no manifest in either form, and the ever-enrolled count is below the marker's `capacity`
- **THEN** the endpoint streams the JSON body to the upstream object `PUT` as usual

#### Scenario: A new device at capacity is rejected

- **WHEN** a valid `PUT /events/<uuid>/devices/<deviceUuid>` arrives for a live event from a device
  with no manifest in either form, and the ever-enrolled count (active plus departed) has reached the
  marker's `capacity`
- **THEN** the endpoint responds `409`, streams no body, and issues no upstream object `PUT`

#### Scenario: A new device during grace is rejected

- **WHEN** a valid `PUT /events/<uuid>/devices/<deviceUuid>` arrives during the event's grace period
  from a device with no manifest in either form
- **THEN** the endpoint responds `410`, streams no body, and issues no upstream object `PUT`

#### Scenario: An expired event is reaped and answered absent

- **WHEN** a valid `PUT /events/<uuid>/devices/<deviceUuid>` arrives after the event's grace period
  has elapsed
- **THEN** the expiry reap runs (capability `event-limits`) and the endpoint responds `404`, streams
  no body, and issues no upstream object `PUT`

#### Scenario: Marker or listing read failure is not treated as absence

- **WHEN** the marker read or the `devices/` listing returns a non-`404` upstream error or times out
- **THEN** the endpoint responds `502` and does not return `404`, `409`, or `410`, and does not store
  the object

#### Scenario: Byte route makes no marker read

- **WHEN** a valid `PUT /files/devices/<deviceId>/<filename>` arrives
- **THEN** the endpoint streams the body without reading any event marker and without listing any
  devices (the byte route is ungated)
