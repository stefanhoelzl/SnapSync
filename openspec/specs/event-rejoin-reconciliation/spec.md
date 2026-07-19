# event-rejoin-reconciliation Specification

## Purpose

The extension-side gate that runs **before** a (re)joined device uploads anything: it pulls the event's
stored-file listing, seeds the ledger with one `COMPLETED` row per already-stored resource, and only then
enables the producer. A device that re-joins therefore re-uploads **nothing** it has already contributed.

Without it, a device rejoining against an empty ledger — after a delete-and-reinstall (the App Group is
wiped) or a destructive ledger-schema migration — re-enumerates the whole library and re-uploads every
photo that is already sitting in storage. The reconcile is gated on a persisted `joinedEventId` marker
rather than ledger-emptiness, because the extension's process is short-lived and per-cycle: a zero-row join
keyed on emptiness would never settle.

This reconcile is also what keeps ledger-sourced status honest. `sync-status` classifies from the ledger
under a no-deletion-during-an-active-event invariant, and (re)join is the sole point where the ledger and
storage can diverge — seeding closes it.

Decision record: `changes/archive/2026-06-27-add-rejoin-reconciliation`.

Generalized from **the extension** to **the upload tier** (both tiers, reconciling inside the shared
`UploadCycle`) in `changes/archive/2026-07-12-fix-app-driven-upload-lifecycle` — it previously bound only the
iOS ≥26.1 extension, so the app-driven tier shipped with no reconciliation at all.
## Requirements
### Requirement: Reconciliation gate before enabling uploads

The **upload tier** SHALL run a join reconciliation on **its own upload cycle**, before creating any
upload jobs, exactly when an event is configured and its `eventId` differs from a persisted
`joinedEventId` marker. The upload tier is whichever process holds the `LedgerWriter` — the extension
on iOS ≥26.1, the app on iOS 18–26.0. The `joinedEventId` marker — **not** ledger-emptiness — SHALL be the join
signal, persisted across the tier's processes. When the configured `eventId` equals the marker, the
tier SHALL NOT fetch, enumerate, or seed, and SHALL proceed to upload. When no event is configured,
the tier SHALL neither reconcile nor upload.

The reconciliation SHALL be driven from the **shared upload cycle** (`UploadCycle`, `:domain`
`feature/upload`), not from each tier's composition root, and the cycle SHALL require a reconciliation to
be supplied — a tier that supplies none SHALL NOT compile. Reconciliation is therefore reached on
**every** route to a divergent ledger: a fresh join, an event switch, a leave-then-rejoin, and a
delete-and-reinstall (which no provisioning path observes, because a cold relaunch of an
already-joined app performs no provision).

#### Scenario: Marker mismatch triggers a join

- **WHEN** the upload tier runs a cycle with an event configured whose `eventId` differs from the `joinedEventId` marker
- **THEN** a reconciliation runs before any upload job is created

#### Scenario: Marker match skips the join

- **WHEN** the configured `eventId` equals the `joinedEventId` marker
- **THEN** no fetch, enumeration, or seeding occurs and the producer uploads directly

#### Scenario: No event configured does nothing

- **WHEN** no event is configured
- **THEN** the tier neither reconciles nor uploads

#### Scenario: Both tiers reconcile

- **WHEN** a (re)join occurs on iOS 18–26.0 (the app-driven tier) or on iOS ≥26.1 (the OS-driven tier)
- **THEN** the same marker-gated reconciliation runs on that tier's cycle before any upload job is created

#### Scenario: A cycle reconciles without any provision having run in its process

- **WHEN** a membership exists (e.g. re-joined after a reinstall, or saved by the other process) and a cycle runs in a process where no provisioning path ever executed, with no `joinedEventId` marker and an empty ledger
- **THEN** that cycle reconciles against the per-device listing and seeds already-stored resources as `COMPLETED` so none re-upload — the reconciliation is cycle-resident, never provision-gated

### Requirement: Event file list seam

The system SHALL define a per-device file-listing seam whose `list(deviceId)` returns a `Result` of
the **filenames the device has stored** — the raw object listing under the device's byte partition,
each entry carrying at least its `filename` (the bare `<assetId>-<role>.<ext>`) — obtained from the
backend **per-device** listing (capability `bunny-list-endpoint`) over HTTPS. This replaces the former
per-event complete-asset listing (`GET /events/<id>/files`): the source of seed truth is now the
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

