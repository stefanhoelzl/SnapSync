## MODIFIED Requirements

### Requirement: An interrupted import is adjudicated, never repeated blindly

The client SHALL NOT create a second asset for a ref that already carries a created-asset marker.
An import that records its created asset but never records a confirmation — because the process ended, or
because the library has not yet reported — leaves the download store holding an **unconfirmed** row
(capability `download-store`), and such a row SHALL NOT be treated as ordinary import work. Before a
process imports anything, it SHALL ask the photo library about the unconfirmed rows it inherited, and act
on the answer:

- **present** — record the import against the marker it already holds and create nothing;
- **absent** — clear the marker, then import, **but only if no import for that ref is running in this
  process**;
- **unknown** — do nothing this pass, and retry later.

**Adjudication is a recovery sweep, not a gate on the import path.** A row carrying a marker is already
excluded from importable work and already suppresses uploads, so no second asset can be created whether or
not the sweep runs; what the sweep does is *release* rows that are otherwise stuck. Its dominant outcome is
**present**: a `performChanges` commit survives the death of the process that opened it (measured), so the
ordinary post-death case is an asset that exists, whose row must be settled, whose staged bytes must be
released, and whose absence from the imported count otherwise pegs the status screen below 100% forever.

The sweep SHALL run **exactly once per process, from a single call site**, and SHALL NOT be performed at
any other entry point. Running it per reconcile, per import pass, or per staged resource asks the library
about the import currently in flight — whose transaction is open, so the answer can only be *absent*, and
whose *absent* the gate below is required to discard. Only a process that has died can leave a row no
running import will settle, and only a later process can adjudicate it.

The sweep SHALL run **after** the client's photo-permission subscriptions are installed. Under a partial
grant the answer comes from the held selection snapshot, which is empty until its first observer emission;
a sweep that precedes it answers *unknown* for every row, and with a single per-process sweep that row
then waits for the next launch rather than for the next pass.

The sweep SHALL be followed by an import drain in the same process. The *absent* branch clears a marker,
which is what returns the row to importable work; a clear that nothing then imports moves the stall
instead of ending it.

An *absent* answer SHALL NOT be acted on while an import for that ref is **claimed** — that is, from the
moment the ref is taken out of circulation for an import until the library reports that import's outcome.
The library answers about **committed** state, so it answers honestly that an asset does not exist while the
transaction creating it is still open; acting on that clears the marker of an asset that does exist, drops it
from the suppression set, and the device uploads a downloaded photo back into the event. A claimed ref SHALL
therefore be treated exactly as *unknown*.

The gate SHALL be the claimed/not-claimed **fact**, never an elapsed-time estimate of it. The process is
suspended for arbitrary spans between a change block and its completion, so any wall-clock bound expires
against transactions that are alive.

The record of claimed refs SHALL NOT survive the process. It describes imports running **here**, and a
process that has ended is running none; a durable record would distrust a ref forever and its photo would
never arrive. This SHALL NOT be justified by the claim that a transaction cannot outlive the process that
opened it — that premise was measured and is **false** (SE2, iOS 26.5.2, 2026-08-09: a SIGKILL 200 ms after
the change block returned still left the asset in the library). What makes the post-relaunch path safe is
the *present* branch, which settles such a row against the marker it already holds. The residual — a
relaunch adjudicating while a surviving commit is still in flight — is accepted and unchanged
(decision record: `changes/archive/2026-08-10-take-imports-off-the-download-lock`).

**Every** verdict SHALL be applied through a store write that is **guarded on the row's current marker**, and
SHALL take effect only while the row still carries the marker the verdict was computed for. This applies to
*present* and to *absent* alike: verdicts are computed outside the download controller's lock, while the
photo library's completion callback settles rows from the platform's own queue holding no lock. Applying a
stale *present* records an import against a marker the row no longer holds; applying a stale *absent* strips
the marker from a row the completion has already settled, leaving its asset in the library with nothing
recording that it must not be uploaded — and because that row is terminal it is never adjudicated or
re-imported again, so the loss is permanent. The guard SHALL live in the store's write rather than in a
caller's preceding read, because the two writers reach it with no shared lock and a read-then-write pair is
not atomic against the one that does not take it.

