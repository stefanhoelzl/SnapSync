## MODIFIED Requirements

### Requirement: Per-event object download route

The backend SHALL accept an HTTP `GET` at the path template `/files/device/<deviceId>/<filename>`
(the literal labels `files` and `device` are required) and respond with the bytes of the stored
object. `deviceId` MUST match a UUID pattern; `filename` MUST be a single, non-empty segment
containing no path separator (`/`, encoded or literal) and no `..` — the same key-safety rule as the
upload route, since `GET` and `PUT` share this path. The route is **per-device** and carries no
event concept. A request whose path does not match the route (missing a label, wrong depth, or no
filename), or that uses any method other than `GET`, SHALL yield `404`; a matched request whose
`deviceId` is not a UUID or whose `filename` is unsafe SHALL yield `400`, and neither case SHALL
make an upstream request. The route SHALL be served by the same application as the upload and list
endpoints, so it is available on every deployment target without separate configuration.

#### Scenario: Valid download path accepted

- **WHEN** a `GET` to `/files/device/<uuid>/<name>` arrives with a valid UUID and a safe filename
- **THEN** the endpoint proceeds to fetch the stored object and stream it back

#### Scenario: Non-UUID device id rejected

- **WHEN** the `deviceId` segment is not a UUID
- **THEN** the endpoint responds `400` and makes no upstream request

#### Scenario: Unsafe filename rejected

- **WHEN** the `filename` segment contains `..` or a separator (`/` or its encoded `%2F`)
- **THEN** the endpoint responds `400` and makes no upstream request

#### Scenario: Unmatched path or wrong method rejected

- **WHEN** the path does not match the route (missing a label, wrong depth, or no filename), or the
  method is not `GET`
- **THEN** the endpoint responds `404` and makes no upstream request

### Requirement: Single ungated streaming object GET

The endpoint SHALL serve the object with a **single** bunny native Storage `GET` of the object key
`files/<deviceId>/<filename>` (each segment percent-encoded when building the storage URL, so the
key stays one flat path — the same key the upload route writes), and SHALL stream the upstream
response body straight through to the client without buffering the whole body in memory. The `GET`
SHALL carry the storage zone's `AccessKey` header from configuration and never the account API key.
Exactly one upstream request SHALL be made per download.

The route is **ungated**: it has no event concept and reads no event marker before serving — the
object `GET` alone already yields faithful absence (see the read-outcome requirement), so any marker
read would be pure added latency on the feature's hot path.

#### Scenario: Object streamed from bunny

- **WHEN** a valid download request arrives for a stored object
- **THEN** the endpoint issues exactly one upstream `GET` of `files/<deviceId>/<filename>` and
  streams its body through to the client without buffering the full body

#### Scenario: No marker read precedes the download

- **WHEN** a valid download request arrives
- **THEN** the endpoint reads no event marker and proceeds directly to the object `GET`

#### Scenario: Storage AccessKey used, account API key never exposed

- **WHEN** the endpoint fetches the object
- **THEN** the upstream `GET` carries the configured `AccessKey` header and no response or
  upstream-facing surface exposes the bunny account API key

### Requirement: Missing object and unknown event are indistinguishably 404

There is no event concept on this per-device route, so the endpoint SHALL respond `404` for any
object that is not stored under `files/<deviceId>/<filename>`. A missing object is simply `404`:
the caller asked for a specific object and "not there" is the honest answer, and absence is not
disambiguated into any further signal.

#### Scenario: Missing object yields 404

- **WHEN** a download targets a `deviceId`/`filename` whose object is not stored
- **THEN** the endpoint responds `404`

#### Scenario: Unknown device partition yields 404

- **WHEN** a download targets a `deviceId` that has never stored the named object
- **THEN** the endpoint responds `404`, identical to any other missing-object case

### Requirement: Public download URL format

The download URL for a stored object SHALL be `<PUBLIC_BASE_URL>/files/device/<deviceId>/<filename>`
where `PUBLIC_BASE_URL` is the configured public origin (no trailing slash), the literal labels
`files` and `device` are present, and each path segment is percent-encoded (`deviceId` is a UUID so
its encoding is identity; `filename` is percent-encoded). This capability is the sole authority on
the public download-URL format, and it is the **sole builder** used by the per-device list `url`
field, so the listing and the download endpoint agree by construction. A URL built this way for a
stored object SHALL, when fetched, return that object — the filename round-trips through URL-encode →
route-decode → storage re-encode to the same stored key (neither double-encoded nor left in an
encoded form), the same round-trip the upload and list filenames obey.

#### Scenario: URL composed from the public origin and the encoded key

- **WHEN** a download URL is produced for device `<uuid>` and a filename requiring percent-encoding
  (e.g. containing a space)
- **THEN** it is `<PUBLIC_BASE_URL>/files/device/<uuid>/<percent-encoded filename>` with the literal
  `files`/`device` labels and no trailing-slash artifact

#### Scenario: A produced URL fetches the object it names

- **WHEN** a download URL produced for a stored object is fetched
- **THEN** the endpoint returns that exact object (the filename round-trips with no double-encoding
  and no residual `%XX`)

### Requirement: Authorization by event id only

The download route is **ungated** and addressed solely by the `deviceId` in the path: there is no
event auth, no token, and no other credential. Reaching an object requires only knowing its
per-device path. The endpoint SHALL NOT expose or forward the bunny account API key.

#### Scenario: No token required

- **WHEN** a valid download request carries no authorization token
- **THEN** the object is returned (the device-id path alone addresses it)

#### Scenario: Account API key never exposed

- **WHEN** the endpoint serves a download
- **THEN** no response or upstream-facing surface exposes the bunny account API key
