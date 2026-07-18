# event-rejoin-reconciliation — delta for add-ledger-event-provenance

## MODIFIED Requirements

### Requirement: Join reconciliation seeds already-stored photos as completed

A triggered reconciliation (in the extension) SHALL: fetch the **per-device** file listing
(`list(deviceId)`); **`resetTo`** (atomic clear-and-seed) the ledger to exactly one `COMPLETED` row
per stored filename, each keyed by that `filename`, carrying the `assetId` parsed from the
filename and the **configured `eventId` as provenance** (`sync-ledger`, "Event provenance and the
backfill sweep" — the seed is this join's own write, so no seeded row is ever a pre-provenance
sentinel row); **clear the discovery cursor** to force a full re-enumeration; and **on success set
the `joinedEventId` marker** to the configured `eventId`. The seed records no timestamp. The clear is
essential: it drops stale/phantom rows — e.g. a `REQUESTED` row left by a prior cycle whose upload
job never materialized, which the engine would otherwise read as in-flight and skip re-creating
forever — leaving the ledger as exactly the device's stored files. Because the byte store is
device-global and event-independent, this clear-and-seed both **restores** dedup after a reinstall
(the seed repopulates every globally-stored resource as `COMPLETED`) and **preserves** it across an
event switch (the global listing re-seeds the same files `COMPLETED`, carrying the **new** event's
id — which is what keeps a switch's provenance truthful without any sweep). A resource that is not in the
device's byte store is absent from the listing, is not seeded, and is uploaded idempotently by the
producer (last-write-wins). Setting the marker on success — even when zero rows were seeded — settles
the join so it does not re-trigger.

A **confirmed-successful** listing SHALL be treated as **authoritative** — whether it reports every,
some, or **none** of the ledger's prior `COMPLETED` files. The `resetTo` seeds exactly the stored
files, so any file the listing omits (a subset deletion, or a full storage reset that returns an empty
listing) is not seeded and is re-uploaded by the producer. In particular, an **empty** listing while
the ledger still holds `COMPLETED` rows means the objects were **deleted from storage** and SHALL
re-baseline the ledger to empty (re-uploading everything), NOT defer: a successful empty listing cannot
be a stale/transient read, because (a) an upload confirms its bytes before the job succeeds (capability
`bunny-upload-endpoint` never returns `2xx` for an unconfirmed upload), (b) the storage LIST reflects
writes and deletes immediately (read-after-write consistent), and (c) the list endpoint never returns a
`2xx` for a failed or partial listing (capability `bunny-list-endpoint`: a failure is `502`, surfaced
to the reconciliation as a fetch failure, not an empty array). The **only** untrustworthy signal — an
upstream error or a timeout — SHALL still defer (see "Extension defers uploads until the seed
succeeds"), leaving the ledger, cursor, and marker untouched so the next cycle retries; the ledger is
thus reset only ever on an authoritative listing.

#### Scenario: A stored resource is seeded completed

- **WHEN** the per-device listing reports stored files `a1-primary.jpg`, `a1-video.mov`
- **THEN** the ledger holds a `COMPLETED` row for each, carrying the `assetId` parsed from the filename and the configured event's id as provenance, and the marker is set

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

#### Scenario: A storage reset (empty listing against a non-empty ledger) re-baselines and re-uploads

- **WHEN** the per-device listing returns **empty** (a genuine storage reset) but the ledger already
  holds `COMPLETED` rows
- **THEN** the reconciliation `resetTo`s the ledger to empty, clears the cursor, and sets the marker, so
  the producer re-uploads every resource — it does **not** defer (a successful empty listing is
  authoritative, never a transient)

#### Scenario: A partial storage deletion re-uploads only the missing files

- **WHEN** the per-device listing reports a strict subset of the ledger's prior `COMPLETED` files (some
  objects were deleted from storage)
- **THEN** the `resetTo` seeds only the still-stored files `COMPLETED`, and the producer re-uploads
  exactly the omitted (deleted) files, leaving the still-stored ones untouched

#### Scenario: A not-yet-stored resource re-uploads idempotently

- **WHEN** a resource is absent from the per-device listing (never uploaded)
- **THEN** it is not seeded and the producer uploads it (any already-present resource overwritten last-write-wins)

