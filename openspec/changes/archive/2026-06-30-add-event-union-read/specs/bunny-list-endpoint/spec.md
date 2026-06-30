## ADDED Requirements

### Requirement: Event-wide union read route

The backend SHALL accept an HTTP `GET` at the path template `/event/<eventId>/files` (the literal
labels `event` and `files` are required) and respond with a flat JSON array of the event's
**complete** assets aggregated across **all** contributing devices. `eventId` MUST match a UUID
pattern. A request whose path does not match this route (missing a label, wrong depth) SHALL yield
`404`; a matched request whose `eventId` is not a UUID SHALL yield `400`; neither case SHALL make an
upstream request. A request using any method other than `GET` on this path SHALL yield `404` (no
matching route). The route SHALL be served by the same application as the per-device list, upload,
and download endpoints, so it is available on every deployment target without separate configuration.

#### Scenario: Valid event id accepted

- **WHEN** a `GET` to `/event/<uuid>/files` arrives with a valid UUID
- **THEN** the endpoint proceeds to assemble and respond with the event's complete-asset union

#### Scenario: Non-UUID event id rejected

- **WHEN** the `eventId` segment is not a UUID
- **THEN** the endpoint responds `400` and makes no upstream request

#### Scenario: Unmatched path or wrong method rejected

- **WHEN** the path does not match `/event/<eventId>/files`, or the method is not `GET`
- **THEN** the endpoint responds `404` and makes no upstream request

### Requirement: Union event-existence gate

The union read SHALL be **gated on event existence**: before any aggregation it SHALL read the event
marker `events/<eventId>/metadata.json`. A marker that is absent (bunny `404`) SHALL yield `404`
"event not found" with no further upstream request. A non-`404` marker read failure SHALL yield `502`
(a transient failure is never mistaken for absence). When the marker is present the endpoint SHALL
proceed to the aggregation, responding `200` with `[]` for an event that exists but has no complete
foreign assets (no contributing devices, or none with a complete asset). The gate distinguishes an
unknown/typo'd event id (`404`) from a real-but-empty event (`200 []`).

#### Scenario: Unknown event yields 404

- **WHEN** the event marker is absent for the requested `eventId`
- **THEN** the endpoint responds `404` and performs no device enumeration

#### Scenario: Non-404 marker read failure yields 502

- **WHEN** the marker read fails with a non-`404` status, a connection error, or a timeout
- **THEN** the endpoint responds `502` and is never mistaken for "event absent"

#### Scenario: Existing event with no complete assets yields empty array

- **WHEN** the marker is present but no contributing device has a complete asset (including the case
  of no device manifests at all)
- **THEN** the endpoint responds `200` with `[]`

### Requirement: Union device enumeration and per-device fan-out

The endpoint SHALL discover the event's contributing devices with a **single** bunny native Storage
List Files request against the device-manifest directory `events/<eventId>/device/`; each
direct-child `<deviceId>.json` object names one contributing device. An absent/empty directory (bunny
`404` or no children) SHALL be treated as "no contributors" → `200 []`. For each enumerated device the
endpoint SHALL read that device's manifest object `events/<eventId>/device/<deviceId>.json` **and**
LIST that device's byte partition `files/<deviceId>/` (the same single-LIST per-device read the
per-device list route uses). Every upstream request (marker, manifest-directory LIST, each manifest
read, each per-device file LIST) SHALL carry the storage zone's `AccessKey` header from configuration
and never the account API key. The stored device manifest is **already** the event's date-filtered
projection, so the union SHALL trust its `assets` list and SHALL NOT re-apply any date filter.

#### Scenario: Devices enumerated with one LIST

- **WHEN** the event has contributing devices
- **THEN** the endpoint enumerates them with one List of `events/<eventId>/device/` and then, per
  device, reads its manifest and lists its byte partition

#### Scenario: Empty manifest directory yields empty array

- **WHEN** `events/<eventId>/device/` lists no `<deviceId>.json` children (empty or `404`)
- **THEN** the endpoint responds `200` with `[]` and reads no manifest

#### Scenario: Reads use the storage AccessKey

- **WHEN** the endpoint performs any upstream read in the fan-out
- **THEN** that request carries the configured `AccessKey` header and never the account API key

#### Scenario: Manifest asset list is not re-filtered by date

- **WHEN** a device manifest lists its projected assets
- **THEN** the endpoint takes that asset list as the event's set and applies no further date filtering

### Requirement: Union completeness — complete assets only

The endpoint SHALL include an asset in the union **only when every** resource the asset's manifest
entry names is present in that device's byte partition. Presence SHALL be tested by membership of each
resource's `key` (its storage object name) among the object names returned by that device's
`files/<deviceId>/` LIST (decoded back to the uploaded name, the equality the upload/list/download
round-trip guarantees). An asset with any named resource missing from the partition SHALL be **omitted**
from the union. A per-device file partition that is empty or absent (bunny `404`) SHALL be treated as
"no bytes present" — every asset of that device is incomplete and omitted — and SHALL NOT be a failure.

