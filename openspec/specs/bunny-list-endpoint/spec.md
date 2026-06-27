# bunny list endpoint Specification

## Purpose

A read-only, per-event file listing on the backend (Deno + Hono), served by the same app as
`bunny-upload-endpoint`. `GET /event/<eventId>/files` returns a flat JSON array of every object
stored for the event, authorized by possession of the event id alone (no token, no registry — the
same capability model as upload). Files live directly under `<eventId>/` (the flat key scheme), so a
single bunny native Storage LIST of the event directory returns them.

Its motivating consumer (a separate change) is a re-joined device pre-seeding its ledger: a
reinstall wipes its ledger, so it reconciles against storage by the reinstall-stable `filename`
(which embeds the PHAsset `localIdentifier`). Authoritative design: docs/design.md §3.1 (keys),
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

### Requirement: Single-directory event listing

The endpoint SHALL return the objects stored under the event as a single flat array obtained from a
**single** bunny native Storage List Files request against the event directory `<zone>/<eventId>/`.
Files are direct children of the event directory (the key is `<eventId>/<filename>`), so no
sub-directory discovery or per-directory fan-out is performed. Directory entries (if any) SHALL NOT
appear in the result (only files). The List request SHALL carry the storage zone's `AccessKey`
header from configuration and never the account API key.

#### Scenario: Event directory files are returned

- **WHEN** the event directory `<zone>/<eventId>/` contains files
- **THEN** the response is a flat array of those files, with no directory entries, obtained from one
  List request

#### Scenario: Directory listing uses the storage AccessKey

- **WHEN** the endpoint lists the event directory
- **THEN** the upstream List request carries the configured `AccessKey` header and never the account
  API key

### Requirement: Normalized entry shape

Each array element SHALL be an object with exactly the fields `filename`, `size`, and
`lastModified`. `filename` SHALL be the object's name within the event directory (bunny's
`ObjectName`); `size` SHALL be the object's byte length (bunny's `Length`); `lastModified` SHALL be
the object's last-modified timestamp (whichever of bunny's last-modified fields is present). The
entry SHALL NOT include a `deviceId`, a content type, or the full storage key.

#### Scenario: Entry carries the three normalized fields

- **WHEN** a stored object is listed
- **THEN** its entry is `{ filename, size, lastModified }` and carries no other fields (no `deviceId`)

### Requirement: Empty or unknown event yields an empty array

A valid event id with no stored objects SHALL yield `200` with an empty array `[]` — whether the
event was never used or simply has no uploads yet. The endpoint SHALL NOT distinguish an unknown
event from an empty one (there is no event registry) and SHALL NOT respond `404` for a well-formed
event id that has no objects.

#### Scenario: No objects under the event

- **WHEN** a valid event id has no stored objects (the event directory lists nothing)
- **THEN** the endpoint responds `200` with `[]`

### Requirement: Faithful outcome — no partial list

The endpoint SHALL return a `2xx` array **only** when the event-directory List succeeds. If that
List fails (upstream error, timeout, or aborted response), the endpoint SHALL respond `5xx` and SHALL
NOT return a partial or truncated array, and SHALL NEVER return `2xx` for a failed List.

#### Scenario: A failed listing fails the whole request

- **WHEN** the event-directory List returns an error or times out
- **THEN** the endpoint responds `5xx` and returns no array (not a partial list)

#### Scenario: The listing succeeds

- **WHEN** the event-directory List succeeds
- **THEN** the endpoint responds `200` with the complete array

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

The returned array SHALL contain **every** object stored under the event — not a capped, sampled, or
first-page subset. This completeness relies on bunny native Storage LIST returning a directory's full
contents in a single response (it is non-paginated); should that cease to hold, the endpoint MUST
follow continuation to preserve completeness rather than return a partial page as `2xx`.

#### Scenario: An event directory with many files returns them all
- **WHEN** the event directory holds a large number of files and the event is listed
- **THEN** the response includes every file in that directory (no page cap)

