package app.snapsync.feature.download

import app.snapsync.model.runCatchingCancellable
import app.snapsync.services.backend.EventUnionSource
import app.snapsync.model.AlbumId
import app.snapsync.model.ImportRequest
import app.snapsync.model.ImportResult
import app.snapsync.services.downloads.DownloadJobs
import app.snapsync.services.gallery.ImportedAssetPresence
import app.snapsync.services.gallery.GalleryImporter

import app.snapsync.model.AssetPresence
import app.snapsync.model.AdoptedAsset
import app.snapsync.model.AssetRef
import app.snapsync.services.downloads.DownloadService
import app.snapsync.model.PlannedAsset
import app.snapsync.model.PlannedResource
import app.snapsync.model.EntryScope
import app.snapsync.services.staging.StagingService
import app.snapsync.services.wake.EventCheck
import app.snapsync.services.wake.EventChecks
import app.snapsync.model.StagedResource
import app.snapsync.model.UnconfirmedImport
import app.snapsync.model.UnionPage
import app.snapsync.model.UnionTrigger
import app.snapsync.model.invocation
import co.touchlab.kermit.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The device-side download/import orchestrator (capability `receiving-photos`). Reads the event-wide
 * union, selects **foreign** assets (`deviceId != myDeviceId`) not already imported, records them in
 * the [store], enqueues their resource downloads, and imports any asset whose resources are all staged.
 * It owns no transport or PhotoKit detail — those are the [jobs] and [importer] seams — so it is
 * exercised in `commonTest` with fakes. Network/union failures keep last-good state (never throw).
 */