### Requirement: Event switch versus re-join

The upload tier SHALL compare the configured `eventId` to the persisted `joinedEventId` marker. When
they **match** (a relaunch or re-provision of the already-joined event) the switch is a no-op: no
seed, no cursor clear, no re-projection, no marker write. When they **differ** — an event switch, a
reinstall with no marker, or a fresh provision — the tier SHALL **`resetTo`** (atomic clear-and-seed)
the ledger from the per-device listing; **clear the discovery cursor** to force a full re-enumeration;
**keep** the device-global accumulator intact and **re-project** the device manifest (`device.json`) to
the **new** event's storage path; and set the `joinedEventId` marker to the configured `eventId`. The
clear-and-seed makes the ledger exactly the device's stored files — dropping stale/phantom rows —
while the device-global listing re-seeds the same files `COMPLETED`, so nothing already stored
re-uploads; the cursor clear re-enumerates to find genuinely-unstored work (the App-Group cursor
survives an app upgrade, so without it a re-join would scan incrementally and find nothing).

After a **leave** (config absent), **no** lifecycle path SHALL clear the ledger or the accumulator
(`upload-lifecycle`, "Upload producer seam has no destructive verb"). The tier SHALL clear
the `joinedEventId` marker **only**, on its next cycle, while **keeping** the ledger and the
accumulator intact (the ledger is device-global and valid across events), so a subsequent provision of
any event runs a fresh reconciliation without losing dedup.

The property this defends is **dedup**: the ledger's `COMPLETED` rows are device-global and stay true
across a leave, a switch, and a re-join, so clearing them would re-upload every already-stored resource on
the next join (`sync-ledger`). The **discovery cursor is not dedup state** — a tier's `stop()` may clear it
as a repair for its own mechanism (`upload-lifecycle`), and this reconciliation clears it itself whenever
it re-baselines. Either way the cost is one full re-enumeration that finds nothing new, because the ledger
it did not touch still knows what is stored.

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

- **WHEN** the user has left an event (config absent) and the upload tier next runs
- **THEN** the tier clears the `joinedEventId` marker **only** and keeps the ledger and accumulator intact, so provisioning any event afterward runs a fresh reconciliation and re-uploads nothing already stored

#### Scenario: No lifecycle transition wipes the ledger

- **WHEN** a leave, an event switch, a re-provision, a permission change, or a direction change occurs on either tier
- **THEN** the ledger is never cleared by that transition; only a triggered reconciliation's `resetTo` ever re-baselines it

#### Scenario: A cleared cursor costs a re-enumeration, not a re-upload

- **WHEN** a transition leaves the discovery cursor cleared — by a tier's own `stop()` repair, or by this reconciliation's re-baseline
- **THEN** the next cycle enumerates the whole in-scope library and creates **no** upload job for anything already `COMPLETED`, because dedup lives in the ledger, not in the cursor

### Requirement: Upload tier defers uploads until the seed succeeds

When a reconciliation is triggered, the upload tier SHALL fetch and seed **before** creating any upload
jobs that cycle. If the listing fetch fails, the tier SHALL create no upload jobs that cycle and SHALL
leave the `joinedEventId` marker **unset**, so it retries on its next cycle. There SHALL be no
user-facing join-failure state and no re-scan-to-retry affordance — retries are the tier's own cadence,
and status meanwhile comes from the ledger read.

The device-listing fetch SHALL be bounded by an **explicit timeout** (`withTimeout`), mirroring the
device-manifest guard, so a hung network call cannot stall an OS-scheduled cycle (on the OS-driven
tier) or a `BGProcessingTask` window (on the app-driven tier). A timeout SHALL be treated **identically
to a failed fetch**: no rows are seeded, the ledger and cursor are left untouched, the `joinedEventId`
marker stays unset, and the next cycle retries. Only the network `LIST` is bounded — the subsequent
`resetTo(seeds)` remains a single atomic, un-timed transaction.

#### Scenario: The seed precedes any upload

- **WHEN** a reconciliation is triggered on a cycle, on either tier
- **THEN** the seed completes before any upload job is created that cycle

