## MODIFIED Requirements

### Requirement: Unified download store, app-written

The download store SHALL be a per-install App-Group SQLite database, written **only** by the app
process, recording one row per imported foreign asset carrying `sourceDeviceId`, `sourceAssetId`, the
created local `localIdentifier` (`createdLocalId`, null until import), and a lifecycle `state`, plus
per-resource staging state (which of an asset's resources have been downloaded and their staged
location). Each not-yet-staged resource SHALL additionally record an **enqueued** marker — set when
its download is sent to the OS and superseded when the resource is staged — so the store can
distinguish "download in flight" from "not yet enqueued". The store SHALL expose an
**`inFlightCount()`** read returning the number of foreign assets that have at least one resource
marked enqueued and not yet staged (asset-counted, the download analogue of the upload ledger's
in-flight `pending`). It SHALL be a distinct database file from the engine's upload ledger, preserving
the single-writer-per-file invariant (the upload ledger is extension-written; this store is
app-written). The enqueued marker and `inFlightCount()` are app-side only and SHALL NOT be exposed
through the read-only `SuppressionSource` the extension links.

#### Scenario: One row per imported foreign asset

- **WHEN** the app imports a foreign asset
- **THEN** the store holds a row keyed by `(sourceDeviceId, sourceAssetId)` carrying its
  `createdLocalId` and a terminal state

#### Scenario: App is the sole writer

- **WHEN** any process needs to mutate the store
- **THEN** only the app process writes it; the extension never writes it

#### Scenario: Enqueued marker set on send, cleared at staged

- **WHEN** a resource's download is sent to the OS
- **THEN** the resource is marked enqueued and its asset counts toward `inFlightCount()`; **WHEN** that
  resource is later staged, the enqueued marker is superseded and the asset no longer counts toward
  `inFlightCount()` on account of that resource

#### Scenario: In-flight count excludes not-yet-enqueued and staged resources

- **WHEN** a foreign asset is recorded but no download has been sent, or all its resources are staged
- **THEN** the asset does not count toward `inFlightCount()`
