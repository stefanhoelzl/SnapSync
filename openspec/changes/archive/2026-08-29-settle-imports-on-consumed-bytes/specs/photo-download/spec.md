## MODIFIED Requirements

### Requirement: An interrupted import is adjudicated, never repeated blindly

The client SHALL NOT create a second asset for a ref that already carries a created-asset marker.
An import that records its created asset but never records a confirmation — because the process ended, or
because the library has not yet reported — leaves the download store holding an **unconfirmed** row
(capability `download-store`), and such a row SHALL NOT be treated as ordinary import work. Before a
process imports anything, it SHALL ask the photo library about the unconfirmed rows it inherited, and act
on the answer:

- **present** — settle the row against the marker it already holds and create nothing;
- **absent, and the row's staged bytes are gone** — settle the row against the marker it already holds,
  exactly as for *present*. The photo library consumed those bytes when it ingested them, which it does
  only as part of creating an asset, so their absence is positive evidence that a creation was submitted;
- **absent, and the row's staged bytes are still present** — clear the marker, then import, **but only if
  no import for that ref is running in this process**;
- **absent, and the row records no staged resources at all** — treat exactly as *unknown*;
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
the change block returned still left the asset in the library).

Because a commit outlives its process, a relaunch can adjudicate a row **while that commit is still in
flight**, and the library then answers *absent* about an asset that is about to exist. That is not a
theoretical window: measured across two hosts, 8 of 9 runs at 25-43 MB reproduced it, and killing the
client widens the commit window 2-3x over the same import with a live client. The staged bytes are what
close it — they are already gone at that moment, and nothing but the library's ingest can have taken them.
A relaunch SHALL therefore NOT be treated as making *absent* actionable on its own; only the staged bytes
still being present makes it actionable.

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

A row settled on the strength of consumed bytes SHALL be settled **against the marker it already holds**,
never against a fresh one, and SHALL be indistinguishable downstream from one settled by a *present*
verdict. Where that row's commit had in fact failed content validation, the marker then names an asset that
does not exist. That is accepted and SHALL be stated rather than corrected: such a marker is inert — the
suppression projection is compared against assets the library actually holds, so an entry matching none is
never compared to anything — while the alternatives either strip a live asset's only suppression handle or
leave the row outstanding forever, pegging the download total below completion. The cost is that the photo
is counted as imported although it never arrived; its bytes were consumed, so no path could have recovered
it.

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

- **WHEN** an unconfirmed row's asset is absent from the library, its staged bytes are still present, and
  no import for that ref is claimed
- **THEN** the marker is cleared and the asset is imported, so the photo still arrives

#### Scenario: A commit still in flight at relaunch is settled, not cleared

- **WHEN** a process died mid-import, a later process adjudicates the row, and the library answers *absent*
  because the surviving commit has not yet become visible
- **THEN** the row's staged bytes are found to be gone, the row is settled against the marker it already
  holds, no second asset is created, and the created asset stays in the suppression set

#### Scenario: An unconfirmed row with no staged resources is left alone

- **WHEN** an unconfirmed row carries a marker and records no staged resources at all
- **THEN** nothing is cleared and nothing is settled, because their absence is evidence neither way

#### Scenario: An unanswerable lookup defers rather than guesses

- **WHEN** the library cannot give a trustworthy answer about an unconfirmed row
- **THEN** nothing is imported and nothing is recorded, and the row is retried on a later launch

### Requirement: Deletion is respected — no re-download

Once a foreign asset's import is **confirmed** (a terminal download-store row), the client SHALL NOT
download or import it again, even if the user later deletes the imported asset from their library. The
same asset linked into more than one event SHALL be imported only once (cross-event dedup via the store).

This guarantee attaches to a **confirmed** import. An **unconfirmed** row — one whose created-asset marker
was recorded but never confirmed — SHALL NOT be re-imported once its staged bytes have been consumed, and
they are consumed by the library's own ingest whenever a creation was submitted. A deleted photo whose
import was never confirmed therefore does **not** return: there are no bytes to import it from, and the row
settles against the marker it holds (capability `photo-download`, adjudication). Only a row whose bytes
survive — a change block that died before ingest — is re-imported, and that row's asset never existed to be
deleted.

#### Scenario: A deleted collected photo is not restored

- **WHEN** the user deletes a previously-imported foreign photo and a later sync runs
- **THEN** the asset is not re-downloaded or re-imported

#### Scenario: The same asset across two events imports once

- **WHEN** a device's asset appears in the unions of two events this install joins
- **THEN** it is imported only the first time and skipped thereafter

#### Scenario: A deleted photo from an unconfirmed import does not return

- **WHEN** the user deletes an asset whose import was never confirmed, and a later sweep adjudicates that
  row as absent
- **THEN** the row's staged bytes are found to be gone, the row is settled against the marker it holds, and
  the photo is not re-imported
