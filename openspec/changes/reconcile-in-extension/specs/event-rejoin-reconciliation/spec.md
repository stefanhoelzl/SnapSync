## MODIFIED Requirements

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

### Requirement: Event switch versus re-join

The extension SHALL compare the configured `eventId` to the persisted `joinedEventId` marker. When they
**differ** (an event switch, a reinstall with no marker, or a fresh provision), the extension SHALL reset
its ledger to empty and reconcile for the configured event, then set the marker. When they **match**,
relaunch or re-provision is a no-op (no reset, no re-seed). After a **leave** (config absent), the
extension SHALL reset its ledger and clear the marker on its next cycle, so a subsequent provision of any
event reconciles it fresh.

#### Scenario: Re-provision of an already-joined event is a no-op

- **WHEN** the configured `eventId` equals the `joinedEventId` marker
- **THEN** the ledger is not reset, no re-seed occurs, and the producer stays as is

#### Scenario: A different event resets and reconciles

- **WHEN** the configured `eventId` differs from the marker
- **THEN** the extension resets the ledger to empty and reconciles for the new event, then sets the marker

#### Scenario: Leaving clears the marker so the next provision reconciles fresh

- **WHEN** the user has left an event (config absent) and the extension next runs
- **THEN** the extension resets its ledger and clears the marker, so provisioning any event afterward runs a fresh reconciliation

## ADDED Requirements

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

## REMOVED Requirements

### Requirement: Status reflects the seed immediately on join

**Reason**: Status is read from the completeness listing, not the seed; the seed is now a producer-side
dedup optimization with no UI role, and it runs in the extension on OS cadence rather than synchronously in
the app.
**Migration**: On (re)join the status screen shows real listing-derived counts immediately via Change 2's
`CompletedAssetsSource` — no seed is needed for the UI.

### Requirement: Join status seam and states

**Reason**: `EventStatus` (`Idle`/`Joining`/`Joined`/`JoinFailed`) existed to narrate the app-run seed to
the UI; with the seed in the extension and status read from storage, there is nothing to narrate.
**Migration**: The status screen reduces permission + the `SyncStatus` snapshot only
(`sync-status-screen`); during reconciliation it shows real listing counts. `EventStatusSource` and the
`UiState.Joining`/`UiState.JoinFailed` states are deleted.

### Requirement: Block until success, no auto-retry, re-scan to retry

**Reason**: This described the app-run, in-memory-flag join with a `JoinFailed` screen and a
re-scan-to-retry affordance. The extension now defers uploads until the seed succeeds and retries on its
own cadence (see "Extension defers uploads until the seed succeeds"), and there is no join-failure UI to
act on.
**Migration**: Replaced by the deferral/retry behavior above; the `joinedEventId` marker (persisted)
replaces the in-memory settled-this-process flag.
