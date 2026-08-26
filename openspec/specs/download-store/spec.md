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
marker-based invariant above, and added the adjudication of unconfirmed rows and the staged-byte lifetime;
`changes/archive/2026-08-27-stop-repeating-futile-import-work` added the terminal unimportable state and
recorded that a staged file can cease to exist without the client releasing it, because the photo library
takes a resource at ingest.

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
unconfirmed row behind. A marker SHALL be cleared for exactly one reason: the library said its change
failed. It SHALL NOT be cleared because time passed, because nothing is awaiting the transaction any longer,
or because a presence lookup answered *absent* while that transaction was still open — it may still commit,
and clearing it is what orphans the created asset (capability `photo-download`).

When the change's completion reports **success**, the importer SHALL record the row as imported **against
the marker it already holds**, from that same callback. The completion is the party that learns the
outcome, and it runs whether or not anything is still awaiting it — so an import whose outcome arrives after
its requester is gone settles itself, rather than staying unconfirmed until some later pass pays for a
synchronous library lookup to discover what the completion already knew.

The **clearing** and the **confirming** writes SHALL both be **guarded on the marker**: each SHALL take
effect only while the row still carries the marker the write was computed for, and each SHALL report whether
it did. A completion or verdict that arrives after the row's marker has been cleared and replaced SHALL
change nothing — confirming would mark the row terminal against an identifier it no longer describes, and
clearing would strip a live suppression handle off a row that has already settled, which is permanent
because a terminal row is never adjudicated or re-imported again.

The guard SHALL live in the store's write, not in a caller's preceding read. Two writers reach these with no
shared lock — the callback writes are non-suspending precisely because the platform's change and completion
blocks cannot call a suspending function — so a read-then-write pair is not atomic against the writer that
does not take the caller's lock. A store SHALL NOT expose a separate marker-scoped read for callers to guard
themselves with; the guarded write is the mechanism.

The in-block marker write is **not** guarded, and SHALL NOT be: it *creates* the addressing the other two
match, so there is nothing for it to match against.

All three callback writes — the marker, its failure mirror, and the confirming write — SHALL be part of
the **store's port**, not of one implementation, so every store honours them and they are exercisable
against each. They SHALL be non-suspending — alone on that interface — because the platform's change block
and completion callback cannot call a suspending function; the constraint that creates the methods shapes
their signature.

#### Scenario: The marker write is available through the port

- **WHEN** any download store implementation is used
- **THEN** the created-asset marker can be recorded, cleared, and confirmed through the store's own
  interface, without reaching for a particular implementation

#### Scenario: Marker precedes discoverability

- **WHEN** a foreign asset is imported
- **THEN** its created `localIdentifier` is persisted to the store within the same change block that
  creates the asset, before the commit is observable

#### Scenario: An observed failure undoes its own marker

- **WHEN** the photo library reports the change failed after the block had already written a marker
- **THEN** the marker is cleared and the asset stays importable, leaving no unconfirmed row

#### Scenario: An unreported outcome keeps its marker

- **WHEN** an import's outcome has not been reported by the library
- **THEN** the marker is retained, because the transaction may still commit — and if it does, the asset
  it created remains suppressed

#### Scenario: A completion settles its row with nothing awaiting it

- **WHEN** the photo library reports a successful change for an import whose requester is gone
- **THEN** the row is recorded as imported against the marker it holds, without any later pass having to
  ask the library whether the asset exists

#### Scenario: A late completion cannot settle a row whose marker moved on

- **WHEN** a completion reports success for a marker that the row no longer carries, because the marker
  was cleared and the asset re-imported under a new one
- **THEN** the row is not settled by that completion, and the marker it now holds is left intact

#### Scenario: A stale clear cannot strip a settled row's marker

- **WHEN** a clear is applied for a marker that the row no longer carries, or to a row that has become
  terminal
- **THEN** the write matches no row, the marker is left intact, and the caller is told it did not apply

