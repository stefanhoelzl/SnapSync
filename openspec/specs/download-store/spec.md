# download-store Specification

## Purpose

The durable record of what this device has downloaded from an event, and the read-only projection the upload
side consults to avoid echoing those photos back. Written exclusively by the app; read by the extension
through a schema shared between the two processes.

Two properties carry the weight. **The suppression marker is written before the imported asset becomes
observable** to the photo library — otherwise discovery could see a freshly-imported foreign photo and queue
it for upload, sending the event its own bytes back. And **handle-carrying rows are permanent**: once a row
records the identifier of an asset it created, that record never goes away, which is what makes "a
downloaded photo the user deleted is not re-imported" hold across relaunches.

That second property is deliberately about the **marker**, not the state. Because the marker is written
inside the platform's change block — which always completes before the library commits — a created asset
*always* has one, while its confirmation may never arrive: a process death or an abandoned wait leaves a row
that holds the marker and still looks non-terminal. A rule phrased around terminal rows deletes exactly that
row on the next leave, destroying the only record that its asset must not be uploaded; the asset is then
sent back into the event, where every other member imports it as a photo they have never seen. That is not
hypothetical — it is what the spec previously said, and what shipped.

Staged bytes follow the same discipline from the other side: they are released only once a row is settled,
because they are the sole source for a retry and a resource already recorded as staged is never
re-downloaded.

A pending resource's presigned URL is refreshed on re-plan, because download links expire and a stale one
must self-heal rather than strand the transfer.

Decision record: `changes/archive/2026-06-30-add-photo-download`;
`changes/archive/2026-08-06-fix-duplicate-import-on-restart` replaced "terminal rows are permanent" with the
marker-based invariant above, and added the adjudication of unconfirmed rows and the staged-byte lifetime.

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
asset is recorded as suppressed before it can be observed by the upload extension's discovery.

Because the block always runs to completion before the library commits, **a created asset always has a
recorded marker**: there is no window in which an asset exists and its marker does not. The marker is
therefore the store's record that an irreversible act was requested, and the pair
`state = PENDING` + a non-null `createdLocalId` SHALL be read as **"an asset was created for this ref,
and its import is unconfirmed"** — not as "not yet imported".

When the change's completion reports **failure**, the importer SHALL **clear** the marker it wrote — the
exact mirror of the in-block write, in the same callback — so an observed failure never leaves an
unconfirmed row behind. A marker SHALL NOT be cleared when the import's wait is abandoned on its
deadline (`ImportResult.TimedOut`, capability `photo-download`): that transaction may still commit, and
clearing it is what orphans the created asset.

Both the marker write and its mirror SHALL be part of the **store's port**, not of one implementation, so
every store honours them and the pair is exercisable against each. They SHALL be non-suspending — alone
on that interface — because the platform's change block cannot call a suspending function and the write
must happen inside it; the constraint that creates the method shapes its signature.

#### Scenario: The marker write is available through the port

- **WHEN** any download store implementation is used
- **THEN** the created-asset marker can be recorded and cleared through the store's own interface,
  without reaching for a particular implementation

#### Scenario: Marker precedes discoverability

- **WHEN** a foreign asset is imported
- **THEN** its created `localIdentifier` is persisted to the store within the same change block that
  creates the asset, before the commit is observable

#### Scenario: An observed failure undoes its own marker

- **WHEN** the photo library reports the change failed after the block had already written a marker
- **THEN** the marker is cleared and the asset stays importable, leaving no unconfirmed row

#### Scenario: An abandoned wait keeps its marker

- **WHEN** an import's wait is abandoned on its deadline
- **THEN** the marker is retained, because the transaction may still commit — and if it does, the asset
  it created remains suppressed

### Requirement: Handle-carrying rows are permanent

A row carrying a `createdLocalId` SHALL NOT be cleared by leave, by an event switch, or by a durable
state reset, and the store SHALL NOT be wiped on those transitions — **whether or not that row has
reached a terminal state**. The marker, not the state, is the record that an asset was created; deleting
a row that still carries one destroys the only evidence that the created asset must never be uploaded,
and the asset then echoes back into the event.

This makes a downloaded asset permanently recognized — never re-downloaded after deletion, and
deduplicated across events. Non-terminal rows that carry **no** marker MAY be dropped on leave, switch,
or reset, to be re-enqueued later.

#### Scenario: Leave and switch preserve terminal rows

- **WHEN** the user leaves the event or switches to another event
- **THEN** terminal imported rows (and thus the suppression set) are preserved, while non-terminal rows
  carrying no marker may be discarded

#### Scenario: Leave preserves an unconfirmed row's marker

- **WHEN** the user leaves or switches while a row is `PENDING` and carries a `createdLocalId`
- **THEN** that row and its marker survive, so the asset it created is still suppressed from upload

#### Scenario: A durable state reset preserves markers

- **WHEN** this device's durable sync state is reset
- **THEN** every row carrying a `createdLocalId` is retained, on the same reasoning as leave

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

### Requirement: An asset already created for a ref is never created again

The store SHALL expose the unconfirmed rows — those whose `state` is not terminal and whose
`createdLocalId` is non-null — so the import path can adjudicate them before creating a second asset for
the same `(sourceDeviceId, sourceAssetId)`. Selecting work to import SHALL NOT treat a row carrying a
marker as ordinary pending work.

The store SHALL hold at most one `createdLocalId` per ref, and recording a confirmed import MAY
overwrite it — but only for a ref whose prior marker has been adjudicated, never as a way of discarding
one. A marker overwritten while its asset still exists removes that asset from the suppression set,
which is the defect this requirement exists to prevent.

#### Scenario: An unconfirmed row is not offered as ordinary import work

- **WHEN** the import path selects assets whose resources are all staged
- **THEN** a row carrying a `createdLocalId` is adjudicated rather than imported outright

#### Scenario: The suppression set includes unconfirmed markers

- **WHEN** a row is `PENDING` and carries a `createdLocalId`
- **THEN** that identifier appears in the suppression projection the upload side reads, so the created
  asset is never uploaded

### Requirement: Staged bytes are released only once their row is settled

The store SHALL expose the staged paths of an asset's resources, of all assets whose import is
confirmed, and of all rows about to be dropped — so the download side can release those bytes. Releasing
an asset's bytes SHALL also drop that asset's resource rows, so the store never records a staged path
for a file that no longer exists, and so a backlog pass over already-imported assets is
**self-extinguishing**.

Staged bytes SHALL be released **only** after the confirming write has committed, or immediately before
the rows referencing them are dropped. They SHALL NOT be released while an import is unconfirmed,
failed, or abandoned on its deadline: those bytes are the only source for the retry, and a resource
already recorded as staged is never re-downloaded, so releasing early loses the photo permanently.

#### Scenario: Bytes survive a failed or abandoned import

- **WHEN** an import reports failure, or its wait is abandoned on its deadline
- **THEN** the asset's staged bytes are retained and the retry imports from them

#### Scenario: Bytes are released once the import is confirmed

- **WHEN** an asset's import is confirmed
- **THEN** its staged bytes are released and its resource rows dropped, while the asset row and its
  marker are retained

#### Scenario: A backlog pass runs once and finds nothing thereafter

- **WHEN** a release pass runs over assets whose import is confirmed but whose resource rows remain
- **THEN** their bytes are released and their rows dropped, so a second pass finds no work