class DownloadController(
    private val union: EventUnionSource,
    private val store: DownloadService,
    private val jobs: DownloadJobs,
    private val importer: GalleryImporter,
    // Adjudicates a row whose asset was created but whose import was never confirmed (capability
    // `receiving-photos`). Required, with no default: a permissive stand-in would answer "absent" for
    // assets that exist, clear their markers, and re-import them — which is the defect this guard is
    // here to prevent, reintroduced by the thing meant to prevent it.
    private val presence: ImportedAssetPresence,
    // The event album an import files into, for the current membership — `null` for none (opted out, not
    // created yet, or a folder album the member emptied). Read once per import, BEFORE it (capability
    // `event-album`), so the platform's change block does no lookup of its own. Required: which album is the album
    // feature's rule, bound by the composition.
    private val eventAlbum: suspend () -> AlbumId?,
    // Told when an import settled as imported INTO [eventAlbum]'s answer — how a folder album learns it has held a
    // photo, which is what later tells an emptied one from a fresh one
    // (`changes/archive/2026-09-30-android-event-album` D4).
    private val onImportedIntoAlbum: suspend (AlbumId) -> Unit,
    // Where staged bytes live, what is still on disk, and the release of settled rows' bytes (capability
    // `receiving-photos`). Required: a composition that downloads must say where the bytes land.
    private val stagedBytes: StagingService,
    private val myDeviceId: String,
    // The download arm runs only when the current membership's participation direction includes download
    // (capability `join-event`): an upload-only membership performs no reconcile at ANY trigger. Injected
    // as a plain predicate so this capability gains no config dependency; the composition root binds it to
    // `EventConfig.direction.includesDownload`. This is the SINGLE choke point — every trigger (join,
    // foreground, push) funnels through `reconcile`, so the gate lives here and not in the untested shell.
    // It is orthogonal to the push receiver's active-event guard (which answers "is this push for my event").
    //
    // **Three-valued, and required.** `true` = joined and the direction includes download; `false` = joined
    // but upload-only; `null` = **no membership at all**. Those last two are different answers and neither
    // enables the arm — collapsing them is not a nicety. This was `() -> Boolean = { true }`, bound at the
    // root with a `?: true`, so "we have no membership" resolved to "download freely": the same `?: true`
    // shape the former upload arm's KDoc blamed for starting an upload producer for an event that did not exist. It was
    // unreachable only because every caller happened to pass a config-derived event id — a property of the
    // callers, not of the gate. The default is gone for the same reason the cutoff and the reconcile have
    // none: a permissive default on a safety gate is how a caller ships without one.
    private val downloadEnabled: () -> Boolean?,
    // When a background wake last read the union (decision record `changes/timely-background-receiving`, D4): every
    // read stamps it, and [reconcileIfDue] reads the union only when an hour has passed. Required: the bound is what
    // keeps a busy heartbeat from reading a whole union per wake.
    private val checks: EventChecks,
    // Whether the drain may import now (capability `receiving-photos`): a usable grant, AND the current membership's
    // received photos already recognised by their SnapSync mark (`ReceivedPhotoAdoption.ensureAdopted`, which the
    // composition runs here — once per membership per process, and again while the library cannot be read). The drain
    // imports NOTHING otherwise. After a reinstall the rejoin provisions with the access dialog still open, and every
    // import that overtakes the recognition lands a second copy of a photo the library holds: measured on the SE2 —
    // without a grant gate all four of a rejoin's photos (2026-09-30), and with one, the foreground that follows the
    // dialog importing while the grant's own pass was still reading names (2026-10-01). Asked here, every import path
    // waits for it, whichever trigger reaches the drain first. Required, and with no default, for the reason
    // [downloadEnabled] has none.
    private val readyToImport: suspend () -> Boolean,
    private val log: Logger = Logger.withTag("DownloadController"),
    private val entryContext: EntryScope = EntryScope.None,
) {

    // Serializes all store-mutating flows. Both join (`provisionEvent`) and foreground fire `reconcile`,
    // and downloads complete on the URLSession delegate.
    //
    // It covers the DECISION, never the WORK: selection, the claim below, the staged-resource read and
    // every store write run under it; the photo-library call does not. A library call is synchronous,
    // thread-blocking and unabandonable (cancellation is cooperative), so holding this across one queues
    // every later reconcile, import, leave, switch AND `onResourceStaged` behind a stalled library — the
    // SNAPSYNC-6 field hang, held from 09:03:37 until the process died. `onResourceStaged` is the sharp
    // edge: it is called from the background `URLSession` delegate inside an OS-granted wake, so blocking
    // it can cost that wake its staging work.
    private val mutex = Mutex()

    /**
     * The refs whose import is running in THIS process: claimed under [mutex] before the platform call,
     * released when the library reports.
     *
     * This is the mutual exclusion the lock's *span* used to provide. Without it two triggers can both find
     * an asset importable before either records a marker, and both create one (observed on device).
     *
     * **Three readers, and each asks about THIS ref** — which is what makes claim-granularity safe for all
     * of them. A ref is claimed *before* its change block runs, so the set is a superset of "a transaction
     * is genuinely open", and a superset only ever makes each reader more conservative:
     *
     *  - **selection** — a claimed ref is not offered as importable work, so no second import starts.
     *  - **adjudication's ABSENT gate** — while a ref is claimed, the library's answer that its asset is
     *    absent means nothing: the library answers about COMMITTED state, so it answers honestly that an
     *    asset does not exist while the transaction creating it is still open. Acting on that clears the
     *    marker of a live asset, which drops it from the suppression set and sends someone else's photo
     *    back into their event (Bugsink SNAPSYNC-9: 19 such clears, each 9-44 ms after that same asset was
     *    created — a live transaction, not an expired wait).
     *  - **the prune's `protecting`** — a claimed ref's row carries no marker yet, so no state-based
     *    predicate can tell it from ordinary prunable work; dropping it makes the change block's marker
     *    write land on nothing (capability `receiving-photos`).
     *
     * **The reader that is NOT here is the point.** A superseded design
     * (`parked/settle-imports-by-transaction`) used one registry for these AND for wake quiescence — "is
     * this wake's work finished?" — and that one broke: membership begins at the CLAIM, so work that had
     * been launched but not yet claimed was invisible to it, and a wake could report itself finished with
     * imports pending. That question is not about any ref, so no superset argument covers it. It is
     * answered elsewhere and already: the tail AWAITS its drain, and the wake holds its background time until then
     * (capability `sync-status`). This field is private so that answer cannot be sought here.
     *
     * **No clock, anywhere.** A claim ends because the library reported, or because the process did. The
     * process is suspended for arbitrary spans between a change block and its completion (measured 116 s
     * and 254 s), so any elapsed-time bound would expire against transactions that are alive.
     *
     * **In memory, and its erasure is load-bearing.** This set records imports running **here**; a process
     * that has ended is running none. A durable claim would outlive the process that owned the transaction
     * and would never be released, so that photo would never arrive.
     *
     * ⚠️ It does **not** rest on the premise that a `performChanges` transaction cannot outlive its
     * process. That premise was inherited from the prior change's D2 and has since been **measured false**
     * (SE2 / iOS 26.5.2, 2026-08-09: a SIGKILL 200 ms after the change block returned still left the asset
     * in the library). What makes the post-relaunch path safe is the *present* branch, which finds that
     * asset and settles the row against the marker it already holds. The residual — a relaunch adjudicating
     * while a surviving commit is still in flight — is accepted, and pinned by
     * `a_surviving_commit_still_in_flight_at_relaunch_is_the_accepted_residual`. Decision record:
     * `changes/archive/2026-08-10-take-imports-off-the-download-lock`.
     *
     * A plain `MutableSet`, not an atomic one: unlike the record it replaces, nothing outside [mutex]
     * writes it — the platform's completion callback resumes the drain, and the drain takes the lock.
     */
    private val importing = mutableSetOf<AssetRef>()

    /**
     * Whether this device holds every photo of the others it receives (capability `manage-membership`, "The app leaves
     * on its own once the event is finished for it"): every foreign asset the event serves is SETTLED here — imported,
     * or deleted by the member, or judged unimportable — and nothing is still waiting to download or to import. A
     * membership that does not receive has nothing to wait for. A union that cannot be read answers `false`: the doubt
     * keeps the member. Always a FULL read (decision record `changes/incremental-union`, D6): it runs only once the
     * event has closed, so its union no longer changes, and it is the one decision where doubt must keep the member.
     */
    suspend fun everythingReceived(eventId: String): Boolean =
        downloadEnabled() != true || holdsEveryForeignPhoto(eventId)

    /**
     * [everythingReceived] for a membership that receives, asked without the joined configuration — what a leave asks
     * once it has cleared that configuration (capability `manage-membership`): every foreign asset the event serves is
     * settled here and nothing waits to download or import. A union that cannot be read answers `false`. A FULL read
     * too, for the same reason: doubt must not report a member that lacks photos as having everything.
     */
    suspend fun holdsEveryForeignPhoto(eventId: String): Boolean {
        val assets = union.union(eventId, null, UnionTrigger.LEAVE_CHECK).getOrElse { return false }.assets
        val foreign = assets.filter { it.deviceId != myDeviceId }.map { AssetRef(it.deviceId, it.assetId) }
        return mutex.withLock {
            store.settledAmong(foreign).size == foreign.size &&
                store.pendingDownloads().isEmpty() &&
                store.importableAssets().isEmpty()
        }
    }

    /**
     * [reconcile] for a background wake, unless the union was read within the hour (capability `receiving-photos`, "New
     * photos are announced by a silent wake, and never only by it"; decision record `changes/timely-background-receiving`,
     * D4): what a background wake runs, so others' photos arrive when no push does, at no more than one union read per
     * hour per event. A push, an opening and a join call [reconcile] themselves — each has a reason to read now.
     */
    suspend fun reconcileIfDue(eventId: String) {
        if (!checks.due(EventCheck.PHOTOS, eventId)) {
            log.i { "union read within the hour — this wake reads none" }
            return
        }
        reconcile(eventId, UnionTrigger.WAKE)
    }

    /**
     * Discover + plan + enqueue, idempotently. Safe to call on join and on every foreground: already-imported and
     * already-planned assets are no-ops, and only not-yet-staged resources enqueue.
     *
     * **Incremental** (decision record `changes/incremental-union`, D6): a [trigger] that is not [UnionTrigger.full] — a
     * push, a background wake — reads only what the union gained after the position this event was last read to, and a
     * push whose [announced] position this device has already read reads nothing at all. Every other trigger, and any
     * read with no stored position, reads the whole union. The position the read covered is stored in the same
     * transaction as its plan.
     *
     * **A full read prunes** (D7; capability `photo-sharing`, a withdrawn photo stops being offered to members who have
     * not received it): this event's rows the union no longer lists, and that this device has not received, are
     * dropped with their staged bytes — never settled, so a photo that comes back is planned again by a later read. An
     * import already claimed is spared, as every prune spares it.
     *
     * **It imports nothing** (capability `receiving-photos`, "A failed union fetch still drains the staged imports"):
     * the import drain is the process tail's first unit ([importReady]), which every caller's wake requests after
     * its own work — whatever the union answered. A reconcile that drained would be a second import path beside the
     * tail's, running concurrently with it at foreground, which the single-flight tail exists to rule out (decision
     * record `changes/own-work-per-wake`, D1).
     *
     * It plans from [known] when a caller read the union in this same act — a join, whose adoption read it seconds
     * before (`JoinUnion` in the composition) — and reads its own otherwise. Either way it stamps the hour: a union
     * was read for this event now.
     */
    suspend fun reconcile(
        eventId: String,
        trigger: UnionTrigger,
        announced: Long? = null,
        known: UnionPage? = null,
    ) = log.invocation(
        entryContext,
        "reconcile",
        params = "eventId=$eventId trigger=${trigger.wire}" +
            (announced?.let { " announced=$it" } ?: "") + if (known != null) " (the join's union)" else "",
    ) {
        // `!= true` covers BOTH non-answers: an upload-only membership (`false`) and no membership at all
        // (`null`). Neither enables the arm, and neither is inferred from the other.
        if (downloadEnabled() != true) {
            // Upload-only membership, or none: skip discovery entirely (no union fetch, no enqueue, no import).
            log.i { "reconcile skipped — this membership does not download" }
            return@invocation
        }
        val stored = store.union.cursor(eventId)
        if (known == null && announced != null && stored != null && stored >= announced) {
            log.i { "the union was already read to $stored, past the announced $announced — nothing to read" }
            return@invocation
        }
        // Every read counts, a failing one too: a backend that fails is asked no more often than one that answers.
        checks.stamp(EventCheck.PHOTOS, eventId)
        val from = stored.takeIf { !trigger.full && known == null }
        // A failed union fetch costs this wake its DISCOVERY, not its imports: the tail that follows the wake's own
        // work drains what is staged whatever the union answered, because the drain reads only the store and the
        // bytes already on disk.
        val page = known ?: union.union(eventId, from, trigger).getOrElse {
            log.w(it) { "union fetch failed — keeping last state; the tail still imports what is staged" }
            return@invocation
        }
        mutex.withLock { planLocked(eventId, page, stored, from) }
    }

    /**
     * Plan [page] — read from [from], `null` for the whole union — and enqueue what is not yet staged, under [mutex];
     * store the position the read covered when it moved past [stored], and prune after a full read ([reconcile]).
     */
    private suspend fun planLocked(eventId: String, page: UnionPage, stored: Long?, from: Long?) {
        // Own contribution is already in this library; only foreign assets are download work.
        val foreign = page.assets.filter { it.deviceId != myDeviceId }
        val foreignRefs = foreign.map { AssetRef(it.deviceId, it.assetId) }
        // ONE read of which of them are settled (imported or unimportable — delete-proof / cross-event
        // dedup), and ONE transaction planning the rest. Per asset this was a query plus a transaction,
        // each transaction a durable commit, and a background wake planning a 101-asset backlog spent
        // ~11.5 s on it (iPhone XS) — on every trigger while the backlog lasted.
        //
        // The snapshot is read under this lock, and every writer that can make a row terminal takes it
        // too, except the importer's completion (`confirmCreatedLocalId`), which runs on the platform's
        // queue. That one settles only a row that is mid-import: its resources are all staged, so
        // re-planning it inserts nothing and refreshes no url — exactly what the per-asset
        // read-then-plan pair (never atomic against that writer either) already allowed.
        val settled = store.settledAmong(foreignRefs)
        val plans = foreign.mapNotNull { asset ->
            val ref = AssetRef(asset.deviceId, asset.assetId)
            if (ref in settled) return@mapNotNull null
            PlannedAsset(ref, asset.creationDate, asset.resources.map {
                PlannedResource(it.key, it.url, it.role, it.contentType, it.originalFilename)
            })
        }
        // Tag the whole foreign union with this event, settled refs included: an imported photo of THIS event
        // counts as received on the joined screen, and one imported for an earlier event stops counting. The
        // position the read covered lands in the same transaction — only when it moved: a wake that found nothing
        // new costs no durable commit (position 0, no gain yet, is the same as none).
        store.planAll(plans, eventId, members = foreignRefs, cursor = page.cursor.takeIf { it != (stored ?: 0L) })
        if (from == null) pruneWithdrawnLocked(eventId, foreignRefs.toSet())
        log.i {
            "reconcile: ${page.assets.size} union asset(s) (${if (from == null) "full" else "since $from"}), " +
                "${plans.size} foreign planned, position ${page.cursor}"
        }
        // Enqueue the not-yet-staged resources to the OS, then mark them in-flight so the status
        // line's download arrow can pulse (superseded once each stages). Idempotent: re-marking an
        // already-enqueued or already-staged resource is harmless (staged rows are excluded). One
        // transaction for the whole batch, not an autocommit per resource.
        val pending = store.pendingDownloads()
        jobs.enqueue(pending)
        if (pending.isNotEmpty()) store.markAllEnqueued(pending)
    }

    /**
     * Drop [eventId]'s withdrawn, not-yet-received rows and free their staged bytes — under [mutex], against ONE view of
     * what is claimed, for [releaseAndPruneLocked]'s reasons. A transfer still in flight for a dropped row stages onto no
     * row and its bytes are discarded ([onResourceStaged]).
     */
    private suspend fun pruneWithdrawnLocked(eventId: String, listed: Set<AssetRef>) {
        val stranded = store.union.pruneWithdrawn(eventId, listed, protecting = importing.toSet())
        if (stranded.isNotEmpty()) log.i { "a full read pruned withdrawn photos — ${stranded.size} staged file(s) freed" }
        runCatchingCancellable { stagedBytes.release(stranded) }
            .onFailure { log.w(it) { "releasing withdrawn staged bytes failed — files left behind" } }
    }

    /**
     * A resource's bytes finished downloading and were moved to durable staging (called by the
     * background-`URLSession` delegate, possibly while backgrounded / on relaunch). Records it staged — and that is
     * all: staging is a download wake's own work, and the import it makes possible is the tail's first unit, which
     * the composition requests once the staging is recorded (capability `receiving-photos`, "Import without foreground;
     * staged by the wake, imported by the tail"). Answers whether a row took it; a staging no row takes has its file
     * discarded here.
     */
    suspend fun onResourceStaged(ref: AssetRef, resourceKey: String, stagedPath: String): Boolean =
        log.invocation(entryContext, "onResourceStaged", params = "key=$resourceKey", result = { "recorded=$it" }) {
            val recorded = mutex.withLock { store.markStaged(ref, resourceKey, stagedPath) }
            // No row took it: the transfer outran a leave's prune, or a relaunched process inherited it for an event it
            // has left. Nothing references the file, so it goes now rather than sitting unreclaimable.
            if (!recorded) {
                log.i { "staged $resourceKey has no row to record against — its file is discarded" }
                runCatchingCancellable { stagedBytes.release(listOf(stagedPath)) }
                    .onFailure { log.w(it) { "discarding the unrecorded staged file failed — left behind" } }
            }
            recorded
        }

    /**
     * Import every asset whose resources are all staged and that is not yet imported — the process tail's unit ①,
     * and the only import drain any wake runs.
     *
     * [stopRequested] is the operating system's "time is up", forwarded (capability `sync-status`, "Expiry stops
     * work cooperatively at the next boundary"): checked before each import is claimed, so the import in flight runs
     * to its report and no further one starts. Claim semantics are unchanged by a stop — an import that never reports
     * keeps its claim — and every import left unstarted is a safe retry off its staged bytes.
     *
     * [awaitImport] runs one import and decides how long to wait for it. Inline by default. The tail passes one that
     * gives the wait up — leaving the import claimed and running — when its time is up or another request is due, so a
     * transaction that never reports holds no one hostage ("A stalled import blocks no other work"); the drain then
     * moves on to the next importable asset, which the claim keeps from being the stalled one.
     */
    suspend fun importReady(
        stopRequested: () -> Boolean = { false },
        awaitImport: suspend (import: suspend () -> Unit) -> Unit = { it() },
    ) = log.invocation(entryContext, "importReady") {
        drainImportable(stopRequested, awaitImport)
    }

    /**
     * Record [adopted] — photos already in the library that a join recognised as refs' earlier imports (capability
     * `receiving-photos`) — under the same lock as every other decision here, so a ref this process has CLAIMED for an
     * import is never adopted underneath it: that import's own marker settles it. Answers the refs recorded.
     */
    suspend fun settleAdopted(adopted: Collection<AdoptedAsset>, eventId: String): Set<AssetRef> =
        mutex.withLock { store.adoptAll(adopted.filter { it.ref !in importing }, eventId) }

    /**
     * The process's one recovery pass: adjudicate what a dead process left behind, then drain.
     *
     * The drain is not optional and not a caller's business. The *absent* branch **clears a marker**, and
     * clearing it is what returns the row to importable work; a clear that nothing then imports moves the
     * stall from "blocked by a marker" to "waiting for some later trigger". Pairing them here is what
     * makes this one call a complete recovery rather than half of one.
     *
     * Invoked once per process, from the composition's startup path, and from nowhere else — see
     * [adjudicateUnconfirmed] for why every other call site was removed.
     */
    suspend fun sweepInterruptedImports() = log.invocation(entryContext, "sweepInterruptedImports") {
        adjudicateUnconfirmed()
        drainImportable()
    }

    /**
     * The per-process recovery sweep (capability `receiving-photos`): settle the rows this process
     * **inherited** — an asset was created for them and the confirmation never arrived, because the
     * process that opened the transaction died.
     *
     * **Called from exactly one place: the composition's startup path.** It is deliberately NOT the first
     * act of `reconcile`, `importReady` and `onResourceStaged` any more. Those fire once per trigger and
     * once per staged resource, and during a burst the only unconfirmed row is the import currently in
     * flight — whose transaction is open, so the library can only answer *absent*, and whose *absent* the
     * gate below is required to discard. Measured on an iPhone XS: 1,164 verdicts in one 131-asset burst,
     * **1,149 of them thrown away**, each one a synchronous XPC round-trip. Nothing a running process can
     * observe changes the answer for a row it opened itself; only a process that has died leaves a row no
     * running import will settle.
     *
     * **Its dominant outcome is *present*, not *absent*.** A `performChanges` commit survives the death of
     * the process that opened it (measured, SE2 / iOS 26.5.2, 2026-08-09), so the ordinary inherited row
     * has a real asset behind it: settle it, release its bytes, and let the download count stop reporting
     * it as outstanding. The *absent* branch is for the two narrow cases — a death inside the change block
     * before the transaction was submitted, and a commit that genuinely failed with no completion
     * delivered — not the reason this runs.
     *
     * **Runs OUTSIDE [mutex], deliberately.** The presence lookup is a synchronous, thread-blocking
     * platform call that no timeout can abandon (cancellation is cooperative), so holding the lock across
     * it would let a stalled photo library block every reconcile, import, leave and switch behind it —
     * the exact pathology that bounding each import's wait exists to prevent. Off the lock it parks one
     * background thread instead.
     *
     * **Staleness between the phases is NOT harmless**, and each verdict is therefore applied through a
     * store write GUARDED on the marker it was computed for (capability `receiving-photos`). A row can settle
     * between the lookup and the write — the completion callback runs on the platform's queue and takes no
     * lock — and applying either verdict to a row that has moved on overwrites a live suppression handle:
     * the asset stays in the library with nothing recording that it must not be uploaded.
     *
     * The guard is in the write and not in a re-check here, because a read-then-write pair under this lock
     * is not atomic against a writer that does not take it. This code used to hold that pair, and it was
     * correct only by the narrowest margin; the store now answers "did my verdict apply?" atomically, at
     * the moment it matters.
     *
     * Costs nothing in a process that inherited nothing: one store read that returns nothing, and no
     * platform call at all. That was always the claim; running per staged resource is what made it false,
     * because a burst always has an import open.
     */
    private suspend fun adjudicateUnconfirmed() {
        val unconfirmed = store.unconfirmedImports()
        if (unconfirmed.isEmpty()) return

        val found = presence.presence(unconfirmed.mapTo(mutableSetOf()) { it.createdLocalId })
        for (row in unconfirmed) {
            val answer = found[row.createdLocalId] ?: AssetPresence.UNKNOWN
            val note = mutex.withLock { apply(row, verdictFor(row, answer)) }
            log.i { "adjudicated ${row.ref.sourceAssetId}: $note" }
        }
    }

    /** What adjudication does to one unconfirmed row; [note] is its log line's tail when it applies. */
    private sealed interface Verdict {
        val note: String

        /** A creation was submitted: settle against the marker the row already holds ([settleAgainstMarker]). */
        class Settle(override val note: String) : Verdict

        /** Nothing was created: clear the marker, so the row is imported again. */
        class Clear(override val note: String) : Verdict

        /** No evidence either way: change nothing, and ask again in the next process. */
        class Leave(override val note: String) : Verdict
    }

    /**
     * The verdict for [row], given what the library answered. **Called under [mutex]** — the import claim and the
     * staged-resource rows it reads are governed by that lock.
     */
    private suspend fun verdictFor(row: UnconfirmedImport, presence: AssetPresence): Verdict = when (presence) {
        // The asset is really there. Settle the row against the marker it already holds — never against a fresh
        // one, which is what overwrote the first copy's handle and orphaned it.
        AssetPresence.PRESENT -> Verdict.Settle("asset ${row.createdLocalId} exists — settled, not re-imported")
        AssetPresence.ABSENT -> absentVerdict(row)
        // Not answerable from what this grant can see: a miss here is not absence, and treating it as absence is
        // how a live marker gets cleared.
        AssetPresence.UNKNOWN -> Verdict.Leave("presence unknown — left unconfirmed, retried later")
    }

    /**
     * "Absent" is honest and WRONG to act on while an import for this ref is running here: the library answers about
     * COMMITTED state, so it cannot see an asset whose change block has not committed — and clearing a live marker
     * drops it from the suppression set, so the device re-uploads a photo it downloaded (Bugsink SNAPSYNC-9). The gate
     * is the claimed/not-claimed FACT, never an elapsed-time estimate of it (the process is suspended for arbitrary
     * spans between a change block and its completion), and it is read UNDER the lock: decision records
     * `changes/archive/2026-08-09-gate-absence-on-unreported-imports` and
     * `changes/archive/2026-08-10-take-imports-off-the-download-lock` (D14).
     */
    private suspend fun absentVerdict(row: UnconfirmedImport): Verdict {
        if (row.ref in importing) return Verdict.Leave("absent, but its import is in flight — left unconfirmed")
        // THE SECOND ORACLE, and the one the library cannot be: did the photo library already TAKE this row's bytes?
        // It takes a resource's file when it ingests it, and it ingests only as part of creating an asset — so their
        // absence is positive evidence that a creation was submitted, available at exactly the moment `absent` cannot
        // be trusted. Nothing else removes them: `releaseStagedBytes` runs only past a confirming write,
        // `pruneNonTerminal` never drops a marker-carrying row, and the OS does not reclaim the App-Group staging
        // directory. Measured: `changes/archive/2026-08-29-settle-imports-on-consumed-bytes/PROBE-FINDINGS.md`.
        val stagedPaths = store.stagedResources(row.ref).map { it.stagedPath }
        return when {
            // A row carrying a marker and recording no staged resource has nothing to reason from — so it is
            // treated exactly as `unknown`.
            stagedPaths.isEmpty() ->
                Verdict.Leave("absent, but no staged resources to reason from — left unconfirmed")
            // A creation WAS submitted: the evidence differs from `present`, the conclusion does not. Clearing here
            // would re-upload a downloaded photo, and the re-import it enables cannot succeed anyway: the bytes it
            // would read are the ones the library just took (measured, `3302`).
            !stagedBytes.allPresent(stagedPaths) -> Verdict.Settle(
                "absent, but its staged bytes were consumed — commit not yet visible, settled against marker " +
                    row.createdLocalId,
            )
            // Nothing was created after all. Clear the marker: an import that fails before reaching the change block
            // would otherwise leave it in place and skip the row forever.
            else -> Verdict.Clear("asset ${row.createdLocalId} is gone — marker cleared, will re-import")
        }
    }

    /**
     * Apply [verdict] to [row] through a write **guarded** on the marker it was computed for — and, for a clear, on
     * the row still being non-terminal: the completion settles rows from the platform's queue holding no lock, and an
     * unguarded write to a row that moved on would strip or overwrite a live suppression handle. Answers the log
     * line's tail. Callers hold [mutex].
     */
    private suspend fun apply(row: UnconfirmedImport, verdict: Verdict): String {
        val applied = when (verdict) {
            is Verdict.Settle -> settleAgainstMarker(row)
            is Verdict.Clear -> store.clearCreatedLocalId(row.ref, row.createdLocalId)
            is Verdict.Leave -> true
        }
        return if (applied) verdict.note else "verdict went stale — row already settled, discarded"
    }

    /**
     * One claimed ref's import work, decided under [mutex] so the platform call needs nothing from the
     * store while it runs.
     */
    private class ClaimedImport(
        val ref: AssetRef,
        val creationDate: String,
        val resources: List<StagedResource>,
    )

    /**
     * Claim ONE ref under the lock, import it outside the lock, repeat. The only shape any trigger needs.
     *
     * **One at a time, deliberately.** Claiming the whole importable batch up front makes a single
     * non-reporting import strand every other ref in that batch: they stay claimed, so no later pass
     * selects them, and recovery moves from "the next wake" to "the next process launch". Claiming per ref
     * keeps the blast radius at one photo.
     *
     * A stalled import ends THIS trigger's drain by blocking on it, and nothing else: every other trigger
     * skips the claimed ref and imports the rest. That is what replaces the deleted deadline's
     * stop-the-drain rule, which existed only to avoid abandoning one transaction per remaining asset —
     * and nothing is abandoned any more.
     */
    private suspend fun drainImportable(
        stopRequested: () -> Boolean = { false },
        awaitImport: suspend (import: suspend () -> Unit) -> Unit = { it() },
    ) {
        // Attempted-in-this-pass, so a ref is offered at most ONCE per drain. Without it this loop
        // live-locks: a `Failed` import leaves its row importable **and** releases its claim, so the next
        // iteration selects the same ref and fails again, forever — spinning on any permanently bad
        // resource (an unmapped type, a corrupt staged file). The old batch form could not reach this,
        // because it iterated a fixed list; the per-ref form has to say so explicitly.
        val attempted = mutableSetOf<AssetRef>()
        if (!readyToImport()) {
            log.i { "import drain skipped — no usable photo grant yet; what is staged stays staged" }
            return
        }
        while (true) {
            // Between two imports, never inside one: the operating system's time is up, so nothing new starts.
            if (stopRequested()) {
                log.i { "import drain stopped — the operating system's time is up; the rest stays staged" }
                return
            }
            val claimed = mutex.withLock { claimNextImportableLocked(attempted) } ?: return
            attempted += claimed.ref
            awaitImport { importOne(claimed) }
        }
    }

    /**
     * Phase 1 of the drain, **under [mutex]**: choose ONE importable ref, take it out of circulation, and
     * read everything the platform call will need.
     *
     * The claim is what replaces holding the lock across the platform call. Reading the staged resources
     * here too is deliberate: it leaves the library call as the ONLY thing outside the lock, so there is no
     * second reason a coroutine might leave this region and no second cause to reason about when one does.
     * Returns `null` when nothing is left, which is what ends the drain.
     */
    private suspend fun claimNextImportableLocked(attempted: Set<AssetRef>): ClaimedImport? {
        val next = store.importableAssets()
            .firstOrNull { it.ref !in attempted && it.ref !in importing } ?: return null
        importing += next.ref
        // Relative to the shared area, as the store holds them: the importer locates them for the library.
        val resources = store.stagedResources(next.ref)
        return ClaimedImport(next.ref, next.creationDate, resources)
    }

    /**
     * Phase 2 of the drain, **outside [mutex]**: the photo-library call and the writes that record it.
     *
     * Traced with [invocation] because nothing bounds this call any more (capability `privacy-security`):
     * an import that entered and never exited is visible only as an entry line with no matching exit, and
     * that line is the sole evidence a library stalled.
     *
     * The claim is released when the library REPORTS — i.e. when [GalleryImporter.import] **returns**,
     * whether imported or an observed failure. Nothing else releases it, and that is deliberate:
     *
     *  - **returns** → the library reported → release.
     *  - **throws or is cancelled** → this coroutine is gone, and nothing tells us whether a transaction
     *    was submitted first → KEEP it claimed, and let the throw propagate. Treating "this coroutine is
     *    gone" as "this transaction is gone" is the inference this capability refuses, and the port's
     *    contract does NOT promise that a throw means no change block was submitted — an importer that
     *    raised after `performChanges` would, on the other reading, have its live marker cleared by the
     *    next adjudication, which is `SNAPSYNC-9` through the guard built to prevent it.
     *
     * There is deliberately **no catch-all** here. Swallowing a `Throwable` into a warning also swallows
     * programming errors — measured: it silently absorbed the test fake's live-lock assertion, so removing
     * the drain's attempted-set hung the suite instead of failing it, and the mutation could not be
     * revert-proofed at all.
     */
    private suspend fun importOne(claimed: ClaimedImport) =
        log.invocation(entryContext, "import", params = "asset=${claimed.ref.sourceAssetId}") {
            val ref = claimed.ref
            // No try/catch: a throw leaves the ref claimed and propagates. See the KDoc above.
            val album = eventAlbum()
            val result = importer.import(ImportRequest(ref, claimed.resources, claimed.creationDate, album))
            mutex.withLock {
                when (result) {
                    is ImportResult.Imported -> {
                        // Unguarded, and safe because this ref is STILL claimed: no second import can have
                        // run for it, so the row cannot have moved on. It is also the safety net for an
                        // importer that skipped its in-block marker write — a guarded write would match
                        // nothing there, leaving the row importable, and every later pass would create
                        // another asset while reporting success.
                        store.markImported(ref, result.createdLocalId)
                        log.i { "imported foreign asset ${ref.sourceAssetId} as ${result.createdLocalId}" }
                        // AFTER the confirming write, never before: a crash between them must leave extra
                        // bytes, not a row pointing at bytes that are gone (capability `receiving-photos`).
                        releaseStagedBytes(ref)
                        album?.let { onImportedIntoAlbum(it) }
                    }
                    is ImportResult.Failed ->
                        // Two failures, two outcomes, and the library's own behaviour is what tells them
                        // apart (capability `receiving-photos`). It takes a resource's file at INGEST, before
                        // validating the content — so a content rejection leaves no bytes, and a staged
                        // resource is never re-downloaded. Retrying that imports from files that no longer
                        // exist, on every trigger, for the life of the install.
                        if (result.consumedResources) {
                            if (store.settleUnimportable(ref)) {
                                // ERROR, not WARN, and that severity is the decision (capability
                                // `privacy-security`): this photo will never arrive, and it is otherwise
                                // absent from the member's library with no error surface and absent from the
                                // log except as a repetition of the failure that caused it. "Failed, will
                                // retry" and "will never arrive" are different answers.
                                log.e {
                                    "import settled UNIMPORTABLE for ${ref.sourceAssetId}: ${result.message} " +
                                        "— the library consumed its staged bytes and created no asset, so " +
                                        "nothing can retry it and this photo will not arrive"
                                }
                            } else {
                                // The guarded write matched nothing: the row moved on while this import ran.
                                // Settling it would overwrite whatever it moved on to.
                                log.i { "import for ${ref.sourceAssetId} failed, but its row already settled — discarded" }
                            }
                        } else {
                            log.w { "import deferred for ${ref.sourceAssetId}: ${result.message}" } // retried later
                        }
                }
                // AFTER the writes above, in the same acquisition. Releasing first would let the row move
                // on between the release and `markImported`, which is exactly what that write's
                // unguardedness relies on not happening.
                importing -= ref
            }
        }

    /**
     * Settle [row] against the marker it **already holds**, never against a fresh one — the single action
     * both evidence-bearing adjudication branches take (capability `receiving-photos`).
     *
     * `present` and `absent-with-consumed-bytes` differ in what proved a creation was submitted, not in
     * what follows from it, so they share this rather than each reimplementing it. Returns whether the
     * guarded write applied; `false` means the row moved on between the lookup and here — the completion
     * callback settles rows from the platform's own queue holding no lock — and the caller must then do
     * nothing further, least of all release bytes belonging to whatever the row moved on to.
     *
     * Releasing the claim is hygiene rather than recovery: the write above just made the row terminal,
     * and a terminal row is excluded from importable work, from adjudication and from the prune alike, so
     * no reader can tell a released claim from a retained one. What it buys is a bounded set.
     *
     * Callers hold [mutex]; every write here is governed by it.
     */
    private suspend fun settleAgainstMarker(row: UnconfirmedImport): Boolean {
        if (!store.confirmCreatedLocalId(row.ref, row.createdLocalId)) return false
        importing -= row.ref
        // Only past the guard: releasing the bytes of a row that moved on would delete the staged files a
        // live import is reading from.
        releaseStagedBytes(row.ref)
        return true
    }

    /**
     * Free one settled asset's staged bytes and drop its resource rows, so the store never records a
     * staged path for a file that no longer exists — which is also what makes [releaseSettledBytes]
     * self-extinguishing. Best-effort: freeing disk is never worth failing an import over.
     */
    private suspend fun releaseStagedBytes(ref: AssetRef) {
        runCatchingCancellable {
            val paths = store.stagedResources(ref).map { it.stagedPath }
            if (paths.isNotEmpty()) stagedBytes.release(paths)
            store.dropResources(ref)
        }.onFailure { log.w(it) { "releasing staged bytes for ${ref.sourceAssetId} failed — retried later" } }
    }

    /**
     * Reclaim the staged bytes of assets whose import is confirmed but whose files are still on disk —
     * everything installs accumulated before bytes were ever released (capability `receiving-photos`).
     *
     * **Self-extinguishing**: releasing also drops the resource rows that made the work findable, so a
     * second run finds nothing. No flag, no migration, no run-once bookkeeping.
     *
     * Driven by the `flow/Foreground` trigger, unconditionally — the backlog belongs to the device, not
     * to a membership, so it is reclaimed while unjoined and under an upload-only one too. That call is
     * what makes it a reclaim rather than a capability; it shipped without one, and every install that
     * predates per-asset release kept its orphaned files. See the trigger's own note for why foreground
     * and not a background task.
     */
    suspend fun releaseSettledBytes() = log.invocation(entryContext, "releaseSettledBytes") {
        val paths = runCatchingCancellable { store.stagedPathsOfImportedAssets() }.getOrDefault(emptyList())
        if (paths.isEmpty()) return@invocation
        log.i { "releasing ${paths.size} staged file(s) of already-imported assets" }
        runCatchingCancellable {
            stagedBytes.release(paths)
            mutex.withLock { store.dropResourcesOfImportedAssets() }
        }.onFailure { log.w(it) { "staged-byte reclaim failed — retried later" } }
    }

    /**
     * Leave/switch: cancel in-flight transfers and drop non-terminal rows (imported rows persist).
     *
     * An import already claimed is NOT cancelled. Its transaction may still commit, and cancelling would
     * re-open the window this capability's claim closes; its row is spared by the prune below, settles when
     * the library reports, and remains as a permanent suppression handle. So a leave does not fully clean —
     * one row can outlive it — and that is correct: the photo IS in the library, and the handle is the only
     * thing keeping it out of the upload universe.
     */
    suspend fun onLeaveOrSwitch() = log.invocation(entryContext, "onLeaveOrSwitch") {
        mutex.withLock {
            jobs.cancelAll()
            releaseAndPruneLocked()
        }
    }

    /**
     * The download half of a durable-state reset (capability `sync-status`, `POST /device/reset`).
     *
     * It lives HERE, not in the reset feature, because it must hold [mutex]: a ref is claimed under that
     * lock, so anything deciding what a prune may delete has to exclude new claims, not merely read a
     * snapshot of them. Reading the claimed set as a value and pruning later leaves a window in which a ref
     * is claimed in between — and that row, whose change block has not run, is then deleted, so its marker
     * write lands on nothing and the created asset is uploaded back into the event. The reset suspends
     * between its steps, so that window is wide.
     */
    suspend fun onDurableStateReset() = log.invocation(entryContext, "onDurableStateReset") {
        mutex.withLock { releaseAndPruneLocked() }
    }

    /**
     * Free the staged bytes of prunable rows and drop those rows — **under [mutex]**, and against ONE view
     * of what is claimed, so the two halves cannot disagree.
     *
     * The prune returns the paths it stranded rather than the caller reading them first: two reads at two
     * instants, over a store the platform's change and completion blocks mutate without any lock, let a
     * marker cleared in the gap turn a protected row into a deleted one whose files are then orphaned with
     * no row referencing them — unreclaimably, and across launches. Releasing bytes for a row the prune
     * then spares is worse still: the row stays, its resources still record a `stagedPath`, the files are
     * gone, and a resource recorded as staged is never re-downloaded, so that photo is permanently
     * unimportable.
     */
    private suspend fun releaseAndPruneLocked() {
        val stranded = store.pruneNonTerminal(protecting = importing.toSet())
        runCatchingCancellable { stagedBytes.release(stranded) }
            .onFailure { log.w(it) { "releasing pruned staged bytes failed — files left behind" } }
    }
}