### Requirement: Handle-carrying rows are permanent

A row carrying a `createdLocalId` SHALL NOT be cleared by leave, by an event switch, or by a durable
state reset, and the store SHALL NOT be wiped on those transitions — **whether or not that row has
reached a terminal state**. The marker, not the state, is the record that an asset was created; deleting
a row that still carries one destroys the only evidence that the created asset must never be uploaded,
and the asset then echoes back into the event.

A row whose import has been **claimed but whose change block has not yet run** carries no marker and is
equally protected: the caller SHALL name the refs whose imports are in flight, and the prune SHALL spare
their rows. Deleting one makes its marker write land on nothing, so the asset it creates has no suppression
handle at all. That protection is required of the caller and SHALL carry no default value — a permissive
default on a safety gate is how a caller ships without one, and an empty set SHALL be a claim rather than an
omission.

The prune SHALL drop its rows and return the staged paths those rows owned, in **one** operation, so the
caller frees exactly the files it stranded. Reading the paths and pruning separately is two reads at two
instants over a store that the platform's change and completion blocks mutate without taking any lock; a
marker cleared in that gap turns a row the read protected into a row the prune deletes, leaving its files on
disk with nothing referencing them — unfindable, and surviving relaunch.

This makes a downloaded asset permanently recognized — never re-downloaded after deletion, and
deduplicated across events. Non-terminal rows that carry **no** marker and are **not** protected MAY be
dropped on leave, switch, or reset, to be re-enqueued later.

#### Scenario: Leave and switch preserve terminal rows

- **WHEN** the user leaves the event or switches to another event
- **THEN** terminal imported rows (and thus the suppression set) are preserved, while non-terminal rows
  carrying no marker and not protected may be discarded

#### Scenario: Leave preserves an unconfirmed row's marker

- **WHEN** the user leaves or switches while a row is `PENDING` and carries a `createdLocalId`
- **THEN** that row and its marker survive, so the asset it created is still suppressed from upload

#### Scenario: A claimed row survives a prune that has not seen its marker yet

- **WHEN** a prune runs while an import is claimed for a ref whose change block has not run, so its row
  carries no marker
- **THEN** that row is spared, and the marker its change block later writes lands on a row that exists

#### Scenario: The prune frees exactly what it stranded

- **WHEN** a prune drops rows that owned staged files
- **THEN** it returns those files' paths, so the caller releases exactly them and no file is left with
  nothing referencing it

#### Scenario: A durable state reset preserves markers

- **WHEN** this device's durable sync state is reset
- **THEN** every row carrying a `createdLocalId` is retained, on the same reasoning as leave, and every
  protected row is retained too

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

The store SHALL expose the staged paths of an asset's resources and of all assets whose import is
confirmed, and SHALL return the staged paths of the rows a prune drops as part of that prune — so the
download side can release those bytes. Releasing an asset's bytes SHALL also drop that asset's resource
rows, so the store never records a staged path for a file that no longer exists, and so a backlog pass over
already-imported assets is **self-extinguishing**.

Staged bytes SHALL be released **by the client** only after the settling write has committed — the write
that confirms the import, or the one that settles the row as permanently unimportable — or as part of
dropping the rows referencing them. They SHALL NOT be released by the client while an import is
unconfirmed or still claimed: releasing early destroys the staged files a live import is reading from.

**A staged file may cease to exist without the client releasing it.** The photo library takes a resource's
file at ingest (capability `photo-download`), which happens while the row is still claimed and unconfirmed
and before the commit's verdict is known. The store's contract is therefore that a recorded `stagedPath`
asserts *what the client staged*, not that the file is still there. A row whose import failed SHALL NOT be
assumed to retain its bytes, and the retry guarantee below holds only for a failure that consumed nothing.