Clearing the marker **before** importing on *absent* is required: an import that fails before reaching
the change block (an unmapped resource type, for example) would otherwise leave the stale marker in
place and the row would be skipped on every future pass.

The lookup SHALL be **batched** — one query for every inherited unconfirmed row at once — and SHALL NOT be
performed at all when no row carries a marker, which is the ordinary case and, with a single per-process
sweep, the case in every process that did not inherit interrupted work.

The claimed/not-claimed gate SHALL be read **under** the download controller's lock, together with the
write it guards — never sampled before acquiring it. Reading it earlier reproduced this defect on real
hardware once already: the gate answer went stale while the adjudication queued on the lock, and the marker
of a live asset was cleared. The record of claimed refs is also mutated only under that lock, so reading it
from outside is a data race besides. With the sweep running before the process claims anything, the gate is
structurally satisfied rather than merely read in the right place; it is retained because that property
depends on there being one call site, and a guard that costs nothing SHALL NOT be the thing a future call
site silently removes.

The lookup SHALL run **outside** the download controller's lock; only the gate and the verdicts' guarded
writes run under it. The library lookup is a synchronous, thread-blocking call that no timeout can abandon
(cancellation is cooperative), so performing it under the lock would let a stalled photo library block every
reconcile, import, leave, and switch. The adapter SHALL own its dispatcher hop for the same reason.

A *present* verdict SHALL also release that ref's claim. The write that precedes it has already made the row
terminal, so no reader can distinguish a released claim from a retained one; what the release buys is a
bounded set rather than a behaviour.

#### Scenario: A relaunch after an interrupted import creates no second asset

- **WHEN** an import records its created asset, the process ends before the import is confirmed, and a
  later pass reaches the same asset
- **THEN** the library is asked, the asset is found, the import is recorded against the existing created
  asset, and no second asset is created

#### Scenario: A burst asks the library nothing

- **WHEN** a process that inherited no unconfirmed rows downloads and imports many assets, staging
  resources and draining imports throughout
- **THEN** the photo library is asked about presence exactly zero times, because the only sweep ran at
  process start and found no row carrying a marker

#### Scenario: A staged resource does not trigger a sweep

- **WHEN** a resource finishes downloading and is staged, while an import for another asset is in flight
- **THEN** no presence lookup is performed, and no verdict about the in-flight import is computed or
  discarded

#### Scenario: The sweep precedes the first import of the process

- **WHEN** a process starts and download work is entered by any trigger
- **THEN** the sweep has completed before that process creates any asset

#### Scenario: A partial grant is not swept before its selection is known

- **WHEN** a process starts while the app holds a partial photo grant
- **THEN** the sweep runs only after the selection subscription's first emission, so an answerable row is
  not recorded as unknown

#### Scenario: A cleared marker is imported in the same process

- **WHEN** the sweep clears the marker of a row whose asset is definitively absent
- **THEN** an import drain runs in that same process and imports it, rather than leaving it for a later
  trigger

#### Scenario: An absent answer about a claimed import is not acted on

- **WHEN** an import for a ref is claimed, its outcome has not been reported, and the library answers that
  the created asset is absent
- **THEN** the marker is kept, nothing is imported, and the row is retried on a later pass

#### Scenario: A relaunch makes absence actionable again

- **WHEN** the process ends while a ref is claimed and a later process adjudicates the same row
- **THEN** the ref is not treated as claimed, because the claim recorded imports running in a process that
  no longer exists

#### Scenario: A stale absent verdict does not clear a settled row's marker

- **WHEN** an *absent* verdict is computed for a ref, and the library's completion settles that row
  before the verdict is applied
- **THEN** the guarded write matches no row, the marker is left intact, and the asset stays suppressed

