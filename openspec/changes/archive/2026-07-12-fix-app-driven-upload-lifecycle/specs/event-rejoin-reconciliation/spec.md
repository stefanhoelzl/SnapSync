## MODIFIED Requirements

### Requirement: Reconciliation gate before enabling uploads

The **upload tier** SHALL run a join reconciliation on **its own upload cycle**, before creating any
upload jobs, exactly when an event is configured and its `eventId` differs from a persisted
`joinedEventId` marker. The upload tier is whichever process holds the `LedgerWriter` — the extension
on iOS ≥26.1, the app on iOS 18–26.0. The `joinedEventId` marker — **not** ledger-emptiness — SHALL be the join
signal, persisted across the tier's processes. When the configured `eventId` equals the marker, the
tier SHALL NOT fetch, enumerate, or seed, and SHALL proceed to upload. When no event is configured,
the tier SHALL neither reconcile nor upload.

The reconciliation SHALL be driven from the **shared upload cycle** (`:capability:upload`'s
`UploadCycle`), not from each tier's composition root, and the cycle SHALL require a reconciliation to
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

#### Scenario: A reinstall reconciles without any provision

- **WHEN** the app is deleted and reinstalled (wiping the App Group ledger) and relaunched into an already-joined event, so no provisioning path runs
- **THEN** the next upload cycle finds no `joinedEventId` marker, reconciles against the per-device listing, and seeds already-stored resources as `COMPLETED` so none re-upload

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

After a **leave** (config absent), **no** lifecycle path SHALL clear the ledger, cursor, or
accumulator (`upload-lifecycle`, "Upload producer seam has no destructive verb"). The tier SHALL clear
the `joinedEventId` marker **only**, on its next cycle, while **keeping** the ledger, cursor, and
accumulator intact (the ledger is device-global and valid across events), so a subsequent provision of
any event runs a fresh reconciliation without losing dedup.

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
- **THEN** the tier clears the `joinedEventId` marker **only** and keeps the ledger, cursor, and accumulator intact, so provisioning any event afterward runs a fresh reconciliation and re-uploads nothing already stored

#### Scenario: No lifecycle transition wipes the ledger

- **WHEN** a leave, an event switch, a re-provision, a permission change, or a direction change occurs on either tier
- **THEN** the ledger and discovery cursor are never cleared by that transition; only a triggered reconciliation's `resetTo` ever re-baselines them

## REMOVED Requirements

### Requirement: Extension defers uploads until the seed succeeds

**Reason**: Scoped to the extension process, so it never bound the app-driven tier (iOS 18–26.0) added in `2026-07-04-add-url-session-upload`. That tier consequently reconciled nothing, and a reinstall or leave-then-rejoin re-uploaded every already-stored resource.

**Migration**: Superseded by "Upload tier defers uploads until the seed succeeds" below, which is identical in substance but binds **whichever** process holds the `LedgerWriter`, and is enforced by the shared `UploadCycle` requiring a reconciliation to be supplied.

## ADDED Requirements

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
