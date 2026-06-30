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

The system SHALL define a per-device file-listing seam whose `list(deviceId)` returns a `Result` of
the **filenames the device has stored** — the raw object listing under the device's byte partition,
each entry carrying at least its `filename` (the bare `<assetId>-<role>.<ext>`) — obtained from the
backend **per-device** listing (`GET /files/device/<deviceId>`) over HTTPS. This replaces the former
per-event complete-asset listing (`GET /event/<id>/files`): the source of seed truth is now the
device's event-independent byte store, not any single event. The seam SHALL surface failures as a
failed `Result` (never a thrown error to the caller), so the join can reduce them into state. A
settable/fake implementation SHALL exist for tests; the iOS implementation SHALL use an HTTP client
against the compile-time device-facing host.

#### Scenario: Successful listing returns the device's stored filenames

- **WHEN** the backend returns the device's stored objects
- **THEN** `list(deviceId)` yields a success `Result` carrying one entry per stored file, each with its `filename`

#### Scenario: Upstream failure yields a failed Result

- **WHEN** the backend request fails (network error, non-2xx, timeout)
- **THEN** `list(deviceId)` yields a failed `Result` and does not throw to the caller

### Requirement: Join reconciliation seeds already-stored photos as completed

A triggered reconciliation (in the extension) SHALL: fetch the **per-device** file listing
(`list(deviceId)`); **`resetTo`** (atomic clear-and-seed) the ledger to exactly one `COMPLETED` row
per stored filename, each keyed by that `filename` and carrying the `assetId` parsed from the
filename; **clear the discovery cursor** to force a full re-enumeration; and **on success set the
`joinedEventId` marker** to the configured `eventId`. The seed records no timestamp. The clear is
essential: it drops stale/phantom rows — e.g. a `REQUESTED` row left by a prior cycle whose upload
job never materialized, which the engine would otherwise read as in-flight and skip re-creating
forever — leaving the ledger as exactly the device's stored files. Because the byte store is
device-global and event-independent, this clear-and-seed both **restores** dedup after a reinstall
(the seed repopulates every globally-stored resource as `COMPLETED`) and **preserves** it across an
event switch (the global listing re-seeds the same files `COMPLETED`). A resource that is not in the
device's byte store is absent from the listing, is not seeded, and is uploaded idempotently by the
producer (last-write-wins). Setting the marker on success — even when zero rows were seeded — settles
the join so it does not re-trigger.

#### Scenario: A stored resource is seeded completed

- **WHEN** the per-device listing reports stored files `a1-primary.jpg`, `a1-video.mov`
- **THEN** the ledger holds a `COMPLETED` row for each, carrying the `assetId` parsed from the filename, and the marker is set

#### Scenario: The reset drops stale/phantom rows

- **WHEN** the ledger holds a non-`COMPLETED` row (e.g. a `REQUESTED` row from a prior cycle whose job never materialized) for a resource absent from the per-device listing and a reconciliation seeds
- **THEN** the `resetTo` clears that stale row, so the ledger holds exactly one `COMPLETED` row per listed filename and nothing else

#### Scenario: Reinstall restores dedup from an empty ledger

- **WHEN** the ledger is empty (a reinstall) and the device's byte store already holds prior resources
- **THEN** the clear-and-seed sets a `COMPLETED` row for every stored filename, so the producer re-uploads none of them

#### Scenario: A zero-row join still settles

- **WHEN** the per-device listing returns no files for a device with an empty byte store
- **THEN** no rows are seeded but the `joinedEventId` marker is set, so the next cycle does not re-reconcile

#### Scenario: A not-yet-stored resource re-uploads idempotently

- **WHEN** a resource is absent from the per-device listing (never uploaded)
- **THEN** it is not seeded and the producer uploads it (any already-present resource overwritten last-write-wins)

### Requirement: Seeded rows are skipped by the producer

The filenames seeded from the per-device listing SHALL be byte-identical to the keys the upload
producer derives for those resources — both the listing and the producer name a resource by the same
bare `<assetId>-<role>.<ext>` from the **shared gallery enumeration**, with no event scoping — so a
seeded `COMPLETED` row's key matches a later `ResourceChanged` for the same resource. Because an
uploaded resource is immutable, the engine treats any `COMPLETED` key as `AlreadyUploaded` regardless
of content, so the producer never re-uploads a seeded resource. Because the seed source is the
**device-global** listing, this skip now holds **across events**: a resource uploaded under one event
is re-seeded `COMPLETED` by the clear-and-seed and skipped after a switch to any other event, never
re-uploaded.

#### Scenario: Seeded resource is skipped by the producer

- **WHEN** the producer later enumerates a resource that the join seeded `COMPLETED`
- **THEN** the producer's decision is `AlreadyUploaded` and it creates no upload job

#### Scenario: Skip holds across an event switch

- **WHEN** a resource uploaded under one event is seeded `COMPLETED` and the device switches to a different event
- **THEN** the producer still decides `AlreadyUploaded` for that resource and re-uploads nothing already in the device byte store

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

The extension SHALL compare the configured `eventId` to the persisted `joinedEventId` marker. When
they **match** (a relaunch or re-provision of the already-joined event) the switch is a no-op: no
seed, no cursor clear, no re-projection, no marker write. When they **differ** — an event switch, a
reinstall with no marker, or a fresh provision — the extension SHALL **`resetTo`** (atomic
clear-and-seed) the ledger from the per-device listing; **clear the discovery cursor** to force a
full re-enumeration; **keep** the device-global accumulator intact and **re-project** the device
manifest (`device.json`) to the **new** event's storage path; and set the `joinedEventId` marker to
the configured `eventId`. The clear-and-seed makes the ledger exactly the device's stored files —
dropping stale/phantom rows — while the device-global listing re-seeds the same files `COMPLETED`, so
nothing already stored re-uploads; the cursor clear re-enumerates to find genuinely-unstored work
(the App-Group cursor survives an app upgrade, so without it a re-join would scan incrementally and
find nothing). After a **leave** (config absent), the extension SHALL clear the `joinedEventId`
marker **only** on its next cycle while **keeping** the ledger, cursor, and accumulator intact (the
ledger is device-global and valid across events), so a subsequent provision of any event runs a fresh
reconciliation without losing dedup.

#### Scenario: Re-provision of an already-joined event is a no-op

- **WHEN** the configured `eventId` equals the `joinedEventId` marker
- **THEN** no seed, no cursor clear, no re-projection, and no marker write occur; the ledger, cursor, and accumulator are unchanged

#### Scenario: A different event resets-and-seeds and clears the cursor

- **WHEN** the configured `eventId` differs from the marker
- **THEN** the ledger is `resetTo` (clear-and-seed) from the per-device listing, the discovery cursor is cleared, the accumulator is kept and `device.json` is re-projected to the new event path, and the marker is set — with the global listing re-seeding the same files `COMPLETED` so nothing already stored re-uploads

#### Scenario: A reinstall restores via the same clear-and-seed

- **WHEN** the marker is absent and the ledger is empty (a reinstall) for a configured event
- **THEN** the `resetTo` from the per-device listing restores the `COMPLETED` rows, the cursor is cleared, and the marker is set

#### Scenario: Leaving clears the marker but keeps dedup

- **WHEN** the user has left an event (config absent) and the extension next runs
- **THEN** the extension clears the `joinedEventId` marker **only** and keeps the ledger, cursor, and accumulator intact, so provisioning any event afterward runs a fresh reconciliation and re-uploads nothing already stored

