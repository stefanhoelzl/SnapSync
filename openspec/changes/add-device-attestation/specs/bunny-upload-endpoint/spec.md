## ADDED Requirements

### Requirement: Writes require a device token

Both write routes SHALL require a valid device token (capability `device-attestation`) in
`Authorization: Bearer`: the byte route `PUT /files/devices/<deviceId>/<filename>` and the
device-manifest route `PUT /events/<eventId>/devices/<deviceId>`. A request without one SHALL be rejected
with `401`, and the endpoint SHALL NOT stream the body, SHALL NOT read the event marker, and SHALL NOT
issue any upstream object `PUT`.

The byte route remains **ungated on event existence** — it still reads no marker, because bytes are
device-partitioned and event-independent. What changes is *who may write*, not *what is checked about the
event*. The token gate SHALL be applied **before** the event-existence gate on the manifest route, so an
unauthenticated caller cannot probe which events exist.

The token check SHALL cost no storage read: it is a signature verification, so the streaming upload path
gains no round-trip.

#### Scenario: An unauthenticated byte upload is refused before any streaming

- **WHEN** a `PUT /files/devices/<uuid>/<name>` arrives with no valid token
- **THEN** the endpoint responds `401`, streams no body, and issues no upstream object `PUT`

#### Scenario: An attested byte upload proceeds unchanged

- **WHEN** a `PUT /files/devices/<uuid>/<name>` carries a valid token
- **THEN** the body is streamed into one bunny native `PUT` exactly as before, with the same faithful
  `201`/`502` outcome

#### Scenario: An unauthenticated manifest write cannot probe event existence

- **WHEN** a `PUT /events/<uuid>/devices/<uuid>` arrives with no valid token
- **THEN** the endpoint responds `401` without reading the event marker, so the response does not reveal
  whether the event exists

#### Scenario: OPTIONS remains reachable without a token

- **WHEN** the iOS uploader preflights an upload path with `OPTIONS` and sends no token
- **THEN** the preflight is answered as before, advertising no resumable upload, and the uploader proceeds
  with a plain `PUT`