#### Scenario: A stale present verdict is discarded

- **WHEN** a *present* verdict is computed for a marker and the row is settled under a different marker
  before that verdict is applied
- **THEN** the guarded write matches no row, and the marker the row now holds is left intact

#### Scenario: The orphan is never uploaded

- **WHEN** an interrupted import is adjudicated as present
- **THEN** the created asset stays in the suppression set throughout, and no upload job is ever created
  for it

#### Scenario: A created asset that never materialised is retried

- **WHEN** an unconfirmed row's asset is definitively absent from the library and no import for that ref
  is claimed
- **THEN** the marker is cleared and the asset is imported, so the photo still arrives

#### Scenario: An unanswerable lookup defers rather than guesses

- **WHEN** the library cannot give a trustworthy answer about an unconfirmed row
- **THEN** nothing is imported and nothing is recorded, and the row is retried on a later launch

### Requirement: Staged bytes are released when their import is settled

The client SHALL release an asset's staged bytes once its import is **settled** — confirmed, or settled as
permanently unimportable — and SHALL release the staged bytes of the rows a leave, switch, or durable state
reset actually drops, taking those paths from the prune itself, which returns what it stranded (capability
`download-store`). A pass over assets whose import is confirmed but whose staged bytes remain SHALL release
those too, so installs that accumulated bytes before this behaviour existed are reclaimed.

The client SHALL NOT read those paths **before** the prune as a separate step. That is two reads at two
instants over a store the photo library's change and completion blocks mutate without taking the client's
lock, so a marker cleared in between turns a row the read protected into a row the prune deletes — and its
files are then orphaned with nothing referencing them, unfindable and surviving relaunch.

The release the **client** performs SHALL happen **after** the settling write commits, never before.
Releasing first and recording second loses the photo permanently if the process ends between them: the
bytes are gone, the resource is still recorded as staged, and a staged resource is never re-downloaded.

**The photo library consumes a resource's file at ingest, and that is not under the client's control.**
Resources are handed to PhotoKit with the move option (capability `photo-download`, below), which takes the
file when the resource is ingested — before the content is validated and before the commit's verdict, and
therefore while the row is still claimed and unconfirmed. The client SHALL therefore treat a staged file as
possibly already consumed from the moment an import is attempted, and SHALL NOT assume it survives a failed
or interrupted import. Where the file is gone and no asset was created, the row SHALL settle as permanently
unimportable rather than be retried against a file that no longer exists.

Bytes SHALL NOT be released **by the client** for an import that is still claimed or unconfirmed. Where the
release follows a verdict, it SHALL happen only if that verdict's guarded write took effect: releasing the
bytes of a row that has moved on destroys the staged files a live import is reading from. Release SHALL be
best-effort: a failure to delete SHALL never fail an import.

Bytes SHALL be released only where they can be **positively attributed** to a settled or dropped row.
The client SHALL NOT delete staged files inferred to be unreferenced by scanning storage: a transfer
moves its bytes into place before the store records them, so a scan can delete a file whose row is about
to claim it is staged — losing that photo permanently.

#### Scenario: A received photo does not occupy storage twice forever

- **WHEN** a foreign asset's import is confirmed
- **THEN** its staged bytes are released, while the row recording the created asset is retained

#### Scenario: An importing asset does not hold its bytes twice

- **WHEN** an asset's resources are imported into the photo library
- **THEN** the library takes each file rather than copying it, so that asset's bytes are never resident
  twice while its import runs

#### Scenario: A discarded verdict releases nothing

- **WHEN** a *present* verdict's guarded write matches no row because the row moved on
- **THEN** that row's staged bytes are not released

#### Scenario: Bytes are not stranded by a leave

- **WHEN** rows are dropped on leave, switch, or reset
- **THEN** the prune returns exactly those rows' staged paths and the client releases them, so no file is
  left with nothing referencing it and no surviving row loses the bytes it still needs

## ADDED Requirements

### Requirement: An import the library cannot perform settles, and says so

