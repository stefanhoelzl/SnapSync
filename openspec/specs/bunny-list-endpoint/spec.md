# bunny list endpoint Specification

## Purpose

A read-only, per-event listing on the backend (Deno + Hono), served by the same app as
`bunny-upload-endpoint`. `GET /event/<eventId>/files` returns a JSON array of the event's **complete
assets** — computed at read time by reading each per-asset manifest (`asset-manifest`) against the
stored objects and including an asset only when every resource it names is present — authorized by
possession of the event id alone (no token, no registry — the same capability model as upload).
Objects live directly under `<eventId>/` (the flat key scheme), so a single bunny native Storage LIST
of the event directory discovers them; each manifest's content is then read to learn its resource set.

Its motivating consumer is a re-joined device pre-seeding its ledger: a reinstall wipes its ledger, so
it reconciles against storage, seeding the resources of each complete asset by the reinstall-stable
`filename`. Because a complete asset is immutable, a complete result is cacheable permanently.
Authoritative design: docs/design.md §3.1 (keys, manifest, read-time completeness), §4 (storage/auth).
## Requirements
### Requirement: Per-event file listing route

The backend SHALL accept an HTTP `GET` at the path template `/event/<eventId>/files` (the literal
labels `event` and `files` are required) and respond with a JSON array of the **complete assets**
stored for that event — assets all of whose manifest-declared resources are present — not a flat list
of individual objects. `eventId` MUST match a UUID pattern. A request whose path does not match this
route (missing a label, wrong depth) SHALL yield `404`; a matched request whose `eventId` is not a
UUID SHALL yield `400`; neither case SHALL make an upstream request. A request using any method other
than `GET` on this path SHALL yield `404` (no matching route). The route SHALL be served by the same
application as the upload endpoint.

#### Scenario: Valid event id accepted

- **WHEN** a `GET` to `/event/<uuid>/files` arrives with a valid UUID
- **THEN** the endpoint responds `200` with a JSON array of the event's complete assets

#### Scenario: Non-UUID event id rejected

- **WHEN** the `eventId` segment is not a UUID
- **THEN** the endpoint responds `400` and makes no upstream request

#### Scenario: Unmatched path or wrong method rejected

- **WHEN** the path does not match `/event/<eventId>/files`, or the method is not `GET`
- **THEN** the endpoint responds `404` and makes no upstream request

### Requirement: Asset assembly from a single directory listing

The endpoint SHALL discover the event's objects with a **single** bunny native Storage List Files
request against the event directory `<zone>/<eventId>/` (objects are direct children; no
sub-directory fan-out). It SHALL then, for each manifest object (`<assetId>.manifest.json`) found in
that listing, read the manifest's content (a bunny native `GET`) to obtain the asset's declared
resource set. The List request and the manifest content reads SHALL each carry the storage zone's
`AccessKey` header from configuration and never the account API key. The single-LIST rule governs
**object discovery**; per-manifest content reads are permitted on top of it. A single event-existence
read (the marker `GET` of `events/<eventId>.json`) precedes this per the existence gate.

#### Scenario: Objects discovered with one LIST, manifests read for content

- **WHEN** the event directory holds manifest and resource objects
- **THEN** the endpoint enumerates them with one List request and reads each manifest's content to learn its resource set

#### Scenario: Reads use the storage AccessKey

- **WHEN** the endpoint lists the directory and reads manifests
- **THEN** every upstream request carries the configured `AccessKey` header and never the account API key

### Requirement: Normalized asset entry shape

Each array element SHALL be an asset object with exactly the fields `assetId`, `creationDate`, and
`resources`. `resources` SHALL be a non-empty array whose elements each carry exactly `role`,
`filename`, `contentType`, `originalFilename`, and `url`. `role`, `filename`, `contentType`, and
`originalFilename` SHALL be taken verbatim from the manifest; `url` SHALL be the absolute download URL
for that resource object, as defined by `bunny-download-endpoint` (this spec does not restate the URL
format — `bunny-download-endpoint` is its sole authority). Both field sets are closed: neither the
asset object nor a resource element SHALL carry any other field — no storage key, no last-modified, no
size.

#### Scenario: Asset entry carries the three normalized fields

- **WHEN** a complete asset is listed
- **THEN** its entry is `{ assetId, creationDate, resources }` and carries no other fields

#### Scenario: Resource element carries the five fields

- **WHEN** a resource is listed inside an asset entry
- **THEN** it is `{ role, filename, contentType, originalFilename, url }` and carries no other field

#### Scenario: A resource url fetches the listed object

- **WHEN** a listed resource's `url` is fetched
- **THEN** the download endpoint returns the very object that resource describes (the round-trip guaranteed by `bunny-download-endpoint`)

### Requirement: Faithful outcome — no partial list

The endpoint SHALL return a `2xx` array **only** when the directory List and every required manifest
content read succeed at the transport level. If the List fails, or a manifest read fails with an
upstream error or timeout, the endpoint SHALL respond `5xx` and SHALL NOT return a partial or
truncated array, and SHALL NEVER return `2xx` for a failed List or manifest read. A manifest that is
successfully fetched but is malformed or missing required fields is **not** a transport failure: the
endpoint SHALL omit that asset and still return `2xx`.

