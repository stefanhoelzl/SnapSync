## ADDED Requirements

### Requirement: An interrupted import is adjudicated, never repeated blindly

The client SHALL NOT create a second asset for a ref that already carries a created-asset marker.
An import that records its created asset but never records a confirmation — because the process ended, or
because its wait was abandoned on its deadline — leaves the download store holding an **unconfirmed** row
(capability `download-store`), and such a row SHALL NOT be treated as ordinary import work. Before any
asset is created for a ref that already carries a marker, the client SHALL ask the photo library whether
that asset exists, and act on the answer:

- **present** — record the import against the marker it already holds and create nothing;
- **absent** — clear the marker, then import;
- **unknown** — do nothing this pass, and retry later.

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

#### Scenario: The orphan is never uploaded

- **WHEN** an interrupted import is adjudicated as present
- **THEN** the created asset stays in the suppression set throughout, and no upload job is ever created
  for it

#### Scenario: A created asset that never materialised is retried

- **WHEN** an unconfirmed row's asset is definitively absent from the library
- **THEN** the marker is cleared and the asset is imported, so the photo still arrives

#### Scenario: An unanswerable lookup defers rather than guesses

- **WHEN** the library cannot give a trustworthy answer about an unconfirmed row
- **THEN** nothing is imported and nothing is recorded, and the row is retried on a later pass

### Requirement: Asset presence is answered by the photo-access grant

The presence lookup SHALL be answered differently according to the current photo-access grant, because
an "absent" answer is only trustworthy under a **full** grant:

- **full access** — the library is queried directly; both *present* and *absent* are authoritative;
- **partial access** — the answer comes from the selection the app already holds; a hit is *present*, a
  miss is **unknown**, never *absent*, because an asset created under a full grant is real but invisible
  after a downgrade (app-created assets join the selection only at creation time);
- **no usable grant** — **unknown**, because a query returns nothing for assets that exist, and imports
  cannot succeed anyway.

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

### Requirement: Staged bytes are released when their import is settled

The client SHALL release an asset's staged bytes once its import is **confirmed**, and SHALL release the
staged bytes of rows it is about to drop on leave, switch, or a durable state reset. A pass over assets
whose import is confirmed but whose staged bytes remain SHALL release those too, so installs that
accumulated bytes before this behaviour existed are reclaimed.

The release SHALL happen **after** the confirming write commits, never before. Releasing first and
recording second loses the photo permanently if the process ends between them: the bytes are gone, the
resource is still recorded as staged, and a staged resource is never re-downloaded.

Bytes SHALL NOT be released for an import that failed, was abandoned on its deadline, or is unconfirmed
— those bytes are the only source for the retry. Release SHALL be best-effort: a failure to delete SHALL
never fail an import.

Bytes SHALL be released only where they can be **positively attributed** to a confirmed or dropped row.
The client SHALL NOT delete staged files inferred to be unreferenced by scanning storage: a transfer
moves its bytes into place before the store records them, so a scan can delete a file whose row is about
to claim it is staged — losing that photo permanently.

#### Scenario: A received photo does not occupy storage twice forever

- **WHEN** a foreign asset's import is confirmed
- **THEN** its staged bytes are released, while the row recording the created asset is retained

#### Scenario: A retry still has its bytes

- **WHEN** an import fails or is abandoned on its deadline and a later pass retries it
- **THEN** the staged bytes are still present and the retry imports from them

#### Scenario: Bytes are not stranded by a leave

- **WHEN** rows are dropped on leave, switch, or reset
- **THEN** their staged bytes are released first, so no file is left with nothing referencing it

## MODIFIED Requirements

### Requirement: Deletion is respected — no re-download

Once a foreign asset's import is **confirmed** (a terminal download-store row), the client SHALL NOT
download or import it again, even if the user later deletes the imported asset from their library. The
same asset linked into more than one event SHALL be imported only once (cross-event dedup via the store).

This guarantee attaches to a **confirmed** import. For an **unconfirmed** row — one whose created-asset
marker was recorded but never confirmed — a library lookup reporting *absent* cannot distinguish "the
user deleted it" from "the commit never landed", and the client SHALL import. That resurrects a deleted
photo at most **once**: the resulting import is confirmed, after which this requirement applies to it
normally. The alternative — treating *absent* as deletion — would permanently lose a photo whose commit
genuinely failed, invisibly and with no retry, since cross-event dedup blocks every later attempt.

#### Scenario: A deleted collected photo is not restored

- **WHEN** the user deletes a previously-imported foreign photo and a later sync runs
- **THEN** the asset is not re-downloaded or re-imported

#### Scenario: The same asset across two events imports once

- **WHEN** a device's asset appears in the unions of two events this install joins
- **THEN** it is imported only the first time and skipped thereafter

#### Scenario: A deleted photo from an unconfirmed import returns at most once

- **WHEN** the user deletes an asset whose import was never confirmed, and a later pass adjudicates that
  row as absent
- **THEN** the photo is imported once more and that import is confirmed, so deleting it again is
  respected permanently
