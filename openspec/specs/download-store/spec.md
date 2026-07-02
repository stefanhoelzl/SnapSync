# download-store Specification

## Purpose
TBD - created by archiving change add-photo-download. Update Purpose after archive.
## Requirements
### Requirement: Unified download store, app-written

The download store SHALL be a per-install App-Group SQLite database, written **only** by the app
process, recording one row per imported foreign asset carrying `sourceDeviceId`, `sourceAssetId`, the
created local `localIdentifier` (`createdLocalId`, null until import), and a lifecycle `state`, plus
per-resource staging state (which of an asset's resources have been downloaded and their staged
location). It SHALL be a distinct database file from the engine's upload ledger, preserving the
single-writer-per-file invariant (the upload ledger is extension-written; this store is app-written).

#### Scenario: One row per imported foreign asset

- **WHEN** the app imports a foreign asset
- **THEN** the store holds a row keyed by `(sourceDeviceId, sourceAssetId)` carrying its
  `createdLocalId` and a terminal state

#### Scenario: App is the sole writer

- **WHEN** any process needs to mutate the store
- **THEN** only the app process writes it; the extension never writes it

### Requirement: Schema in a shared module read by the extension

The store's schema and a read-only **suppression projection** (the set of `createdLocalId`s) SHALL
live in a lean shared module linked by **both** the app (writer + full reader) and the upload
extension (read-only reader of the suppression projection). The extension SHALL open the store
read-only and read only the `createdLocalId` set, over WAL (the mirror of how the app already reads
the extension's ledger). The app-side download logic (planner, transfer controller, importer) SHALL
NOT be linked by the extension.

#### Scenario: Extension reads the suppression set read-only

- **WHEN** the upload extension needs the suppression set
- **THEN** it opens the store read-only and reads the `createdLocalId` projection, without linking the
  app-side download logic

### Requirement: Suppression marker written before the asset is observable

The `createdLocalId` SHALL be obtained from the import's `placeholderForCreatedAsset` and written into
the store **inside the `performChanges` change block**, before the change commits — so the created
asset is recorded as suppressed before it can be observed by the upload extension's discovery. A
marker written for a change that ultimately fails SHALL be harmless (it matches no live asset).

#### Scenario: Marker precedes discoverability

- **WHEN** a foreign asset is imported
- **THEN** its created `localIdentifier` is persisted to the store within the same change block that
  creates the asset, before the commit is observable

### Requirement: Terminal rows are permanent

A terminal (imported) row SHALL NOT be cleared by leave or by an event switch, and the store SHALL NOT
be wiped on those transitions. This makes a downloaded asset permanently recognized — never
re-downloaded after deletion and deduplicated across events. Non-terminal rows (pending/in-flight)
MAY be dropped on leave/switch to be re-enqueued later.

#### Scenario: Leave and switch preserve terminal rows

- **WHEN** the user leaves the event or switches to another event
- **THEN** terminal imported rows (and thus the suppression set) are preserved, while non-terminal
  rows may be discarded

### Requirement: Pending resource URL is refreshed on re-plan

When the app re-plans an asset already recorded in the store (re-reading the union on join or
foreground), the store SHALL **refresh** the stored `url` of each of that asset's resources whose
`stagedPath IS NULL` (not yet downloaded) to the value from the latest read, while leaving a resource
that is already **staged**, and any **terminal (imported)** asset, entirely untouched. A resource's
other fields (`role`, `contentType`, `originalFilename`) are immutable per `resourceKey` and SHALL NOT
change on re-plan. This lets a freshly-minted presigned download URL supersede an earlier, now-expiring
one for work still pending, without disturbing completed staging or re-downloading already-staged
bytes.

#### Scenario: Re-plan updates a pending resource's url

- **WHEN** a resource is planned with url A while unstaged, then the asset is re-planned with url B for
  the same `resourceKey`
- **THEN** the resource's stored `url` becomes B and it appears in `pendingDownloads()` with url B

#### Scenario: A staged resource keeps its url and staging

- **WHEN** a resource has been staged (its `stagedPath` is set) and its asset is re-planned with a
  different url
- **THEN** the resource's `url` and `stagedPath` are unchanged (it is not re-queued or re-downloaded)

#### Scenario: A terminal asset is untouched by re-plan

- **WHEN** an imported asset is re-planned
- **THEN** none of its resources' urls change and the asset stays terminal (never downgraded)

