## MODIFIED Requirements

### Requirement: An interrupted import is adjudicated, never repeated blindly

The client SHALL NOT create a second asset for a ref that already carries a created-asset marker.
An import that records its created asset but never records a confirmation — because the process ended, or
because its wait was abandoned on its deadline — leaves the download store holding an **unconfirmed** row
(capability `download-store`), and such a row SHALL NOT be treated as ordinary import work. Before any
asset is created for a ref that already carries a marker, the client SHALL ask the photo library whether
that asset exists, and act on the answer:

- **present** — record the import against the marker it already holds and create nothing;
- **absent** — clear the marker, then import, **but only if that ref's import outcome has been reported**;
- **unknown** — do nothing this pass, and retry later.

An *absent* answer SHALL NOT be acted on while the ref's outcome is **unreported** — that is, from the
moment an import's wait is abandoned on its deadline until the photo library reports that import's
outcome. The library answers about **committed** state, so it answers honestly that an asset does not
exist while the transaction creating it is still open; acting on that clears the marker of an asset that
does exist, drops it from the suppression set, and the device uploads a downloaded photo back into the
event. An unreported ref SHALL therefore be treated exactly as *unknown*.

The gate SHALL be the reported/unreported **fact**, never an elapsed-time estimate of it. The process is
suspended for arbitrary spans between a change block and its completion, so any wall-clock bound expires
against transactions that are alive.

The record of unreported refs SHALL NOT survive the process. A transaction cannot outlive the process that
opened it, so after a relaunch every *absent* answer is trustworthy again; a durable record would instead
distrust a ref forever and its photo would never arrive.

**Every** verdict SHALL be re-checked against the row's current marker before it is applied, and
discarded if the row no longer carries the marker the verdict was computed for. This applies to *present*
and to *absent* alike, and to the unreported gate itself: all three are read outside the download
controller's lock, while the photo library's completion callback settles rows from the platform's own
queue holding no lock. Applying a stale *present* records an import against a marker the row no longer
holds; applying a stale *absent* strips the marker from a row the completion has already settled, leaving
its asset in the library with nothing recording that it must not be uploaded — and because that row is
terminal it is never adjudicated or re-imported again, so the loss is permanent.

Clearing the marker **before** importing on *absent* is required: an import that fails before reaching
the change block (an unmapped resource type, for example) would otherwise leave the stale marker in
place and the row would be skipped on every future pass.

The lookup SHALL be **batched** — one query per import pass, for every unconfirmed row at once — and
SHALL NOT be performed at all when no row carries a marker, which is the ordinary case.

The lookup SHALL run **outside** the download controller's lock; only the verdicts and the import drain
run under it. The library lookup is a synchronous, thread-blocking call that no timeout can abandon
(cancellation is cooperative), so performing it under the lock would let a stalled photo library block
every reconcile, import, leave, and switch — the failure that bounding each import's wait exists to
prevent. The adapter SHALL own its dispatcher hop for the same reason.

#### Scenario: A relaunch after an interrupted import creates no second asset

- **WHEN** an import records its created asset, the process ends before the import is confirmed, and a
  later pass reaches the same asset
- **THEN** the library is asked, the asset is found, the import is recorded against the existing created
  asset, and no second asset is created

#### Scenario: An absent answer about an unreported import is not acted on

- **WHEN** an import's wait was abandoned on its deadline, its outcome has not been reported, and the
  library answers that the created asset is absent
- **THEN** the marker is kept, nothing is imported, and the row is retried on a later pass

#### Scenario: The same ref is adjudicated once its outcome arrives

- **WHEN** the photo library reports the outcome of an import whose wait had been abandoned
- **THEN** the ref stops being unreported, and a subsequent *absent* answer about it is acted on normally

#### Scenario: A relaunch makes absence trustworthy again

- **WHEN** the process ends while a ref is unreported and a later process adjudicates the same row
- **THEN** the ref is not treated as unreported, because no transaction from the previous process can
  still commit

#### Scenario: A stale absent verdict does not clear a settled row's marker

- **WHEN** an *absent* verdict is computed for a ref, and the library's completion settles that row and
  reports its outcome before the verdict is applied
- **THEN** the verdict is discarded, the marker is left intact, and the asset stays suppressed

#### Scenario: A stale present verdict is discarded

- **WHEN** a *present* verdict is computed for a marker and the row is settled under a different marker
  before that verdict is applied
- **THEN** the verdict is discarded, and the marker the row now holds is left intact

#### Scenario: The orphan is never uploaded

- **WHEN** an interrupted import is adjudicated as present
- **THEN** the created asset stays in the suppression set throughout, and no upload job is ever created
  for it

#### Scenario: A created asset that never materialised is retried

