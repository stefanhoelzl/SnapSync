## REMOVED Requirements

### Requirement: Cross-device aggregation via per-directory walk
**Reason**: The storage key drops the `<deviceId>` level (`<eventId>/<deviceId>/<filename>` →
`<eventId>/<filename>`), so files are direct children of the event directory. There are no device
sub-directories to discover or flatten.
**Migration**: Replaced by "Single-directory event listing" — one non-recursive LIST of
`<eventId>/` returns the files directly.

## ADDED Requirements

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

## MODIFIED Requirements

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

### Requirement: Listing completeness

The returned array SHALL contain **every** object stored under the event — not a capped, sampled, or
first-page subset. This completeness relies on bunny native Storage LIST returning a directory's full
contents in a single response (it is non-paginated); should that cease to hold, the endpoint MUST
follow continuation to preserve completeness rather than return a partial page as `2xx`.

#### Scenario: An event directory with many files returns them all
- **WHEN** the event directory holds a large number of files and the event is listed
- **THEN** the response includes every file in that directory (no page cap)
