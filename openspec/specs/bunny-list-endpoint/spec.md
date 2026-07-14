# bunny list endpoint Specification

## Purpose

A read-only, **per-device** listing on the backend (Deno + Hono), served by the same app as
`bunny-upload-endpoint`. `GET /devices/<deviceId>/files` returns a JSON array of the device's **raw
stored objects** under `devices/<deviceId>/files/` (each entry a `{filename, size, url}`) — a single bunny
native Storage LIST, with **no** manifest reads and **no** server-side completeness computation.
The byte store is device-partitioned and event-independent, so the listing is global to the device;
the app derives own-device completeness by intersecting this listing with its gallery enumeration
(`sync-status`). Authorized by possession of the device id alone (no token, no registry — the same
capability model as upload).

Its motivating consumer is a re-joined device pre-seeding its ledger: a reinstall wipes its ledger, so
it reconciles against storage, seeding the resources of each complete asset by the reinstall-stable
`filename`. Because a complete asset is immutable, a complete result is cacheable permanently.

This capability is the **sole authority on the download-URL format** — the listing's `url` is a presigned S3
GET the edge mints per object, which the device fetches directly from bunny, keeping the backend off the
download byte path.

Decision record: `changes/archive/2026-06-26-add-bunny-list-endpoint` (the listing),
`changes/archive/2026-07-02-add-s3-presigned-downloads` (presigned GET URLs),
`changes/archive/2026-07-06-restructure-storage-url-layout` (the current key layout).
## Requirements
### Requirement: Per-event file listing route

The backend SHALL accept an HTTP `GET` at the path template `/files/devices/<deviceId>` (the literal
labels `files` and `devices` are required) and respond with a flat JSON array of the **raw stored
objects** under that device's partition — one element per object, no manifest read, no completeness
computation. `deviceId` MUST match a UUID pattern. A request whose path does not match this route
(missing a label, wrong depth) SHALL yield `404`; a matched request whose `deviceId` is not a UUID
SHALL yield `400`; neither case SHALL make an upstream request. A request using any method other than
`GET` on this path SHALL yield `404` (no matching route). A valid request whose partition holds no
objects (empty or never-written) SHALL respond `200` with an empty array `[]`. The route SHALL be
served by the same application as the upload endpoint.

#### Scenario: Valid device id accepted

- **WHEN** a `GET` to `/files/devices/<uuid>` arrives with a valid UUID
- **THEN** the endpoint responds `200` with a flat JSON array of the device's stored objects

#### Scenario: Non-UUID device id rejected

- **WHEN** the `deviceId` segment is not a UUID
- **THEN** the endpoint responds `400` and makes no upstream request

#### Scenario: Unmatched path or wrong method rejected

- **WHEN** the path does not match `/files/devices/<deviceId>`, or the method is not `GET`
- **THEN** the endpoint responds `404` and makes no upstream request

#### Scenario: Empty or unknown partition yields empty array

- **WHEN** a valid device id's partition `/files/devices/<uuid>/` holds no objects (empty or never written)
- **THEN** the endpoint responds `200` with `[]`

### Requirement: Asset assembly from a single directory listing

The endpoint SHALL discover the device's objects with a **single** bunny native Storage List Files
request against the device directory `/files/devices/<deviceId>/` (objects are direct children; no
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
`files/devices/<deviceId>/<filename>`, each segment percent-encoded so the key stays one flat path),
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
- **THEN** its `url` is a path-style `https://<s3-host>/<zone>/files/devices/<deviceId>/<filename>`
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

### Requirement: Faithful outcome — no partial list

The endpoint SHALL return a `2xx` array **only** when the directory List succeeds at the transport
level. If the List fails with an upstream error or times out, the endpoint SHALL respond `502` and
SHALL NOT return a partial or truncated array, and SHALL NEVER return `2xx` for a failed List.

#### Scenario: A failed listing fails the whole request

- **WHEN** the device-directory List returns an error or times out
- **THEN** the endpoint responds `502` and returns no array

### Requirement: Authorization by event id only

The per-device list is addressed by the device-id path alone — the endpoint SHALL NOT require any
authorization token, and SHALL NOT consult any event id or event marker (the listing is
event-independent). Any caller possessing a valid device id is authorized to list that device's
partition. The endpoint SHALL NOT expose or forward the bunny account API key.

#### Scenario: No token required

- **WHEN** a `GET /files/devices/<uuid>` carries a valid device id but no authorization token
- **THEN** the listing is returned (the device id is the capability)

#### Scenario: Account API key never exposed

- **WHEN** the endpoint lists a device partition
- **THEN** no response or upstream-facing surface exposes the bunny account API key

### Requirement: Listed resource filename round-trips with the uploaded filename

Each `filename` in the per-device list response SHALL be byte-identical to the filename the client used
when uploading that object, so a consumer can match listed files against local resources by `filename`
equality. The upload path percent-encodes the filename on the wire and the backend decodes it into the
storage key; the listing returns it such that a filename requiring percent-encoding round-trips to the
same string the client uploaded — neither double-encoded nor left in an encoded form.

#### Scenario: A percent-encoded filename round-trips

- **WHEN** a client uploads a filename that requires percent-encoding, and that object is later listed
- **THEN** the listed `filename` equals the original filename the client uploaded (no double-encoding, no residual `%XX`)

### Requirement: Event-wide union read route