- **WHEN** an unconfirmed row's asset is definitively absent from the library and that ref's import
  outcome has been reported
- **THEN** the marker is cleared and the asset is imported, so the photo still arrives

#### Scenario: An unanswerable lookup defers rather than guesses

- **WHEN** the library cannot give a trustworthy answer about an unconfirmed row
- **THEN** nothing is imported and nothing is recorded, and the row is retried on a later pass

### Requirement: Asset presence is answered by the photo-access grant

The presence lookup SHALL be answered differently according to the current photo-access grant, because
an "absent" answer is only trustworthy under a **full** grant:

- **full access** — the library is queried directly; *present* is authoritative, and *absent* is
  authoritative **about committed state only**;
- **partial access** — the answer comes from the selection the app already holds; a hit is *present*, a
  miss is **unknown**, never *absent*, because an asset created under a full grant is real but invisible
  after a downgrade (app-created assets join the selection only at creation time);
- **no usable grant** — **unknown**, because a query returns nothing for assets that exist, and imports
  cannot succeed anyway.

Even under a full grant, an *absent* answer describes only what the library has **committed**. An asset
whose creating transaction is still open is genuinely absent by that measure and present moments later,
so the grant alone does not make *absent* safe to act on; the adjudication requirement additionally
requires that the ref's import outcome has been reported.

Reporting *absent* where the grant cannot support it would clear a live marker, re-import the asset, and
orphan the first copy — the defect this capability's adjudication exists to prevent.

Choosing the source by grant SHALL be composition's, not the download feature's: the feature asks one
question and reads one three-valued answer.

#### Scenario: A partial grant never reports absent

- **WHEN** an unconfirmed row is adjudicated while the app holds a partial photo grant and the asset is
  not in the selection
- **THEN** the answer is unknown, the marker is kept, and nothing is imported

#### Scenario: A revoked grant never reports absent

- **WHEN** an unconfirmed row is adjudicated while the app has no usable photo access
- **THEN** the answer is unknown and the marker is kept, so restoring access does not expose an
  unsuppressed asset

#### Scenario: A full grant's absent answer is about committed state

- **WHEN** the app holds full photo access and asks about an asset whose creating transaction has not
  committed
- **THEN** the answer is absent, and the adjudication does not act on it because that ref's outcome is
  unreported

### Requirement: Each import is bounded, and a timeout stops the drain for that wake

Each per-asset import SHALL be bounded by a deadline. The bound SHALL be placed on the **wait** for the
library's completion callback, never on the library call itself: the asset-creation request returns to
its caller and only the awaiting coroutine suspends, so abandoning the wait frees a continuation and no
thread. An unbounded wait is not merely a lost photo — the import holds the download controller's
serializing lock, so every later reconcile, import, leave and switch in that process queues behind it
forever.

The deadline SHALL be set from measurement of legitimate imports, not from the wake budget. Measured on
device, one import takes 1.0 s at 49 MB and 5.2 s at 197 MB, and cost scales with resource size and
library size; the reporting field device runs approximately twice as slow. A bound that expires on healthy
imports manufactures unconfirmed rows, which is what the adjudication guard then has to reason about, so
the deadline SHALL carry several times the measured worst case.

When an import exceeds its deadline the drain SHALL stop for that wake rather than continue to the next
importable asset, and the expiry SHALL be logged. A stall in the photo library is a property of the
device at that moment, not of the photo: continuing would abandon further transactions, each of which may
still commit and so become a duplicate candidate.

An abandoned wait SHALL leave the asset un-imported in the store, so the existing durable retry path
imports it at a later wake, and SHALL record that ref as **unreported**, so no *absent* answer about it is
acted on until the library reports its outcome.

#### Scenario: A stalled import releases the lock

- **WHEN** an import's completion callback has not arrived when its deadline expires
- **THEN** the wait is abandoned, the controller's lock is released, the expiry is logged, and later
  reconciles and imports in that process proceed

#### Scenario: An abandoned wait is recorded as unreported

- **WHEN** an import's wait is abandoned on its deadline
- **THEN** that ref is recorded as unreported, so a later *absent* answer about its created asset is not
  acted on

#### Scenario: A timed-out import is retried later

- **WHEN** an import is abandoned at its deadline
- **THEN** the asset remains not-imported in the store and is imported at a subsequent wake

#### Scenario: One deadline stops the wake's drain

- **WHEN** an import exceeds its deadline while further assets are importable
- **THEN** no further import is attempted in that wake, and the remaining assets are drained at the
  next one

#### Scenario: A healthy import is not abandoned

- **WHEN** an import of a large resource takes several seconds and the library reports normally
- **THEN** the wait is not abandoned, and the row is confirmed rather than left unconfirmed
