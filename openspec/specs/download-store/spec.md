# download-store Specification

## Purpose

The durable record of what this device has downloaded from an event, and the read-only projection the upload
side consults to avoid echoing those photos back. Written exclusively by the app; read by the extension
through a schema shared between the two processes.

Two properties carry the weight. **The suppression marker is written before the imported asset becomes
observable** to the photo library — otherwise discovery could see a freshly-imported foreign photo and queue
it for upload, sending the event its own bytes back. And **terminal rows are permanent**: once a resource is
recorded as imported (or deliberately deleted by the user), that verdict never reverts, which is what makes
"a downloaded photo the user deleted is not re-imported" hold across relaunches.

A pending resource's presigned URL is refreshed on re-plan, because download links expire and a stale one
must self-heal rather than strand the transfer.

Decision record: `changes/archive/2026-06-30-add-photo-download`.

## Requirements
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

### Requirement: Schema in a shared module read by the extension

The store's schema and a read-only **suppression projection** (the set of `createdLocalId`s) SHALL
live in a lean shared module linked by **both** the app (writer + full reader) and the upload
extension (read-only reader of the suppression projection). The extension SHALL open the store
read-only and read only the `createdLocalId` set, over WAL (the mirror of how the app already reads
the extension's ledger). The app-side download logic (planner, transfer controller, importer) SHALL
NOT be linked by the extension.

The extension SHALL depend on the suppression projection through a **narrowed `SuppressionSource`
type** exposing only `suppressedLocalIds()` — not the full `DownloadStore` interface. The composition
root SHALL wire a read-only `SuppressionSource` factory into the upload cycle, so the extension's
inability to write or read beyond the suppression set is **compile-enforced** rather than a linkage
convention. `DownloadStore` MAY extend `SuppressionSource`, but no `DownloadStore`-typed value SHALL
reach the extension's upload cycle.

#### Scenario: Extension reads the suppression set read-only

- **WHEN** the upload extension needs the suppression set
- **THEN** it opens the store read-only and reads the `createdLocalId` projection, without linking the
  app-side download logic

#### Scenario: The extension is typed to the narrowed suppression source

- **WHEN** the extension's upload cycle is assembled
- **THEN** it receives a `SuppressionSource` (only `suppressedLocalIds()`), never a `DownloadStore`, so
  it cannot express a write or a non-suppression read

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

On re-plan of an asset already recorded in the store, the store SHALL **refresh** the stored `url`
of each of that asset's resources whose `stagedPath IS NULL` (not yet downloaded) to the value from
the latest read — re-plan happens on re-reading the union on join or foreground — while leaving a
resource that is already **staged**, and any **terminal (imported)** asset, entirely untouched. A resource's
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

