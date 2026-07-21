## MODIFIED Requirements

### Requirement: Union authorization, identity-blindness, and caching

The union SHALL be authorized by the **event id alone** — the event id is the capability. A device token
(capability `device-attestation`) is **optional**: a request that carries a valid token and a request that
carries none SHALL both be served the same union (subject to the existence gate below), because the union
is read-authorized by `eventId`-possession, not by attestation. A tokenless request SHALL therefore
**not** be rejected with `401`; the no-app download page (capability `web-event-download`) depends on
exactly this. The marker MAY be consulted for existence; existence-probing by a tokenless caller is an
accepted consequence of opening this read (decision record: `changes/web-event-download`). The endpoint
SHALL be **identity-blind**: it SHALL return every contributing device's complete assets, each tagged with
its `deviceId`, and SHALL NOT accept any "own device" / exclude parameter — skipping the caller's own
device is the client's concern, performed by `deviceId`. It SHALL NOT restrict the union to any
`deviceId`.

The response SHALL carry `Cache-Control: no-store, no-cache, max-age=0`. This directive is load-bearing
for **freshness**: the union is a live read over mutable manifests and listings, carrying time-limited
signed URLs, served through a bunny CDN pull zone that documents `no-cache` — not `no-store` — as the
directive suppressing its cache; a cached union would hand back stale membership and expiring signed URLs.
It SHALL NOT be relaxed. (It is no longer load-bearing for authorization: with the token optional, the
union response no longer varies by caller.) The endpoint SHALL NOT expose or forward the bunny account API
key.

#### Scenario: A tokenless union is served

- **WHEN** a `GET /events/<uuid>/files` carries a valid event id but no token
- **THEN** the endpoint serves the union (or `404` if the event does not exist), not `401` — the event id
  is the capability

#### Scenario: An attested union is returned

- **WHEN** a `GET /events/<uuid>/files` carries a valid token and a valid event id
- **THEN** the union is returned (the event id is the capability)

#### Scenario: Every contributing device is returned, tagged by id

- **WHEN** the event has multiple contributing devices with complete assets
- **THEN** the union contains all of their assets, each tagged with its owning `deviceId`, with no
  server-side own-device exclusion — and no filtering by any `deviceId`

#### Scenario: Response is non-cacheable

- **WHEN** the endpoint responds `200` with a union
- **THEN** the response carries `Cache-Control: no-store, no-cache, max-age=0`

#### Scenario: The CDN does not cache a union

- **WHEN** the same event's union is requested twice through the pull zone
- **THEN** each response is served from the origin (not a cached copy), reflecting any manifest or
  listing change between the two reads, and carrying freshly minted signed URLs

#### Scenario: Account API key never exposed

- **WHEN** the endpoint assembles the union
- **THEN** no response or upstream-facing surface exposes the bunny account API key
