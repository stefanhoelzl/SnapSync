## ADDED Requirements

### Requirement: The import lock covers the decision, and a claim provides the exclusion

The download controller's lock SHALL cover the **decision** — import selection, the claim below, the read of
an asset's staged resources, and every download-store write — and SHALL NOT be held across the photo-library
import call. A library call is synchronous, thread-blocking and unabandonable (cancellation is cooperative),
so holding the lock across it lets a stalled library block every reconcile, import, leave, switch and
staged-resource callback in the process. That callback is the sharp edge: it is delivered inside an
OS-granted wake, so blocking it can cost that wake its staging work.

Because the lock no longer spans the platform call, the mutual exclusion its *span* provided SHALL be
replaced by an explicit **claim**: before any import begins, that ref SHALL be taken out of circulation
under the lock, and SHALL NOT be offered as importable work to any concurrent pass until the library reports
its outcome. Without it two triggers can both find one asset importable before either records a marker, and
both create an asset — the duplicate this capability exists to prevent.

The claim SHALL be **in memory** and SHALL NOT survive the process: a claim that outlives the process which
owned the transaction is never released, and that photo never arrives. Nothing SHALL expire a claim on
elapsed time; a claim ends because the library reported, or because the process did.

The drain SHALL claim **one ref at a time**, and SHALL offer each ref at most once per drain. Claiming the
whole importable set up front makes one non-reporting import strand every other ref in that set until the
process is relaunched. Offering a ref more than once per drain live-locks it: a failed import leaves its row
importable *and* releases its claim, so the loop re-selects the same ref forever, spinning on any
permanently bad resource.

A claim SHALL be released when the library reports the outcome, and when adjudication establishes that the
created asset is *present* — the latter being the only recovery for a completion that is never delivered. A
claim SHALL be **retained** when the importing coroutine is cancelled: the transaction may still be open, and
treating "this coroutine is gone" as "this transaction is gone" is the inference this capability refuses.
The release SHALL happen **after** the writes that record the import, not before, or the row can move on
between the release and the write.

Imports of **distinct** refs MAY proceed concurrently. The per-ref claim is the only exclusion correctness
requires, and serializing the platform call behind a second lock would let one stalled import block every
other import for the life of the process, with nothing to end it.

#### Scenario: A stalled library blocks nothing else

- **WHEN** an import's completion has not arrived and other triggers fire
- **THEN** a reconcile, a staged-resource callback, a leave and a switch each proceed to completion
  without waiting for that import

#### Scenario: Two triggers cannot both import one asset

- **WHEN** two triggers reach the drain concurrently and one asset is importable
- **THEN** exactly one of them claims and imports it, and exactly one asset is created

#### Scenario: One stalled import strands no other ref

- **WHEN** one importable ref's import never reports and other refs are importable
- **THEN** a later trigger imports those other refs, skipping only the claimed one

#### Scenario: A permanently failing asset does not live-lock the drain

- **WHEN** an asset's import fails on every attempt and its row stays importable
- **THEN** the drain attempts it at most once in that pass and proceeds, rather than re-selecting it

#### Scenario: A relaunch releases every claim

- **WHEN** the process ends while refs are claimed and a later process drains
- **THEN** those refs are importable again, because no transaction from the previous process can still
  commit

#### Scenario: A cancelled import keeps its claim

- **WHEN** an importing coroutine is cancelled while its transaction may still be open
- **THEN** that ref stays claimed, so no *absent* answer about it is acted on and no second asset is created

## MODIFIED Requirements

### Requirement: An interrupted import is adjudicated, never repeated blindly

The client SHALL NOT create a second asset for a ref that already carries a created-asset marker.
An import that records its created asset but never records a confirmation — because the process ended, or
because the library has not yet reported — leaves the download store holding an **unconfirmed** row
(capability `download-store`), and such a row SHALL NOT be treated as ordinary import work. Before any
asset is created for a ref that already carries a marker, the client SHALL ask the photo library whether
that asset exists, and act on the answer:

- **present** — record the import against the marker it already holds and create nothing;
- **absent** — clear the marker, then import, **but only if no import for that ref is running in this
  process**;
- **unknown** — do nothing this pass, and retry later.

