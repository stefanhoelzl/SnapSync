## ADDED Requirements

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

A valid event id with no stored objects — whether the event was never used or simply has no uploads
yet — SHALL yield `200` with an empty array `[]`. The endpoint SHALL NOT distinguish an unknown
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
