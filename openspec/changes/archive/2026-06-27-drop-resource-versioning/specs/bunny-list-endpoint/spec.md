## MODIFIED Requirements

### Requirement: Normalized entry shape

Each array element SHALL be an object with exactly the fields `filename`, `size`, and `url`.
`filename` SHALL be the object's name within the event directory (bunny's `ObjectName`); `size` SHALL
be the object's byte length (bunny's `Length`); `url` SHALL be the absolute download URL for that
object, as defined by `bunny-download-endpoint` (this spec does NOT restate the URL format —
`bunny-download-endpoint` is its sole authority). The field set is closed: the entry SHALL NOT include
any other field — no `lastModified`, no `deviceId`, no content type, and not the full storage key. The
storage last-modified timestamp is deliberately omitted: it is a storage-clock value with no consumer
(the re-join seed now timestamps its rows with the join time, not a listed date). Exposing `url` is
**not** a leak of the storage key: `url` is the backend's own public route, stable because the backend
owns it and revealing nothing about the storage backend, whereas the storage key
(`<zone>/<eventId>/<filename>`) remains internal and unexposed.

#### Scenario: Entry carries the three normalized fields

- **WHEN** a stored object is listed
- **THEN** its entry is `{ filename, size, url }` and carries no other fields (no `lastModified`, no
  `deviceId`, no storage key)

#### Scenario: The entry url fetches the listed object

- **WHEN** a listed entry's `url` is fetched
- **THEN** the download endpoint returns the very object that entry describes (the round-trip
  guaranteed by `bunny-download-endpoint`)