An *absent* answer SHALL NOT be acted on while an import for that ref is **claimed** — that is, from the
moment the ref is taken out of circulation for an import until the library reports that import's outcome.
The library answers about **committed** state, so it answers honestly that an asset does not exist while the
transaction creating it is still open; acting on that clears the marker of an asset that does exist, drops it
from the suppression set, and the device uploads a downloaded photo back into the event. A claimed ref SHALL
therefore be treated exactly as *unknown*.

The gate SHALL be the claimed/not-claimed **fact**, never an elapsed-time estimate of it. The process is
suspended for arbitrary spans between a change block and its completion, so any wall-clock bound expires
against transactions that are alive.

The record of claimed refs SHALL NOT survive the process. A transaction cannot outlive the process that
opened it, so after a relaunch every *absent* answer is trustworthy again; a durable record would instead
distrust a ref forever and its photo would never arrive.

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

The lookup SHALL be **batched** — one query per import pass, for every unconfirmed row at once — and
SHALL NOT be performed at all when no row carries a marker, which is the ordinary case.

The claimed/not-claimed gate SHALL be read **under** the download controller's lock, together with the
write it guards — never sampled before acquiring it. Reading it earlier reproduced this defect on real
hardware once already: the gate answer went stale while the adjudication queued on the lock, and the marker
of a live asset was cleared. The record of claimed refs is also mutated only under that lock, so reading it
from outside is a data race besides.

The lookup SHALL run **outside** the download controller's lock; only the gate and the verdicts' guarded
writes run under it. The library lookup is a synchronous, thread-blocking call that no timeout can abandon (cancellation is
cooperative), so performing it under the lock would let a stalled photo library block every reconcile,
import, leave, and switch. The adapter SHALL own its dispatcher hop for the same reason.

A *present* verdict SHALL also release that ref's claim. The library reporting that the asset exists is proof
the transaction landed, and without this release a completion that is never delivered pins the ref until the
process ends.

#### Scenario: A relaunch after an interrupted import creates no second asset

- **WHEN** an import records its created asset, the process ends before the import is confirmed, and a
  later pass reaches the same asset
- **THEN** the library is asked, the asset is found, the import is recorded against the existing created
  asset, and no second asset is created

#### Scenario: An absent answer about a claimed import is not acted on

- **WHEN** an import for a ref is claimed, its outcome has not been reported, and the library answers that
  the created asset is absent
- **THEN** the marker is kept, nothing is imported, and the row is retried on a later pass

#### Scenario: The same ref is adjudicated once its outcome arrives

- **WHEN** the photo library reports the outcome of an import and the ref's claim is released
- **THEN** a subsequent *absent* answer about it is acted on normally

#### Scenario: A relaunch makes absence trustworthy again

- **WHEN** the process ends while a ref is claimed and a later process adjudicates the same row
- **THEN** the ref is not treated as claimed, because no transaction from the previous process can
  still commit

#### Scenario: A stale absent verdict does not clear a settled row's marker

- **WHEN** an *absent* verdict is computed for a ref, and the library's completion settles that row
  before the verdict is applied
- **THEN** the guarded write matches no row, the marker is left intact, and the asset stays suppressed

#### Scenario: A stale present verdict is discarded

- **WHEN** a *present* verdict is computed for a marker and the row is settled under a different marker
  before that verdict is applied
- **THEN** the guarded write matches no row, and the marker the row now holds is left intact

#### Scenario: A present verdict releases the ref for later work

- **WHEN** an import's completion is never delivered and a later pass adjudicates its row as *present*
- **THEN** the row is settled and the ref's claim is released, so the ref is not pinned until relaunch

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
requires that no import for that ref is running in this process.

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
- **THEN** the answer is absent, and the adjudication does not act on it because an import for that ref is
  claimed

### Requirement: Staged bytes are released when their import is settled

The client SHALL release an asset's staged bytes once its import is **confirmed**, and SHALL release the
staged bytes of the rows a leave, switch, or durable state reset actually drops — taking those paths from
the prune itself, which returns what it stranded (capability `download-store`). A pass over assets whose
import is confirmed but whose staged bytes remain SHALL release those too, so installs that accumulated
bytes before this behaviour existed are reclaimed.

