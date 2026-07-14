## MODIFIED Requirements

### Requirement: Authorization by event id only

The per-device list SHALL require a valid device token (capability `device-attestation`) in
`Authorization: Bearer`; a request without one SHALL be rejected with `401` and SHALL issue no upstream
`LIST`. **Which** partition is listed remains addressed by the device-id path alone — the endpoint SHALL
NOT consult any event id or event marker (the listing is event-independent), and any attested caller
possessing a valid device id is authorized to list that device's partition. The token establishes that
the caller is a genuine app instance; the device id remains the capability selecting the partition. The
endpoint SHALL NOT expose or forward the bunny account API key.

Gating this route matters beyond the listing itself: its `url` entries are presigned S3 GET URLs that are
fetched directly from bunny and cannot themselves be gated, so this route is the **only** place where
access to those bytes can be controlled.

#### Scenario: An unauthenticated listing is refused

- **WHEN** a `GET /files/devices/<uuid>` carries a valid device id but no valid token
- **THEN** the endpoint responds `401`, issues no `LIST`, and hands out no presigned URL

#### Scenario: An attested listing is returned

- **WHEN** a `GET /files/devices/<uuid>` carries a valid token and a valid device id
- **THEN** the listing is returned as before (the device id selects the partition)

#### Scenario: Account API key never exposed

- **WHEN** the endpoint lists a device partition
- **THEN** no response or upstream-facing surface exposes the bunny account API key

### Requirement: Union authorization, identity-blindness, and caching

The union SHALL require a valid device token (capability `device-attestation`) in `Authorization: Bearer`;
a request without one SHALL be rejected with `401`, and the endpoint SHALL NOT read the event marker (so
an unauthenticated caller cannot probe which events exist). **Which** union is returned remains addressed
by the event-id path alone — the event id is the capability; the marker is consulted for existence, never
for authorization. The endpoint SHALL be **identity-blind**: it SHALL return every contributing device's
complete assets, each tagged with its `deviceId`, and SHALL NOT accept any "own device" / exclude
parameter — skipping the caller's own device is the client's concern, performed by `deviceId`. It SHALL
NOT restrict the union to the token's `deviceId`.

The response SHALL carry `Cache-Control: no-store, no-cache, max-age=0` (the union is a live read over
mutable manifests and listings, carrying time-limited signed URLs, and it is served through a bunny CDN
pull zone that documents `no-cache` — not `no-store` — as the directive suppressing its cache). This
directive is now load-bearing for **authorization** as well as freshness: the pull zone forwards
`Authorization` to the origin but does **not** vary its cache key on it, so a cacheable gated response
would be served to a *different* device. It SHALL NOT be relaxed. The endpoint SHALL NOT expose or forward
the bunny account API key.

#### Scenario: An unauthenticated union is refused without probing existence

- **WHEN** a `GET /events/<uuid>/files` carries a valid event id but no valid token
- **THEN** the endpoint responds `401` and reads no marker, so the response does not reveal whether the
  event exists

#### Scenario: An attested union is returned

- **WHEN** a `GET /events/<uuid>/files` carries a valid token and a valid event id
- **THEN** the union is returned (the event id is the capability)

#### Scenario: Every contributing device is returned, tagged by id

- **WHEN** the event has multiple contributing devices with complete assets
- **THEN** the union contains all of their assets, each tagged with its owning `deviceId`, with no
  server-side own-device exclusion — and no filtering by the token's `deviceId`

#### Scenario: Response is non-cacheable

- **WHEN** the endpoint responds `200` with a union
- **THEN** the response carries `Cache-Control: no-store, no-cache, max-age=0`

#### Scenario: The CDN does not cache a union

- **WHEN** the same event's union is requested twice through the pull zone
- **THEN** each response is served from the origin (not a cached copy), reflecting any manifest or
  listing change between the two reads

#### Scenario: One device's authorized response is never served to another

- **WHEN** two devices request the same gated listing URL through the pull zone with different tokens
- **THEN** each response is produced by the origin for that request, and neither device receives the
  other's cached response

#### Scenario: Account API key never exposed

- **WHEN** the endpoint assembles the union
- **THEN** no response or upstream-facing surface exposes the bunny account API key