#### Scenario: A failed listing fails the whole request

- **WHEN** the event-directory List returns an error or times out
- **THEN** the endpoint responds `5xx` and returns no array

#### Scenario: A manifest read transport failure fails the request

- **WHEN** a manifest content read returns an upstream error or times out
- **THEN** the endpoint responds `5xx` and returns no array

#### Scenario: A malformed manifest omits only its asset

- **WHEN** a manifest is fetched but cannot be parsed as the declared schema
- **THEN** that asset is omitted from the array and the request still responds `2xx`

### Requirement: Authorization by event id only

Authorization to list an event SHALL be possession of the event id alone — the endpoint SHALL NOT
require any token. The endpoint now consults the event registry (the marker) to determine
**existence** and SHALL respond `404` for an event that was never created; consulting the registry is
an existence check, not an authorization step — any caller possessing a valid, existing event id is
authorized to list it. The endpoint SHALL NOT expose or forward the bunny account API key.

#### Scenario: No token required

- **WHEN** a `GET /event/<uuid>/files` carries a valid, existing event id but no authorization token
- **THEN** the listing is returned (the event id is the capability)

#### Scenario: Account API key never exposed

- **WHEN** the endpoint lists an event
- **THEN** no response or upstream-facing surface exposes the bunny account API key

### Requirement: Listed resource filename round-trips with the uploaded filename

Each resource `filename` in the response SHALL be byte-identical to the filename the client used when
uploading that object, so a consumer can match listed resources against local resources by `filename`
equality. The upload path percent-encodes the filename on the wire and the backend decodes it into the
storage key; the listing returns it such that a filename requiring percent-encoding round-trips to the
same string the client uploaded — neither double-encoded nor left in an encoded form.

#### Scenario: A percent-encoded resource filename round-trips

- **WHEN** a client uploads a resource filename that requires percent-encoding, and that asset is later listed complete
- **THEN** the listed resource `filename` equals the original filename the client uploaded (no double-encoding, no residual `%XX`)

### Requirement: Listing completeness

The returned array SHALL contain **every complete asset** stored under the event — not a capped,
sampled, or first-page subset. This relies on bunny native Storage LIST returning a directory's full
contents in a single response (it is non-paginated); should that cease to hold, the endpoint MUST
follow continuation to preserve completeness rather than return a partial page as `2xx`.

#### Scenario: An event with many complete assets returns them all

- **WHEN** the event directory holds a large number of complete assets and the event is listed
- **THEN** the response includes every complete asset (no page cap)

### Requirement: Completeness computed from manifests, with immutable caching

An asset SHALL appear in the response **only when** its manifest object is present and every resource
the manifest names is present as an object in the event directory. An asset whose manifest is present
but is missing one or more named resources SHALL be omitted. Resource objects with no corresponding
manifest, and a manifest that is absent or not parseable as the declared schema, SHALL NOT yield an
asset entry (the asset is treated as not-yet-complete) and SHALL NOT fail the request. Because a
complete asset is immutable and permanent, the endpoint MAY serve a previously-computed complete asset
from cache without re-reading its manifest or re-checking its resources.

#### Scenario: All named resources present yields the asset

- **WHEN** a manifest is present and every resource it names exists as an object
- **THEN** the asset appears in the response

#### Scenario: A missing named resource omits the asset

- **WHEN** a manifest names a resource that is not present as an object
- **THEN** the asset is omitted from the response

#### Scenario: Orphan resources without a manifest yield no asset

- **WHEN** resource objects exist for an `assetId` but its manifest object is absent
- **THEN** no asset entry is produced for that `assetId`

#### Scenario: A complete asset may be served from cache

- **WHEN** an asset was previously computed complete and is listed again
- **THEN** the endpoint MAY return it without re-reading its manifest or re-checking its resources (a complete asset is permanent)

### Requirement: Listing gated on event existence

The endpoint SHALL determine whether the event exists before listing, by reading the event marker
`events/<eventId>.json` (a bunny native Storage `GET` carrying the configured `AccessKey`). When the
marker is absent, the endpoint SHALL respond `404` and SHALL NOT perform the directory LIST. When the
marker is present, the endpoint SHALL proceed to list the event directory. A genuine upstream failure
reading the marker (any non-`404` error or timeout) SHALL be surfaced as `5xx` and SHALL NOT be
treated as "event absent". A created event with no stored objects SHALL still respond `200` with an
empty array `[]` — existence (marker present) and emptiness (no objects) are distinct.

#### Scenario: Unknown event yields 404

- **WHEN** a `GET /event/<uuid>/files` arrives for an event whose marker `events/<uuid>.json` is absent
- **THEN** the endpoint responds `404` and performs no directory LIST

#### Scenario: Created-but-empty event yields empty array

- **WHEN** a valid event's marker exists but its directory `<uuid>/` contains no objects
- **THEN** the endpoint responds `200` with `[]`

#### Scenario: Created event with objects yields the array

- **WHEN** a valid event's marker exists and its directory contains files
- **THEN** the endpoint responds `200` with the flat array of those files

#### Scenario: Marker read failure is not treated as absence

- **WHEN** the marker read returns a non-`404` upstream error or times out
- **THEN** the endpoint responds `5xx` and does not return `404` or an array