The client SHALL NOT read those paths **before** the prune as a separate step. That is two reads at two
instants over a store the photo library's change and completion blocks mutate without taking the client's
lock, so a marker cleared in between turns a row the read protected into a row the prune deletes — and its
files are then orphaned with nothing referencing them, unfindable and surviving relaunch.

The release SHALL happen **after** the confirming write commits, never before. Releasing first and
recording second loses the photo permanently if the process ends between them: the bytes are gone, the
resource is still recorded as staged, and a staged resource is never re-downloaded.

Bytes SHALL NOT be released for an import that failed, is still claimed, or is unconfirmed — those bytes are
the only source for the retry. Where the release follows a verdict, it SHALL happen only if that verdict's
guarded write took effect: releasing the bytes of a row that has moved on destroys the staged files a live
import is reading from. Release SHALL be best-effort: a failure to delete SHALL never fail an import.

Bytes SHALL be released only where they can be **positively attributed** to a confirmed or dropped row.
The client SHALL NOT delete staged files inferred to be unreferenced by scanning storage: a transfer
moves its bytes into place before the store records them, so a scan can delete a file whose row is about
to claim it is staged — losing that photo permanently.

#### Scenario: A received photo does not occupy storage twice forever

- **WHEN** a foreign asset's import is confirmed
- **THEN** its staged bytes are released, while the row recording the created asset is retained

#### Scenario: A retry still has its bytes

- **WHEN** an import fails or its outcome has not been reported and a later pass retries it
- **THEN** the staged bytes are still present and the retry imports from them

#### Scenario: A discarded verdict releases nothing

- **WHEN** a *present* verdict's guarded write matches no row because the row moved on
- **THEN** that row's staged bytes are not released

#### Scenario: Bytes are not stranded by a leave

- **WHEN** rows are dropped on leave, switch, or reset
- **THEN** the prune returns exactly those rows' staged paths and the client releases them, so no file is
  left with nothing referencing it and no surviving row loses the bytes it still needs

### Requirement: Import without foreground; relaunch and backstop

Import SHALL run without the app being foregrounded: a download completing while the app is
backgrounded SHALL trigger import in the background-execution window, and a download completing while
the app is terminated SHALL relaunch the app via `handleEventsForBackgroundURLSession` to finish.
The imports that a background-session wake triggers SHALL be **awaited** by that wake — the staged-resource
callback SHALL be awaitable and its outstanding work tracked by the download-job owner, rather than
dispatched and forgotten by the composition — so the wake reports itself finished only when its work is.
Reporting the session's events drained while the imports they caused are merely queued is what leaves an
asset staged-but-unimported at suspension.

An import that never reports is the one case that awaiting cannot resolve, and it is bounded at the **wake**
rather than at the import: the OS completion handler is released on its own per-entry-point deadline and the
import is left running (capability `ios-app-shell`). Nothing bounds a single import in time. A wall-clock
bound on one import expires against transactions that are alive — the process is suspended for arbitrary
spans between a change block and its completion — and every expiry manufactures an unconfirmed row for the
adjudication guard to reason about. The stalled import blocks no other work, because it does not hold the
download controller's lock and its ref is claimed rather than serialised.

Because no further download event wakes the app once transfers are exhausted, the client SHALL also
drain pending imports via an OS-scheduled background task (e.g. `BGProcessingTask`) so an import that
overran its wake window still completes without a foreground visit. Staged bytes + the store make any
deferred import a safe retry. The backstop's coordination — the trigger-time membership re-read
(`reloadConfig` — see `ios-app-shell`, *Background triggers re-read the membership and fail cleanly
before first unlock*), the attestation wake, then the import drain — SHALL be the
`flow/DownloadBackstop` trigger (`:domain` `flow/`, built in `compose/` with the re-read and wake
injected as **suspend** effect lambdas, per the law *A trigger flow never outlives its own run*); the
untested app shell keeps only the entry-point log wrap, the re-arm, and the OS task-completion handler.
A backstop wake landing before the first unlock since boot fails cleanly and converges at the next wake
(the import's reads are caught; the adapters distinguish unreadable from absent; nothing mints, clears,
or leaves).

