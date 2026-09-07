## MODIFIED Requirements

### Requirement: Unified download store, app-written

The download store SHALL be a per-install App-Group SQLite database, written **only** by the app
process, recording one row per imported foreign asset carrying `sourceDeviceId`, `sourceAssetId`, the
created local `localIdentifier` (`createdLocalId`, null until import), and a lifecycle `state`, plus
per-resource staging state (which of an asset's resources have been downloaded and their staged
location). Each not-yet-staged resource SHALL additionally record an **enqueued** marker — set when
its download is sent to the OS and superseded when the resource is staged — so the store can
distinguish "download in flight" from "not yet enqueued".

The store SHALL expose the download projection's counts as **one read returning them together**: the
imported count (the progress numerator), the count of foreign assets that can still arrive (the progress
denominator, excluding rows settled as permanently unimportable), and the **in-flight** count — the number of
foreign assets that have at least one resource marked enqueued and not yet staged (asset-counted, the download
analogue of the upload ledger's in-flight `pending`).

**One read, not three**, and for the same reason the upload ledger's `aggregates()` is one round-trip: these
counts are published together as a single projection, and the status group requires each of its members to be
internally consistent (`sync-status`, "The cheap local status reads are one bounded group"). Reading them
separately makes the projection's internal consistency an argument about which orders of interleaving are safe,
resting on invariants of the counting predicates that nothing enforces — so a later change to what the
denominator excludes could break it silently, with the failure surfacing as a wrong arrow and no test naming the
cause. One read makes the question unaskable rather than answered.

It SHALL be a distinct database file from the engine's upload ledger, preserving
the single-writer-per-file invariant (the upload ledger is extension-written; this store is
app-written). The enqueued marker and the in-flight count are app-side only and SHALL NOT be exposed
through the read-only `SuppressionSource` the extension links.

#### Scenario: One row per imported foreign asset

- **WHEN** the app imports a foreign asset
- **THEN** the store holds a row keyed by `(sourceDeviceId, sourceAssetId)` carrying its
  `createdLocalId` and a terminal state

#### Scenario: App is the sole writer

- **WHEN** any process needs to mutate the store
- **THEN** only the app process writes it; the extension never writes it

#### Scenario: The projection's counts come from one read

- **WHEN** the download projection is refreshed
- **THEN** its imported, still-arriving and in-flight counts come from a single round-trip and are mutually
  consistent, rather than from separate reads taken at different instants

#### Scenario: Enqueued marker set on send, cleared at staged

- **WHEN** a resource's download is sent to the OS
- **THEN** the resource is marked enqueued and its asset counts toward the in-flight count; **WHEN** that
  resource is later staged, the enqueued marker is superseded and the asset no longer counts toward
  the in-flight count on account of that resource

#### Scenario: In-flight count excludes not-yet-enqueued and staged resources

- **WHEN** a foreign asset is recorded but no download has been sent, or all its resources are staged
- **THEN** the asset does not count toward the in-flight count
