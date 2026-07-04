## MODIFIED Requirements

### Requirement: Per-event file listing route

The backend SHALL accept an HTTP `GET` at the path template `/devices/<deviceId>/files` (the literal
labels `devices` and `files` are required) and respond with a flat JSON array of the **raw stored
objects** under that device's partition — one element per object, no manifest read, no completeness
computation. `deviceId` MUST match a UUID pattern. A request whose path does not match this route
(missing a label, wrong depth) SHALL yield `404`; a matched request whose `deviceId` is not a UUID
SHALL yield `400`; neither case SHALL make an upstream request. A request using any method other than
`GET` on this path SHALL yield `404` (no matching route). A valid request whose partition holds no
objects (empty or never-written) SHALL respond `200` with an empty array `[]`. The route SHALL be
served by the same application as the upload endpoint.

#### Scenario: Valid device id accepted

- **WHEN** a `GET` to `/devices/<uuid>/files` arrives with a valid UUID
- **THEN** the endpoint responds `200` with a flat JSON array of the device's stored objects

#### Scenario: Non-UUID device id rejected

- **WHEN** the `deviceId` segment is not a UUID
- **THEN** the endpoint responds `400` and makes no upstream request

#### Scenario: Unmatched path or wrong method rejected

- **WHEN** the path does not match `/devices/<deviceId>/files`, or the method is not `GET`
- **THEN** the endpoint responds `404` and makes no upstream request

#### Scenario: Empty or unknown partition yields empty array

- **WHEN** a valid device id's partition `/devices/<uuid>/files/` holds no objects (empty or never written)
- **THEN** the endpoint responds `200` with `[]`

### Requirement: Asset assembly from a single directory listing

The endpoint SHALL discover the device's objects with a **single** bunny native Storage List Files
request against the device directory `/devices/<deviceId>/files/` (objects are direct children; no
sub-directory fan-out and no manifest content reads). Each direct-child object in that listing becomes
one entry in the response. The List request SHALL carry the storage zone's `AccessKey` header from
configuration and never the account API key. There is no per-object follow-up read: the listing's own
metadata (object name and size) is the sole source for each entry.

#### Scenario: Objects discovered with one LIST

- **WHEN** the device directory holds stored objects
- **THEN** the endpoint enumerates them with one List request and reads no further object content

#### Scenario: Reads use the storage AccessKey

- **WHEN** the endpoint lists the directory
- **THEN** the upstream List request carries the configured `AccessKey` header and never the account API key

### Requirement: Presigned S3 download URL

Each listed object's `url` SHALL be an **AWS SigV4 presigned S3 GET URL** for that object, minted by
the backend against the storage zone's S3-compatible endpoint. The URL SHALL be
`https://<s3-host>/<zone>/<key>?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=…&X-Amz-Date=…&X-Amz-Expires=604800&X-Amz-SignedHeaders=host&X-Amz-Signature=…`
(path-style; `<s3-host>` and region from configuration; `<key>` the bare object key
`devices/<deviceId>/files/<filename>`, each segment percent-encoded so the key stays one flat path),
signed with the storage zone's credentials (Access Key ID = the zone name, Secret = the storage-zone
`AccessKey`/password) and `X-Amz-Expires` of **7 days** (604800 s). The query signature is the **sole**
authorization: a consumer fetches the object **directly** from bunny's S3 endpoint with this URL and no
additional credential. This capability is the **sole authority** on the download-URL format (the former
`bunny-download-endpoint` proxy route is retired), and both the per-device list and the event-wide union
use it, so their `url` fields agree by construction. A **fresh** URL SHALL be minted on **every**
list/union response — there is no stored or cached URL — so each read yields a URL valid for a further 7
days. No response SHALL expose the storage-zone secret beyond the derived signature, and no response
SHALL expose the bunny account API key.

#### Scenario: A presigned S3 GET URL is returned

- **WHEN** a stored object is listed
- **THEN** its `url` is a path-style `https://<s3-host>/<zone>/devices/<deviceId>/files/<filename>`
  carrying `X-Amz-Algorithm=AWS4-HMAC-SHA256`, `X-Amz-Expires=604800`, and an `X-Amz-Signature`

#### Scenario: The URL fetches the object directly from bunny's S3 endpoint

- **WHEN** a listed `url` is fetched with **no** authorization header
- **THEN** bunny's S3 endpoint returns that exact object's bytes (the query signature authorizes the read)

#### Scenario: A fresh URL is minted on every response

- **WHEN** the same object is listed in two separate responses
- **THEN** each response carries an independently-signed `url`, each valid for 7 days from its own
  response time (no cached or reused signature)

#### Scenario: The storage secret is never exposed

- **WHEN** a `url` is minted
- **THEN** only the derived `X-Amz-Signature` appears; neither the storage-zone password nor the account
  API key is present in any response

### Requirement: Authorization by event id only

The per-device list is addressed by the device-id path alone — the endpoint SHALL NOT require any
authorization token, and SHALL NOT consult any event id or event marker (the listing is
event-independent). Any caller possessing a valid device id is authorized to list that device's
partition. The endpoint SHALL NOT expose or forward the bunny account API key.

#### Scenario: No token required

- **WHEN** a `GET /devices/<uuid>/files` carries a valid device id but no authorization token
- **THEN** the listing is returned (the device id is the capability)

#### Scenario: Account API key never exposed

- **WHEN** the endpoint lists a device partition
- **THEN** no response or upstream-facing surface exposes the bunny account API key

### Requirement: Union device enumeration and per-device fan-out

The endpoint SHALL discover the event's contributing devices with a **single** bunny native Storage
List Files request against the device-manifest directory `events/<eventId>/device/`; each
direct-child `<deviceId>.json` object names one contributing device. An absent/empty directory (bunny
`404` or no children) SHALL be treated as "no contributors" → `200 []`. For each enumerated device the
endpoint SHALL read that device's manifest object `events/<eventId>/device/<deviceId>.json` **and**
LIST that device's byte partition `devices/<deviceId>/files/` (the same single-LIST per-device read the
per-device list route uses). Every upstream request (marker, manifest-directory LIST, each manifest
read, each per-device file LIST) SHALL carry the storage zone's `AccessKey` header from configuration
and never the account API key. The stored device manifest is **already** the event's date-filtered
projection, so the union SHALL trust its `assets` list and SHALL NOT re-apply any date filter.

#### Scenario: Devices enumerated with one LIST

- **WHEN** the event has contributing devices
- **THEN** the endpoint enumerates them with one List of `events/<eventId>/device/` and then, per
  device, reads its manifest and lists its byte partition `devices/<deviceId>/files/`

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
`devices/<deviceId>/files/` LIST (decoded back to the uploaded name, the equality the
upload/list/download round-trip guarantees). An asset with any named resource missing from the
partition SHALL be **omitted** from the union. A per-device file partition that is empty or absent
(bunny `404`) SHALL be treated as "no bytes present" — every asset of that device is incomplete and
omitted — and SHALL NOT be a failure.

#### Scenario: Asset with all resources present is included

- **WHEN** every resource `key` of an asset is present in its device's file partition listing
- **THEN** that asset appears in the union

#### Scenario: Asset with a missing resource is omitted

- **WHEN** an asset's manifest names a resource whose `key` is not present in the device's file
  partition listing
- **THEN** that asset is omitted from the union (it is incomplete)

#### Scenario: Device with no bytes contributes nothing

- **WHEN** a device's `devices/<deviceId>/files/` partition is empty or `404`
- **THEN** every asset of that device is omitted, and the partition `404` is not treated as a failure