A row whose resources cannot be imported SHALL be settleable **terminally**, and the store SHALL exclude
such a row from importable work, from the unconfirmed rows offered for adjudication, and from re-planning —
exactly as it excludes a confirmed row. Without a terminal state the row is offered on every trigger
forever, because a resource already recorded as staged is never re-downloaded and the import can never
succeed.

#### Scenario: Bytes survive a failure that consumed nothing

- **WHEN** an import fails before any resource was ingested, or its outcome has not been reported
- **THEN** the asset's staged bytes are retained and the retry imports from them

#### Scenario: Bytes are released once the import is confirmed

- **WHEN** an asset's import is confirmed
- **THEN** its staged bytes are released and its resource rows dropped, while the asset row and its
  marker are retained

#### Scenario: A terminally unimportable row is not offered again

- **WHEN** a row is settled as permanently unimportable
- **THEN** it is absent from importable work, from the unconfirmed rows offered for adjudication, and from
  re-planning, and its remaining resource rows are dropped

#### Scenario: A backlog pass runs once and finds nothing thereafter

- **WHEN** a release pass runs over assets whose import is confirmed but whose resource rows remain
- **THEN** their bytes are released and their rows dropped, so a second pass finds no work

### Requirement: A marker write that lands on no row is loud

The in-block marker write SHALL report whether it updated a row, and a write that matched **none** SHALL be
logged at a severity that reaches the crash-reporting sink (capability `crash-reporting`), naming the ref.

That outcome means the row was deleted between the import being selected and its change block running — the
failure the prune's protection exists to prevent (below). Its consequence is that an asset is created with no
suppression handle, so the device uploads a downloaded photo back into the event days later, with nothing
anywhere recording why. Today that write returns nothing and tells no one, which makes the protection a
safety gate whose failure is only ever inferred from its damage. "The marker was written" and "the marker
landed on nothing" are different answers with different consequences, so they SHALL be distinguishable.

The ordinary case costs nothing: the line never fires, because the row is always there.

#### Scenario: A marker write onto a surviving row is silent

- **WHEN** an import's change block writes its marker and the row exists
- **THEN** the write is recorded and no failure is reported

#### Scenario: A marker write onto a deleted row is reported

- **WHEN** an import's change block writes its marker and no row matches
- **THEN** the failure is logged at a severity that reaches the crash-reporting sink, naming the ref

### Requirement: A terminal unimportable state is distinguishable from a pending one

The store SHALL record "this asset's resources cannot be imported" as a state distinct from both *pending*
and *imported*, and SHALL preserve that distinction across launches.

Collapsing it into *pending* is what produces the unbounded retry. Collapsing it into *imported* would be
worse: an imported row is a suppression handle asserting that an asset exists in the library, and asserting
that about an asset that was never created makes the row terminal for the wrong reason — the photo is
neither present nor recoverable, and the counts report it as arrived.

A terminally unimportable row SHALL NOT carry a created-asset marker, because no asset was created; and the
counts that drive the status surface SHALL treat it as neither imported nor outstanding, so the screen is
not pegged below completion by work that will never finish.

The state SHALL be introduced additively: existing rows keep their current state, and no stored value is
rewritten. It SHALL NOT be assumed inert to code that predates it — such code spells non-terminal as "not
imported", so it reads the new state as ordinary work. That is tolerable only because installs move forward
only, and its worst outcome is a repeated import attempt rather than a lost photo; it is stated here so a
future reader does not mistake the absence of a schema migration for backward compatibility.

#### Scenario: The state survives a relaunch

- **WHEN** a row is settled as permanently unimportable and the process is restarted
- **THEN** the row is still terminal, and no import or adjudication is attempted for it

#### Scenario: A terminal failure is not a suppression handle

- **WHEN** a row settles as permanently unimportable
- **THEN** it carries no created-asset marker, and it contributes nothing to the suppression set

#### Scenario: The status surface is not pegged by unimportable work

- **WHEN** an event's union includes an asset whose import settled as permanently unimportable
- **THEN** the download counts do not report it as outstanding, so the screen can still reach completion