#### Scenario: Asset with all resources present is included

- **WHEN** every resource `key` of an asset is present in its device's file partition listing
- **THEN** that asset appears in the union

#### Scenario: Asset with a missing resource is omitted

- **WHEN** an asset's manifest names a resource whose `key` is not present in the device's file
  partition listing
- **THEN** that asset is omitted from the union (it is incomplete)

#### Scenario: Device with no bytes contributes nothing

- **WHEN** a device's `files/<deviceId>/` partition is empty or `404`
- **THEN** every asset of that device is omitted, and the partition `404` is not treated as a failure

### Requirement: Union entry shape

Each array element SHALL be an asset object carrying exactly `deviceId` (the owning device's id),
`assetId` (the device-local asset identity from the manifest), `creationDate` (the asset's capture
timestamp, ISO-8601, from the manifest), and `resources` (a non-empty array). Each resource element
SHALL carry exactly `role`, `contentType`, `key`, `filename`, `size`, and `url`: `role`,
`contentType`, `key`, and `filename` projected verbatim from the manifest resource (`key` the storage
object name, `filename` the human capture name); `size` the object's byte length from the device's
file partition listing; and `url` the absolute download URL for that object, built by
`bunny-download-endpoint`'s per-device object route from the owning `deviceId` and the resource `key`
(this spec does not restate the URL format — `bunny-download-endpoint` is its sole authority, so the
union, the per-device list, and the download route agree by construction). The field set is closed:
no asset or resource element SHALL carry any other field.

#### Scenario: Asset entry carries its four fields and owning device

- **WHEN** a complete asset is emitted
- **THEN** its entry is `{ deviceId, assetId, creationDate, resources }` and carries no other field,
  with `deviceId` the device whose manifest it came from

#### Scenario: Resource entry carries the six fields

- **WHEN** a resource is emitted
- **THEN** its entry is `{ role, contentType, key, filename, size, url }` and carries no other field

#### Scenario: A resource url fetches the listed object

- **WHEN** a union resource's `url` is fetched
- **THEN** the download endpoint returns the very object that resource describes (the round-trip
  guaranteed by `bunny-download-endpoint`)

### Requirement: Union faithful outcome — no partial union

The endpoint SHALL return a `2xx` union **only** when **every** required upstream read succeeds at the
transport level: the marker read, the device-manifest-directory LIST, every per-device manifest read
(including its JSON parse), and every per-device file LIST. If **any** of these fails with an upstream
error, a JSON parse failure, or a timeout, the endpoint SHALL respond `502` and SHALL NOT return a
partial or truncated union, and SHALL NEVER return `2xx` for an incomplete fan-out. A per-device file
partition `404` (no bytes) is **not** such a failure — it is handled as an empty partition.

#### Scenario: A failed manifest read fails the whole request

- **WHEN** one device's manifest read returns an error, times out, or yields unparseable JSON
- **THEN** the endpoint responds `502` and returns no union (not a partial array)

#### Scenario: A failed per-device file listing fails the whole request

- **WHEN** one device's `files/<deviceId>/` LIST returns a non-`404` error or times out
- **THEN** the endpoint responds `502` and returns no union

#### Scenario: All reads succeed

- **WHEN** the marker, the manifest-directory LIST, and every per-device manifest read and file LIST
  succeed
- **THEN** the endpoint responds `200` with the complete-only union across all devices

### Requirement: Union authorization, identity-blindness, and caching

The union is addressed by the event-id path alone — the endpoint SHALL NOT require any authorization
token (the event id is the capability; the marker is consulted for existence, not authorization). The
endpoint SHALL be **identity-blind**: it SHALL return every contributing device's complete assets,
each tagged with its `deviceId`, and SHALL NOT accept any "own device" / exclude parameter — skipping
the caller's own device is the client's concern, performed by `deviceId`. The response SHALL carry
`Cache-Control: no-store` (the union is a live read over mutable manifests and listings). The endpoint
SHALL NOT expose or forward the bunny account API key.

#### Scenario: No token required

- **WHEN** a `GET /event/<uuid>/files` carries a valid event id but no authorization token
- **THEN** the union is returned (the event id is the capability)

#### Scenario: Every contributing device is returned, tagged by id

- **WHEN** the event has multiple contributing devices with complete assets
- **THEN** the union contains all of their assets, each tagged with its owning `deviceId`, with no
  server-side own-device exclusion

#### Scenario: Response is non-cacheable

- **WHEN** the endpoint responds `200` with a union
- **THEN** the response carries `Cache-Control: no-store`

#### Scenario: Account API key never exposed

- **WHEN** the endpoint assembles the union
- **THEN** no response or upstream-facing surface exposes the bunny account API key
