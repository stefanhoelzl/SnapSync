## MODIFIED Requirements

### Requirement: Event file list seam

The system SHALL define an `EventFilesSource` seam whose `list(eventId)` returns a `Result` of the
event's stored files — each carrying the `filename` — obtained from the backend per-event listing
(`GET /event/<id>/files`) over HTTPS. The seam SHALL surface failures as a failed `Result` (never a
thrown error to the caller), so the join can reduce them into state. A settable/fake implementation
SHALL exist for tests; the iOS implementation SHALL use an HTTP client against the compile-time
device-facing host.

#### Scenario: Successful listing returns the entries
- **WHEN** the backend returns the event's files
- **THEN** `list(eventId)` yields a success `Result` carrying one entry per stored file

#### Scenario: Upstream failure yields a failed Result
- **WHEN** the backend request fails (network error, non-2xx, timeout)
- **THEN** `list(eventId)` yields a failed `Result` and does not throw to the caller

### Requirement: Join reconciliation seeds already-stored photos as completed

A triggered join SHALL: set status `Joining`; fetch the event file list; enumerate the local photo
library for each resource's `(filename, assetId)`; compute the matched subset of local resources whose
`filename` equals a listed filename; seed those as `COMPLETED` via a single atomic ledger reset
(`resetTo`), with `updatedAt` set to the **join time**; clear the discovery cursor; then set status
`Joined`. Matching SHALL be by `filename` equality only (no size or timestamp comparison). Local
resources with no matching listed filename SHALL NOT be seeded (the producer uploads them later). The
seed SHALL run with the producer disabled.

#### Scenario: An already-stored photo is seeded completed
- **WHEN** a local resource's filename appears in the event file list
- **THEN** the ledger holds a `COMPLETED` row for that resource, with `updatedAt` set to the join time

#### Scenario: A locally-present photo not in the list is not seeded
- **WHEN** a local resource's filename does not appear in the event file list
- **THEN** no row is seeded for it and it remains eligible for upload

#### Scenario: Seeding clears the discovery cursor
- **WHEN** a join seeds the ledger
- **THEN** the discovery cursor is cleared so the producer performs a full re-enumeration

#### Scenario: A listed filename with no local match seeds nothing
- **WHEN** the event file list contains a filename that matches no local resource
- **THEN** no row is seeded for that filename

## REMOVED Requirements

### Requirement: Seeded version matches the producer's recomputed version
**Reason**: There is no content version anymore — uploaded resources are immutable. A seeded
`COMPLETED` row is identified by its `key`/`filename` alone, and the engine treats any `COMPLETED`
key as `AlreadyUploaded` regardless of content, so the producer never re-uploads a seeded resource.
**Migration**: The "skip the seeded resource" guarantee is now provided by `sync-engine`'s state-only
`ResourceChanged` decision (`COMPLETED` → `AlreadyUploaded`); no shared version derivation is needed.
