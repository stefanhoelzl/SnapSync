## MODIFIED Requirements

### Requirement: Normalized asset entry shape

Each array element SHALL be a file object with exactly the fields `filename`, `size`, and `url`. The
field set is closed: an element SHALL NOT carry any other field — no storage key, no last-modified, no
content type, no role. `size` SHALL be the object's byte length as reported by the directory listing.
`url` SHALL be the **presigned S3 download URL** for that object, as defined by this spec's "Presigned
S3 download URL" requirement (the per-device list and the event-wide union share that single authority).
`filename` SHALL be the uploaded filename, decoded back from the stored key. Because each `url` is a
time-limited signed URL, the per-device list response SHALL carry
`Cache-Control: no-store, no-cache, max-age=0`.

All three directives are sent deliberately. The endpoint is fronted by a bunny CDN pull zone, and bunny
documents `no-cache` — **not** `no-store` — as the origin directive that suppresses pull-zone caching.
Sending `no-store` alone would leave the listing's cacheability resting on undocumented behavior, and a
cached listing would serve stale, expiring presigned URLs.

#### Scenario: File entry carries the three fields

- **WHEN** a stored object is listed
- **THEN** its entry is `{ filename, size, url }` and carries no other field

#### Scenario: A file url fetches the listed object

- **WHEN** a listed file's `url` is fetched
- **THEN** bunny's S3 endpoint returns the very object that entry describes (per the "Presigned S3
  download URL" requirement)

#### Scenario: The per-device list is non-cacheable

- **WHEN** the endpoint responds `200` with a per-device listing
- **THEN** the response carries `Cache-Control: no-store, no-cache, max-age=0` (its `url`s are
  time-limited signed URLs)

#### Scenario: The CDN does not cache a per-device listing

- **WHEN** the same per-device listing is requested twice through the pull zone
- **THEN** each response is served from the origin (not a cached copy) and carries freshly-signed `url`s

### Requirement: Union authorization, identity-blindness, and caching

The union is addressed by the event-id path alone — the endpoint SHALL NOT require any authorization
token (the event id is the capability; the marker is consulted for existence, not authorization). The
endpoint SHALL be **identity-blind**: it SHALL return every contributing device's complete assets,
each tagged with its `deviceId`, and SHALL NOT accept any "own device" / exclude parameter — skipping
the caller's own device is the client's concern, performed by `deviceId`. The response SHALL carry
`Cache-Control: no-store, no-cache, max-age=0` (the union is a live read over mutable manifests and
listings, carrying time-limited signed URLs, and it is served through a bunny CDN pull zone that
documents `no-cache` — not `no-store` — as the directive suppressing its cache). The endpoint SHALL NOT
expose or forward the bunny account API key.

#### Scenario: No token required

- **WHEN** a `GET /events/<uuid>/files` carries a valid event id but no authorization token
- **THEN** the union is returned (the event id is the capability)

#### Scenario: Every contributing device is returned, tagged by id

- **WHEN** the event has multiple contributing devices with complete assets
- **THEN** the union contains all of their assets, each tagged with its owning `deviceId`, with no
  server-side own-device exclusion

#### Scenario: Response is non-cacheable

- **WHEN** the endpoint responds `200` with a union
- **THEN** the response carries `Cache-Control: no-store, no-cache, max-age=0`

#### Scenario: The CDN does not cache a union

- **WHEN** the same event's union is requested twice through the pull zone
- **THEN** each response is served from the origin (not a cached copy), reflecting any manifest or
  listing change between the two reads

#### Scenario: Account API key never exposed

- **WHEN** the endpoint assembles the union
- **THEN** no response or upstream-facing surface exposes the bunny account API key
