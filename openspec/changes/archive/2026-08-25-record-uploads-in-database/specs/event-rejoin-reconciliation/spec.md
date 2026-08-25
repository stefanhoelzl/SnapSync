## MODIFIED Requirements

### Requirement: Event file list seam

The system SHALL define a per-device file-listing seam whose `list(deviceId)` returns a `Result` of
the **filenames the device has stored** — each entry carrying at least its `filename` (the bare
`<assetId>-<role>.<ext>`) — obtained from the backend **per-device** listing (capability
`api-endpoints`) over HTTPS. The source of seed truth is the device's event-independent stored
resources, not any single event. The seam SHALL surface failures as a failed `Result` (never a thrown
error to the caller), so the join can reduce them into state. A settable/fake implementation SHALL exist
for tests; the iOS implementation SHALL use an HTTP client against the compile-time device-facing host.

The listing is now served from the backend's own record of uploaded resources rather than from a storage
enumeration, and its entry shape drops the `size` field, which no consumer read. The client SHALL remain
tolerant of unknown fields, so the shape change requires no client release.

Reading the record rather than the byte store means a resource the backend has not recorded as uploaded is
not listed. That is the correct direction for this seam: seeding a row as `COMPLETED` for bytes the backend
cannot vouch for would suppress an upload that never happened.

#### Scenario: Successful listing returns the device's stored filenames

- **WHEN** the backend returns the device's stored resources
- **THEN** `list(deviceId)` yields a success `Result` carrying one entry per stored file, each with its `filename`

#### Scenario: Upstream failure yields a failed Result

- **WHEN** the backend request fails (network error, non-2xx, timeout)
- **THEN** `list(deviceId)` yields a failed `Result` and does not throw to the caller

#### Scenario: An unrecorded resource is not seeded

- **WHEN** the backend holds no `uploaded` record for a resource
- **THEN** the listing omits it, so the reconciler does not seed a `COMPLETED` row that would suppress a
  needed upload