That last property is **conditional, and the transfer check is its condition**. A deferred import is a safe
retry only because staged bytes were accounted for at transfer time. Absent that check, a permanently
invalid body — an error document staged under a photo's path — makes the retry a trap rather than a
safeguard: the import fails on every reconcile, and the transfer is never re-run, because a resource
recorded as staged is never re-planned. The asset is then permanently unimportable and permanently retried,
and the photo never arrives. Retrying a failed import is correct for a transient failure and poison for
invalid bytes; only rejecting bad bytes before staging keeps the two apart.

#### Scenario: Background import on download completion

- **WHEN** a download completes while the app is backgrounded (not foreground)
- **THEN** the asset whose set is now complete is imported in the background

#### Scenario: A wake awaits the imports it triggered

- **WHEN** a background-session wake delivers several staged resources
- **THEN** the imports they trigger are awaited, so the wake does not report itself finished while they are
  merely queued

#### Scenario: A wake whose import never reports still answers the OS

- **WHEN** an import a wake triggered never receives its completion
- **THEN** the OS completion handler is released on that entry point's deadline, the import is left
  running rather than cancelled, and no other reconcile, import, leave or switch is blocked by it

#### Scenario: Import tail is drained without foreground

- **WHEN** an asset's resources are all staged but its import did not complete in a download-wake
  window and no further download is pending
- **THEN** a scheduled background task completes the import without requiring the user to open the app

#### Scenario: An invalid body never reaches the importer

- **WHEN** a transfer's bytes are rejected on status or length
- **THEN** they are never staged, so no import is ever attempted against them and no asset becomes
  permanently unimportable

### Requirement: The download session's OS handler is bounded, and its adoption is visible

This requirement landed on `main` while this change was in flight (`fix(ios): put both background-session
handlers back inside a bounded receipt`), and one sentence of it describes a mechanism this change
**deletes**: it cited the import deadline as bounding a single import "so it cannot hold the controller's
lock forever". Both halves of that stop being true here — the deadline is gone, and the import no longer
runs under the lock. Only that sentence is rewritten; the requirement itself is untouched and complementary,
since the two bounds govern different things (this one, how long the OS is kept waiting; nothing at all,
how long one import may take).

The two changes converged independently on the same conclusion — that awaiting imports without a bound is
wrong, and that the receipt should release the handler while letting the work run on — which is why the
mechanisms compose and only the prose conflicted.

#### Scenario: The handler is released after the imports, within the bound

- **WHEN** a background-session wake delivers staged resources and the imports they trigger finish inside
  the bound
- **THEN** the OS completion handler is released after those imports, on the main thread

#### Scenario: A stalled import does not strand the handler

- **WHEN** an import started by a background-session wake has not reported when the bound expires
- **THEN** the OS completion handler is released, the expiry is logged, and the import continues rather
  than being cancelled

#### Scenario: The adoption is readable in a dump

- **WHEN** the OS relaunches the app to deliver download-session events
- **THEN** the adoption is logged with its entry point, so a later dump shows the wake arrived and what
  became of its handler

## REMOVED Requirements

### Requirement: Each import is bounded, and a timeout stops the drain for that wake

**Reason**: The bound's stated job was protecting the download controller's lock — *"the import holds the
download controller's serializing lock, so every later reconcile, import, leave and switch in that process
queues behind it forever"*. Once the lock does not span the platform call there is nothing left for a
per-import clock to protect, and the wake it would otherwise bound is already bounded by the OS-completion
receipt (capability `ios-app-shell`), which releases its handler on expiry and lets the work run on. Keeping
the clock would restate the mistake this capability already names: the process is suspended for arbitrary
spans between a change block and its completion, so a wall-clock bound expires against transactions that are
alive, and every expiry manufactures an unconfirmed row for adjudication to reason about.

Its stop-the-drain rule goes with it. That rule existed so a stalled library would not have one transaction
abandoned per remaining asset; with no bound nothing is abandoned, and a stall now costs its own trigger the
rest of its drain while every other trigger proceeds — which is what the claim makes possible.

**Migration**: No stored state or API is affected. An import that does not report now leaves its ref claimed
for the life of the process instead of recording it as unreported; adjudication treats both identically. A
stuck import stays visible through the per-invocation enter/exit trace (capability `diagnostic-logging`),
which shows an import that entered and never exited.
