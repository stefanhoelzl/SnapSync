# bunny list endpoint Specification

## Purpose

A read-only, **per-device** listing on the backend (Deno + Hono), served by the same app as
`bunny-upload-endpoint`. `GET /files/device/<deviceId>` returns a JSON array of the device's **raw
stored objects** under `files/<deviceId>/` (each entry a `{filename, size, url}`) — a single bunny
native Storage LIST, with **no** manifest reads and **no** server-side completeness computation.
The byte store is device-partitioned and event-independent, so the listing is global to the device;
the app derives own-device completeness by intersecting this listing with its gallery enumeration
(`sync-status`). Authorized by possession of the device id alone (no token, no registry — the same
capability model as upload).

Its motivating consumer is a re-joined device pre-seeding its ledger: a reinstall wipes its ledger, so
it reconciles against storage, seeding the resources of each complete asset by the reinstall-stable
`filename`. Because a complete asset is immutable, a complete result is cacheable permanently.
Authoritative design: docs/design.md §3.1 (keys, manifest, read-time completeness), §4 (storage/auth).
## Requirements
### Requirement: Per-event file listing route

The backend SHALL accept an HTTP `GET` at the path template `/files/device/<deviceId>` (the literal
labels `files` and `device` are required) and respond with a flat JSON array of the **raw stored
objects** under that device's partition — one element per object, no manifest read, no completeness
computation. `deviceId` MUST match a UUID pattern. A request whose path does not match this route
(missing a label, wrong depth) SHALL yield `404`; a matched request whose `deviceId` is not a UUID
SHALL yield `400`; neither case SHALL make an upstream request. A request using any method other than
`GET` on this path SHALL yield `404` (no matching route). A valid request whose partition holds no
objects (empty or never-written) SHALL respond `200` with an empty array `[]`. The route SHALL be
served by the same application as the upload endpoint.

#### Scenario: Valid device id accepted

- **WHEN** a `GET` to `/files/device/<uuid>` arrives with a valid UUID
- **THEN** the endpoint responds `200` with a flat JSON array of the device's stored objects

#### Scenario: Non-UUID device id rejected

- **WHEN** the `deviceId` segment is not a UUID
- **THEN** the endpoint responds `400` and makes no upstream request

#### Scenario: Unmatched path or wrong method rejected

- **WHEN** the path does not match `/files/device/<deviceId>`, or the method is not `GET`
- **THEN** the endpoint responds `404` and makes no upstream request

#### Scenario: Empty or unknown partition yields empty array

- **WHEN** a valid device id's partition `/files/<uuid>/` holds no objects (empty or never written)
- **THEN** the endpoint responds `200` with `[]`

### Requirement: Asset assembly from a single directory listing

The endpoint SHALL discover the device's objects with a **single** bunny native Storage List Files
request against the device directory `/files/<deviceId>/` (objects are direct children; no
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

### Requirement: Normalized asset entry shape

Each array element SHALL be a file object with exactly the fields `filename`, `size`, and `url`. The
field set is closed: an element SHALL NOT carry any other field — no storage key, no last-modified, no
content type, no role. `size` SHALL be the object's byte length as reported by the directory listing.
`url` SHALL be the absolute download URL for that object, as defined by `bunny-download-endpoint`'s
per-device object route (this spec does not restate the URL format — `bunny-download-endpoint` is its
sole authority). `filename` SHALL be the uploaded filename, decoded back from the stored key.

#### Scenario: File entry carries the three fields

- **WHEN** a stored object is listed
- **THEN** its entry is `{ filename, size, url }` and carries no other field

#### Scenario: A file url fetches the listed object

- **WHEN** a listed file's `url` is fetched
- **THEN** the download endpoint returns the very object that entry describes (the round-trip guaranteed by `bunny-download-endpoint`)

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

- **WHEN** a `GET /files/device/<uuid>` carries a valid device id but no authorization token
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

