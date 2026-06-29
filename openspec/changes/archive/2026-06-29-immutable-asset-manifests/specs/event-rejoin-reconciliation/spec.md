## MODIFIED Requirements

### Requirement: Event file list seam

The system SHALL define an `EventFilesSource` seam whose `list(eventId)` returns a `Result` of the
event's **complete assets** — each carrying its `assetId` and its `resources` (each resource carrying
at least its `filename`) — obtained from the backend per-event listing (`GET /event/<id>/files`) over
HTTPS. The seam SHALL surface failures as a failed `Result` (never a thrown error to the caller), so
the join can reduce them into state. A settable/fake implementation SHALL exist for tests; the iOS
implementation SHALL use an HTTP client against the compile-time device-facing host.

#### Scenario: Successful listing returns the assets

- **WHEN** the backend returns the event's complete assets
- **THEN** `list(eventId)` yields a success `Result` carrying one entry per complete asset, each with its resources

#### Scenario: Upstream failure yields a failed Result

- **WHEN** the backend request fails (network error, non-2xx, timeout)
- **THEN** `list(eventId)` yields a failed `Result` and does not throw to the caller

### Requirement: Join reconciliation seeds already-stored photos as completed

A triggered join SHALL: set status `Joining`; fetch the event's complete-asset list; seed
`COMPLETED` — via a single atomic ledger reset (`resetTo`) — one row per resource of each listed
complete asset, keyed by the resource `filename` and carrying the asset's `assetId`; clear the
discovery cursor; then set status `Joined`. The seed records no timestamp (the join reads no clock).
Only the resources of **complete** assets SHALL be seeded. An asset that is still uploading is absent
from the listing and SHALL NOT be seeded; it SHALL be (re-)uploaded by the producer — including the
rare partially-stored asset, whose already-present resources re-upload idempotently (last-write-wins).
The seed SHALL run with the producer disabled.

#### Scenario: A complete asset's resources are seeded completed

- **WHEN** the listing reports an asset complete with resources `r1`, `r2`
- **THEN** the ledger holds a `COMPLETED` row for each of `r1` and `r2`, carrying the asset's `assetId`

#### Scenario: An asset absent from the listing is not seeded

- **WHEN** an asset is not present in the complete-asset listing (never uploaded, or only partially stored)
- **THEN** no row is seeded for its resources and they remain eligible for upload

#### Scenario: Seeding clears the discovery cursor

- **WHEN** a join seeds the ledger
- **THEN** the discovery cursor is cleared so the producer performs a full re-enumeration

#### Scenario: A partially-stored asset re-uploads idempotently

- **WHEN** an asset has some but not all of its resources stored (so it is absent from the complete-asset listing)
- **THEN** it is not seeded, and the producer re-uploads its resources — the already-present ones overwritten last-write-wins

### Requirement: Seeded rows are skipped by the producer

The resource `filename`s seeded from the listing SHALL be the same keys the upload producer derives
for those resources — the listing returns the very filenames the producer originally uploaded — so a
seeded `COMPLETED` row's key matches a later `ResourceChanged` for the same resource. Because an
uploaded resource is immutable, the engine treats any `COMPLETED` key as `AlreadyUploaded` regardless
of content, so the producer never re-uploads a seeded resource.

#### Scenario: Seeded resource is skipped by the producer

- **WHEN** the producer later enumerates a resource that the join seeded `COMPLETED`
- **THEN** the producer's decision is `AlreadyUploaded` and it creates no upload job
