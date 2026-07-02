## MODIFIED Requirements

### Requirement: Extension defers uploads until the seed succeeds

When a reconciliation is triggered, the extension SHALL fetch and seed **before** creating any upload jobs
that cycle. If the listing fetch fails, the extension SHALL create no upload jobs that cycle and SHALL
leave the `joinedEventId` marker **unset**, so it retries on its next cycle. There SHALL be no
user-facing join-failure state and no re-scan-to-retry affordance — retries are the extension's own
cadence, and status meanwhile comes from the app's listing read.

The device-listing fetch SHALL be bounded by an **explicit timeout** (`withTimeout`), mirroring the
device-manifest guard, so a hung network call cannot stall the OS-scheduled cycle. A timeout SHALL be
treated **identically to a failed fetch**: no rows are seeded, the ledger and cursor are left
untouched, the `joinedEventId` marker stays unset, and the next cycle retries. Only the network `LIST`
is bounded — the subsequent `resetTo(seeds)` remains a single atomic, un-timed transaction.

#### Scenario: The seed precedes any upload

- **WHEN** a reconciliation is triggered on a cycle
- **THEN** the seed completes before any upload job is created that cycle

#### Scenario: A fetch failure defers without settling

- **WHEN** the listing fetch fails during a triggered reconciliation
- **THEN** no upload jobs are created, the `joinedEventId` marker stays unset, and the next cycle retries

#### Scenario: A listing timeout defers without settling

- **WHEN** the device-listing fetch does not return within its bounded timeout
- **THEN** it is treated exactly as a failed fetch — no seed, the ledger and cursor untouched, the
  marker unset — and the next cycle retries

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

As a guard against a same-session-switch transient where a just-uploaded object is not yet listed, when
the listing returns **empty** *and* the ledger already holds `COMPLETED` rows, the reconciliation SHALL
**defer** — leaving the ledger, cursor, and marker untouched, exactly like a fetch failure — rather
than wiping the ledger to empty. An empty listing against an empty/absent-`COMPLETED` ledger (a genuine
fresh/empty device) still settles normally with zero seeded rows.

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

- **WHEN** the per-device listing returns no files for a device with an empty byte store and no
  `COMPLETED` rows in the ledger
- **THEN** no rows are seeded but the `joinedEventId` marker is set, so the next cycle does not re-reconcile

#### Scenario: An empty listing against a non-empty ledger defers

- **WHEN** the per-device listing returns **empty** but the ledger already holds `COMPLETED` rows
- **THEN** the reconciliation defers — the ledger, cursor, and marker are left untouched — rather than
  wiping the ledger, so a transiently-missing listing does not drop dedup

#### Scenario: A not-yet-stored resource re-uploads idempotently

- **WHEN** a resource is absent from the per-device listing (never uploaded)
- **THEN** it is not seeded and the producer uploads it (any already-present resource overwritten last-write-wins)
