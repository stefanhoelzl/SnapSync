# bunny list endpoint Specification

## Purpose

A read-only, per-event file listing on the backend (Deno + Hono), served by the same app as
`bunny-upload-endpoint`. `GET /event/<eventId>/files` returns a flat JSON array of every object
stored for the event, aggregated across all devices, authorized by possession of the event id
alone (no token, no registry — the same capability model as upload). Because bunny native Storage
LIST is per-directory (non-recursive), the endpoint fans out — listing the event directory for its
device sub-directories, then each device directory for its files — and flattens the result.

Its motivating consumer (a separate change) is a re-joined device pre-seeding its ledger: a
reinstall rotates the device's `deviceId` and wipes its ledger, so it reconciles against storage by
the reinstall-stable `filename` (which embeds the PHAsset `localIdentifier`), which requires
enumerating every device's objects for the event. Authoritative design: docs/design.md §3.1 (keys),
§4 (storage/auth).
## Requirements
### Requirement: Per-event file listing route

The backend SHALL accept an HTTP `GET` at the path template `/event/<eventId>/files` (the literal
labels `event` and `files` are required) and respond with a JSON array of the objects stored for
that event. `eventId` MUST match a UUID pattern. A request whose path does not match this route
(missing a label, wrong depth) SHALL yield `404`; a matched request whose `eventId` is not a UUID
SHALL yield `400`; neither case SHALL make an upstream request. A request using any method other
than `GET` on this path SHALL yield `404` (no matching route). The route SHALL be served by the same
application as the upload endpoint, so it is available on every deployment target without separate
configuration.

#### Scenario: Valid event id accepted

- **WHEN** a `GET` to `/event/<uuid>/files` arrives with a valid UUID
- **THEN** the endpoint responds `200` with a JSON array of the event's stored objects

#### Scenario: Non-UUID event id rejected

- **WHEN** the `eventId` segment is not a UUID
- **THEN** the endpoint responds `400` and makes no upstream request

#### Scenario: Unmatched path or wrong method rejected

- **WHEN** the path does not match `/event/<eventId>/files`, or the method is not `GET`
- **THEN** the endpoint responds `404` and makes no upstream request

### Requirement: Cross-device aggregation via per-directory walk

The endpoint SHALL return the objects of **all** devices under the event as a single flat array. It
SHALL obtain them from bunny native Storage's per-directory List Files API: first listing the event
directory `<zone>/<eventId>/` to discover the device sub-directories (the entries marked as a
directory), then listing each device directory `<zone>/<eventId>/<deviceId>/` to discover its files,
and flattening the files of every device into one array. Directory entries themselves SHALL NOT
appear in the result (only files). Each List request SHALL carry the storage zone's `AccessKey`
header from configuration.

#### Scenario: Files from every device are flattened

- **WHEN** the event directory lists two device directories and each contains files
- **THEN** the response is one flat array containing the files of both devices, and no directory
  entries

#### Scenario: Directory listing uses the storage AccessKey

- **WHEN** the endpoint lists the event directory or a device directory
- **THEN** the upstream List request carries the configured `AccessKey` header and never the account
  API key

### Requirement: Normalized entry shape

Each array element SHALL be an object with exactly the fields `filename`, `deviceId`, `size`, and
`lastModified`. `filename` SHALL be the object's name within its device directory (bunny's
`ObjectName`); `deviceId` SHALL be the device directory the object was listed under; `size` SHALL be
the object's byte length (bunny's `Length`); `lastModified` SHALL be the object's last-modified
timestamp (whichever of bunny's last-modified fields is present). The entry SHALL NOT include a
content type or the full storage key.

#### Scenario: Entry carries the four normalized fields

- **WHEN** a stored object is listed
- **THEN** its entry is `{ filename, deviceId, size, lastModified }`, where `deviceId` is the device
  directory it was found under, and carries no other fields

### Requirement: Empty or unknown event yields an empty array

A valid event id with no stored objects SHALL yield `200` with an empty array `[]` — whether the
event was never used or simply has no uploads yet. The endpoint SHALL NOT distinguish an unknown
event from an empty one (there is no event registry) and SHALL NOT respond `404` for a well-formed
event id that has no objects.

#### Scenario: No objects under the event

- **WHEN** a valid event id has no stored objects (the event directory lists nothing)
- **THEN** the endpoint responds `200` with `[]`

### Requirement: Faithful outcome — no partial list

The endpoint SHALL return a `2xx` array **only** when every required List request succeeds. If the
event-directory List or **any** per-device List fails (upstream error, timeout, or aborted
response), the endpoint SHALL respond `5xx` and SHALL NOT return a partial or truncated array, and
SHALL NEVER return `2xx` for an incomplete walk.

#### Scenario: A failed sub-listing fails the whole request

- **WHEN** listing one device directory returns an error or times out
- **THEN** the endpoint responds `5xx` and returns no array (not a partial list)

#### Scenario: All listings succeed

- **WHEN** the event-directory List and every per-device List succeed
- **THEN** the endpoint responds `200` with the complete flattened array

### Requirement: Authorization by event id only

Authorization to list an event SHALL be possession of the event id alone — the endpoint SHALL NOT
require any token and SHALL NOT consult an event registry, matching the upload endpoint's capability
model. The endpoint SHALL NOT expose or forward the bunny account API key.

#### Scenario: No token required

- **WHEN** a `GET /event/<uuid>/files` carries a valid event id but no authorization token
- **THEN** the listing is returned (the event id is the capability)

### Requirement: Listed filename round-trips with the uploaded filename

The `filename` of each listed entry SHALL be byte-identical to the filename the client used when
uploading the object, so a consumer can match listed objects against local resources by `filename`
equality. The upload path percent-encodes the filename on the wire, the backend decodes it and
re-encodes it into the storage key; the listing returns it such that a filename requiring
percent-encoding (e.g. containing a space or non-ASCII byte) round-trips to the same string the
client uploaded — neither double-encoded nor left in an encoded form.

#### Scenario: A percent-encoded filename round-trips through upload and listing
- **WHEN** a client uploads a filename that requires percent-encoding, and that object is later listed
- **THEN** the listed `filename` equals the original filename the client uploaded (no double-encoding,
  no residual `%XX`)

### Requirement: Listing completeness

The returned array SHALL contain **every** object stored under the event across all devices — not a
capped, sampled, or first-page subset. This completeness relies on bunny native Storage LIST
returning a directory's full contents in a single response (it is non-paginated); should that cease
to hold, the endpoint MUST follow continuation to preserve completeness rather than return a partial
page as `2xx`.

#### Scenario: A device directory with many files returns them all
- **WHEN** a device directory holds a large number of files and the event is listed
- **THEN** the response includes every file in that directory (no page cap)