An asset whose resources the photo library rejects SHALL settle as **permanently unimportable** — a
terminal download-store row — rather than be retried on every later trigger.

Today such a row is retried without bound: the import fails, the row remains importable, and the transfer
is never re-run because a resource already recorded as staged is never re-planned. Each retry is a full
photo-library transaction, once per trigger, for the life of the install, and nothing anywhere records that
the photo will never arrive.

The settlement SHALL be reported at a severity that reaches the crash-reporting sink (capability
`crash-reporting`), naming the ref and the library's reported error. A photo that will never arrive is
otherwise invisible: it is absent from the user's library with no error surface, and absent from the logs
except as an indistinguishable repetition of the failure that caused it. "This import failed and will be
retried" and "this photo will never arrive" are different answers with different consequences, so they
SHALL be distinguishable.

A settlement SHALL be distinguished from a **transient** failure by whether the resource's bytes remain.
Where the library has consumed the staged file and created no asset, there is nothing left to retry with
and the row SHALL settle. Where the request was rejected before any file was consumed, the failure SHALL
be treated as transient and the row SHALL remain importable.

A terminally unimportable row SHALL be excluded from importable work, from adjudication, and from
re-planning, exactly as a confirmed row is; its staged bytes, where any remain, SHALL be released.

#### Scenario: A resource the library rejects is not retried forever

- **WHEN** an import fails because the library rejects the resource's content, and the staged file has
  been consumed
- **THEN** the row settles as permanently unimportable, is not offered on any later trigger, and no
  further library transaction is attempted for it

#### Scenario: The give-up is visible off-device

- **WHEN** a row settles as permanently unimportable
- **THEN** it is reported at a severity that reaches the crash-reporting sink, naming the ref and the
  library's error, so the lost photo is discoverable without the device

#### Scenario: A rejected request is still retried

- **WHEN** an import fails because the request was rejected before any resource was ingested, so the
  staged files remain
- **THEN** the row stays importable and a later trigger retries it from those bytes

#### Scenario: A consumed file with no asset does not retry against nothing

- **WHEN** a process ends after the library has taken a resource's file but before any asset is created,
  and a later process drains
- **THEN** the row settles as permanently unimportable rather than attempting an import against a staged
  path whose file no longer exists

### Requirement: Resources are handed to the photo library by move, not copy

The client SHALL add each staged resource to the photo library with the move option, so the library takes
the file rather than duplicating it.

An importing asset otherwise holds its bytes **twice** — the staged file and the library's copy — for the
window between the commit and the client's own release, which follows the confirming write. That window is
where a device short of space fails, because the library must find room for a full second copy at that
instant. The requirement is about that window and not about total occupancy: resources already downloaded
and waiting to import occupy staging identically either way. The option also makes the consumed file the
signal the settlement requirement above rests on.

The ingest-time consumption SHALL be treated as measured platform behaviour, not as an assumption: the
library takes the file when the resource is ingested — **before** it validates the content and **before**
the commit — so a request rejected on its *content* has already consumed the file while a request rejected
on its *shape* has not. Measured 2026-08-26 (iOS 26.2): a resource whose bytes were invalid failed with
`PHPhotosErrorInvalidResource` and its file was gone with no asset created; requests rejected with
`PHPhotosErrorChangeNotSupported` consumed nothing — reproduced case for case on device (SE2, iOS 26.6,
2026-08-26). ⏰ Re-measure at the next iOS major.

#### Scenario: A successful import consumes the staged file

- **WHEN** an asset's resources are imported successfully
- **THEN** the staged files are gone, and the client's own release finds nothing left to delete

#### Scenario: A content rejection consumes the file

- **WHEN** the library rejects a resource's content
- **THEN** the staged file is gone and no asset was created, which is the condition the permanently-
  unimportable settlement acts on

#### Scenario: A shape rejection consumes nothing

- **WHEN** the library rejects the change request itself, before ingesting any resource
- **THEN** every staged file remains and the row is retried later