The backend SHALL accept an HTTP `GET` at the path template `/events/<eventId>/files` (the literal
labels `events` and `files` are required) and respond with a flat JSON array of the event's
**complete** assets aggregated across **all** contributing devices. `eventId` MUST match a UUID
pattern. A request whose path does not match this route (missing a label, wrong depth) SHALL yield
`404`; a matched request whose `eventId` is not a UUID SHALL yield `400`; neither case SHALL make an
upstream request. A request using any method other than `GET` on this path SHALL yield `404` (no
matching route). The route SHALL be served by the same application as the per-device list, upload,
and download endpoints, so it is available on every deployment target without separate configuration.

#### Scenario: Valid event id accepted

- **WHEN** a `GET` to `/events/<uuid>/files` arrives with a valid UUID
- **THEN** the endpoint proceeds to assemble and respond with the event's complete-asset union

#### Scenario: Non-UUID event id rejected

- **WHEN** the `eventId` segment is not a UUID
- **THEN** the endpoint responds `400` and makes no upstream request

#### Scenario: Unmatched path or wrong method rejected

- **WHEN** the path does not match `/events/<eventId>/files`, or the method is not `GET`
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
List Files request against the device-manifest directory `events/<eventId>/devices/`; each direct-child
`<deviceId>.json` **or** `<deviceId>.left.json` object names one contributing device. **Both** active
and departed manifests contribute to the union — a departed device's already-shared photos remain
downloadable until the event is reaped — but a device SHALL be counted **once**: when both siblings are
present the endpoint SHALL read the **last-write-wins** winner (the newer of `<deviceId>.json` /
`<deviceId>.left.json` by the last-modified time in the listing; see `device-manifest`). An absent/empty
directory (bunny `404` or no children) SHALL be treated as "no contributors" → `200 []`. For each
enumerated device the endpoint SHALL read that device's winning manifest object under
`events/<eventId>/devices/` **and** LIST that device's byte partition `files/devices/<deviceId>/` (the
same single-LIST per-device read the per-device list route uses). Every upstream request (the
manifest-directory LIST, each manifest read, each per-device file LIST) SHALL carry the storage zone's
`AccessKey` header from configuration and never the account API key. The stored device manifest is
**already** the device's configured date-filtered projection (its per-membership capture-date cutoff,
`device-manifest` / `photo-date-cutoff`), so the union SHALL trust its `assets` list and SHALL NOT
re-apply any date filter.

#### Scenario: Devices enumerated with one LIST
- **WHEN** the event has contributing devices
- **THEN** the endpoint enumerates them with one List of `events/<eventId>/devices/` and then, per
  device, reads its winning manifest and lists its byte partition `files/devices/<deviceId>/`

#### Scenario: A departed device's photos remain in the union
- **WHEN** a device has only a `<deviceId>.left.json` manifest (it left the event, which still has other members)
- **THEN** its assets are included in the union (served from the departed manifest), so remaining members can still download them

#### Scenario: A device with both siblings is counted once via last-write-wins
- **WHEN** both `<deviceId>.json` and `<deviceId>.left.json` exist for a device
- **THEN** the endpoint reads only the newer sibling's manifest and includes that device's assets exactly once

#### Scenario: Empty manifest directory yields empty array
- **WHEN** `events/<eventId>/devices/` lists no `<deviceId>.json` or `<deviceId>.left.json` children (empty or `404`)
- **THEN** the endpoint responds `200` with `[]` and reads no manifest

#### Scenario: Reads use the storage AccessKey
- **WHEN** the endpoint performs any upstream read in the fan-out
- **THEN** that request carries the configured `AccessKey` header and never the account API key

#### Scenario: Manifest asset list is not re-filtered by date
- **WHEN** a device manifest lists its projected assets (already scoped by that device's configured cutoff)
- **THEN** the endpoint takes that asset list as the event's set for that device and applies no further date filtering

### Requirement: Union completeness — complete assets only

The endpoint SHALL include an asset in the union **only when every** resource the asset's manifest
entry names is present in that device's byte partition. Presence SHALL be tested by membership of each
resource's `key` (its storage object name) among the object names returned by that device's
`files/devices/<deviceId>/` LIST (decoded back to the uploaded name, the equality the
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

- **WHEN** a device's `files/devices/<deviceId>/` partition is empty or `404`
- **THEN** every asset of that device is omitted, and the partition `404` is not treated as a failure

### Requirement: Union entry shape

Each array element SHALL be an asset object carrying exactly `deviceId` (the owning device's id),
`assetId` (the device-local asset identity from the manifest), `creationDate` (the asset's capture
timestamp, ISO-8601, from the manifest), and `resources` (a non-empty array). Each resource element
SHALL carry exactly `role`, `contentType`, `key`, `filename`, `size`, and `url`: `role`,
`contentType`, `key`, and `filename` projected verbatim from the manifest resource (`key` the storage
object name, `filename` the human capture name); `size` the object's byte length from the device's
file partition listing; and `url` the **presigned S3 download URL** for that object, as defined by this
spec's "Presigned S3 download URL" requirement (the per-device list and the union share that single
authority, so both agree by construction). The field set is closed: no asset or resource element SHALL
carry any other field.

#### Scenario: Asset entry carries its four fields and owning device

- **WHEN** a complete asset is emitted
- **THEN** its entry is `{ deviceId, assetId, creationDate, resources }` and carries no other field,
  with `deviceId` the device whose manifest it came from

#### Scenario: Resource entry carries the six fields

- **WHEN** a resource is emitted
- **THEN** its entry is `{ role, contentType, key, filename, size, url }` and carries no other field

#### Scenario: A resource url fetches the listed object

- **WHEN** a union resource's `url` is fetched
- **THEN** bunny's S3 endpoint returns the very object that resource describes (per the "Presigned S3
  download URL" requirement)

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

- **WHEN** one device's `files/devices/<deviceId>/` LIST returns a non-`404` error or times out
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

