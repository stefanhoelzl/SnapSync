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
header from configuration and never the account API key. A single event-existence read (the marker
`GET` of `events/<eventId>.json`) precedes this List per the existence gate; that read is separate
from and does not relax the single-LIST rule for the file listing itself.

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

