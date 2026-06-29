# event-rejoin-reconciliation Specification

## Purpose
TBD - created by archiving change add-rejoin-reconciliation. Update Purpose after archive.
## Requirements
### Requirement: Reconciliation gate before enabling uploads

The **extension** SHALL run a join reconciliation on its own cycle, **before** creating any upload jobs,
exactly when an event is configured and its `eventId` differs from a persisted `joinedEventId` marker.
The `joinedEventId` marker — **not** ledger-emptiness — SHALL be the join signal, persisted across the
extension's short-lived processes. When the configured `eventId` equals the marker, the extension SHALL
NOT fetch, enumerate, or seed, and SHALL proceed to upload. When no event is configured, the extension
SHALL neither reconcile nor upload.

#### Scenario: Marker mismatch triggers a join

- **WHEN** the extension runs with an event configured whose `eventId` differs from the `joinedEventId` marker
- **THEN** a reconciliation runs before any upload job is created

#### Scenario: Marker match skips the join

- **WHEN** the configured `eventId` equals the `joinedEventId` marker
- **THEN** no fetch, enumeration, or seeding occurs and the producer uploads directly

#### Scenario: No event configured does nothing

- **WHEN** no event is configured
- **THEN** the extension neither reconciles nor uploads

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

A triggered reconciliation (in the extension) SHALL: fetch the event's complete-asset list; seed
`COMPLETED` — via a single atomic ledger reset (`resetTo`) — one row per resource of each listed complete
asset, keyed by the resource `filename` and carrying the asset's `assetId`; clear the discovery cursor;
and **on success set the `joinedEventId` marker** to the configured `eventId`. The seed records no
timestamp. Only the resources of complete assets SHALL be seeded; a partially-stored asset is absent from
the listing, is not seeded, and re-uploads idempotently (last-write-wins). Setting the marker on success —
even when zero rows were seeded — settles the join so it does not re-trigger.

#### Scenario: A complete asset's resources are seeded completed

- **WHEN** the listing reports an asset complete with resources `r1`, `r2`
- **THEN** the ledger holds a `COMPLETED` row for each of `r1` and `r2`, carrying the asset's `assetId`, and the marker is set

#### Scenario: A zero-row join still settles

- **WHEN** the listing returns no complete assets for a freshly provisioned event
- **THEN** no rows are seeded but the `joinedEventId` marker is set, so the next cycle does not re-reconcile

#### Scenario: Seeding clears the discovery cursor

- **WHEN** a reconciliation seeds the ledger
- **THEN** the discovery cursor is cleared so the producer performs a full re-enumeration

#### Scenario: A partially-stored asset re-uploads idempotently

- **WHEN** an asset has some but not all resources stored (absent from the complete-asset listing)
- **THEN** it is not seeded and the producer re-uploads its resources (already-present ones overwritten last-write-wins)

### Requirement: Seeded rows are skipped by the producer

The resource `filename`s seeded from the listing SHALL be the same keys the upload producer derives
for those resources — the listing returns the very filenames the producer originally uploaded — so a
seeded `COMPLETED` row's key matches a later `ResourceChanged` for the same resource. Because an
uploaded resource is immutable, the engine treats any `COMPLETED` key as `AlreadyUploaded` regardless
of content, so the producer never re-uploads a seeded resource.

#### Scenario: Seeded resource is skipped by the producer

- **WHEN** the producer later enumerates a resource that the join seeded `COMPLETED`
- **THEN** the producer's decision is `AlreadyUploaded` and it creates no upload job

### Requirement: Extension defers uploads until the seed succeeds

When a reconciliation is triggered, the extension SHALL fetch and seed **before** creating any upload jobs
that cycle. If the listing fetch fails, the extension SHALL create no upload jobs that cycle and SHALL
leave the `joinedEventId` marker **unset**, so it retries on its next cycle. There SHALL be no
user-facing join-failure state and no re-scan-to-retry affordance — retries are the extension's own
cadence, and status meanwhile comes from the app's listing read.

#### Scenario: The seed precedes any upload

- **WHEN** a reconciliation is triggered on a cycle
- **THEN** the seed completes before any upload job is created that cycle

#### Scenario: A fetch failure defers without settling

- **WHEN** the listing fetch fails during a triggered reconciliation
- **THEN** no upload jobs are created, the `joinedEventId` marker stays unset, and the next cycle retries

### Requirement: Event switch versus re-join

The extension SHALL compare the configured `eventId` to the persisted `joinedEventId` marker. When they
**differ** (an event switch, a reinstall with no marker, or a fresh provision), the extension SHALL reset
its ledger to empty, **reset the per-asset manifest markers**, and reconcile for the configured event,
then set the marker. When they **match**, relaunch or re-provision is a no-op (no reset, no re-seed).
After a **leave** (config absent), the extension SHALL reset its ledger, manifest markers, and clear the
marker on its next cycle, so a subsequent provision of any event reconciles it fresh.

The manifest-marker reset is required because the per-asset manifest dedup markers (capability
`asset-manifest`) are keyed by `assetId`, **not** by event: without clearing them on a reset, a device
switching to a new event would skip re-uploading its manifests and the new event's assets would never
read as complete (the resource bytes upload but no `<eventId>/<assetId>.manifest.json` is written).

#### Scenario: Re-provision of an already-joined event is a no-op

- **WHEN** the configured `eventId` equals the `joinedEventId` marker
- **THEN** the ledger is not reset, no re-seed occurs, and the producer stays as is

#### Scenario: A different event resets and reconciles

- **WHEN** the configured `eventId` differs from the marker
- **THEN** the extension resets the ledger to empty, resets the per-asset manifest markers, and reconciles for the new event, then sets the marker

#### Scenario: An event switch re-uploads manifests to the new event

- **WHEN** a device switches to a different event whose `eventId` differs from the marker
- **THEN** the per-asset manifest markers are reset so the new event's manifests are re-uploaded (its assets can read as complete), not skipped as already-done from the prior event

#### Scenario: Leaving clears the marker so the next provision reconciles fresh

- **WHEN** the user has left an event (config absent) and the extension next runs
- **THEN** the extension resets its ledger and clears the marker, so provisioning any event afterward runs a fresh reconciliation