#### Scenario: A fetch failure defers without settling

- **WHEN** the listing fetch fails during a triggered reconciliation
- **THEN** no upload jobs are created, the `joinedEventId` marker stays unset, and the next cycle retries

#### Scenario: A listing timeout defers without settling

- **WHEN** the device-listing fetch does not return within its bounded timeout
- **THEN** it is treated exactly as a failed fetch — no seed, the ledger and cursor untouched, the marker unset — and the next cycle retries

### Requirement: Reinstall semantics stay staged until a post-ship change deletes the read fallback

The reinstall semantics of the config-file migration SHALL remain **staged**, and this requirement
records the staging as contract. The decided end state is **reinstall = left the event** — an
App-Group file dies with the install — but the flip is gated on the read fallback's deletion,
which SHALL be a **designated post-ship change**, not part of the migration branch's ship.

**Stage 1 (in force at ship).** The migration finale ended the 11a Keychain **write-through**
(saves and clears are file-only; the revert direction is sacrificed, consistent with fix-forward),
but the config READ keeps the read-only legacy-Keychain fallback (capability `event-link`): a
definitively-missing file consults the legacy item, resurrects a found membership into the file,
and only file-missing **and** item-absent reads as a leave. **The ship model forces this**: the
migration branch reaches `main` — and therefore every production device — as ONE merge, so at
update time the entire joined installed base consists of pre-11a devices whose config file has
never existed. The per-step TestFlight soak the original 11a→13b staging assumed never happened
on this branch; shipping the fallback's deletion in the same merge that introduces the file would
read every joined device as left on update — a silent, fleet-wide logout. Consequently a
reinstall during Stage 1 (file wiped with the App Group; Keychain item surviving uninstall) still
resurrects the membership — indistinguishable from an update-in-place, by design — and the
pre-existing reinstall behavior (no marker, empty ledger, config present → clear-and-seed
reconciliation, nothing re-uploads) holds unchanged over the resurrected config.

**Stage 2 (a designated post-ship change).** After a production soak — every active joined device
has executed at least one read on a ≥13b build, so its membership has been migrated into the file
— a follow-up change SHALL delete the read-only fallback (`KeychainConfigReader`) and retire the
config pair's runtime-identity pin. Only then does a missing file read as **definitively not
joined** with nothing else consulted: the reinstalled device's first cycle runs the leave-side
reconciliation, uploads nothing, and rejoining requires re-scanning the invite. That change SHALL
carry its own delta to this requirement, collapsing the staging; until it lands, Stage 1 is the
behavior in force.

No stronger reinstall detector (e.g. an install-scoped marker distinguishing reinstall from
update) SHALL be introduced meanwhile: it would flip the semantics for fresh state while the
fallback still resurrects migrated state, buying divergence rather than the end-state truth
(decision record: `changes/archive/migrate-config-to-app-group-file` D5;
`changes/archive/2026-07-19-complete-architecture-migration` D4 records the ship-at-once
reasoning).

#### Scenario: A pre-11a device updates straight to this build and stays joined

- **WHEN** a device joined under a pre-11a (Keychain-only) build updates directly to this build —
  the whole installed base's update path — and the OS schedules the upload extension before the
  user opens the updated app
- **THEN** the first cycle reads the membership through the read-only fallback, migrates it into
  the file, runs no leave-side reconciliation, and leaves the `joinedEventId` marker intact

#### Scenario: A reinstall during Stage 1 still resurrects and reconciles

- **WHEN** the app is deleted and reinstalled (App-Group ledger and config file wiped; Keychain
  item surviving) while the read fallback is in force, and relaunched
- **THEN** the first read resurrects the membership from the legacy item, and the next upload
  cycle finds no `joinedEventId` marker and runs the pre-existing clear-and-seed reconciliation,
  so nothing already stored re-uploads

#### Scenario: The end state arrives only with the post-ship fallback deletion

- **WHEN** the designated post-ship change deletes the read-only fallback after the production
  soak, and the app is thereafter deleted and reinstalled
- **THEN** the first cycle reads definitively-not-joined (no file, no fallback), runs the
  leave-side reconciliation, uploads nothing, and the device rejoins only by scanning the invite
  again

