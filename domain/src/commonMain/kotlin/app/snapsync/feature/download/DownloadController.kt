package app.snapsync.feature.download

import app.snapsync.ports.EventUnionSource
import app.snapsync.ports.ImportResult
import app.snapsync.ports.PhotoDownloadJobs
import app.snapsync.ports.ImportedAssetPresence
import app.snapsync.ports.PhotoLibraryImporter

import app.snapsync.model.AssetPresence
import app.snapsync.ports.AssetRef
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.PlannedResource
import app.snapsync.ports.LogScope
import app.snapsync.ports.StagedBytes
import app.snapsync.ports.invocation
import co.touchlab.kermit.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The device-side download/import orchestrator (capability `photo-download`). Reads the event-wide
 * union, selects **foreign** assets (`deviceId != myDeviceId`) not already imported, records them in
 * the [store], enqueues their resource downloads, and imports any asset whose resources are all staged.
 * It owns no transport or PhotoKit detail — those are the [jobs] and [importer] seams — so it is
 * exercised in `commonTest` with fakes. Network/union failures keep last-good state (never throw).
 */
class DownloadController(
    private val union: EventUnionSource,
    private val store: DownloadStore,
    private val jobs: PhotoDownloadJobs,
    private val importer: PhotoLibraryImporter,
    // Adjudicates a row whose asset was created but whose import was never confirmed (capability
    // `photo-download`). Required, with no default: a permissive stand-in would answer "absent" for
    // assets that exist, clear their markers, and re-import them — which is the defect this guard is
    // here to prevent, reintroduced by the thing meant to prevent it.
    private val presence: ImportedAssetPresence,
    // The refs whose import outcome the library has not reported (capability `photo-download`). Required,
    // with no default, for the same reason [presence] is: a permissive stand-in would answer "everything
    // has been reported", so every stale ABSENT verdict would be acted on and the defect this gate exists
    // to prevent would be reintroduced by the thing meant to prevent it.
    private val unreported: UnreportedImports,
    // Releases the staged bytes of settled rows (capability `download-store`). Defaulted to a no-op
    // because failing to free disk is harmless, unlike every other port here — and a composition with no
    // staging of its own genuinely has nothing to release.
    private val stagedBytes: StagedBytes = StagedBytes.None,
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
    // shape `UploadArm`'s KDoc blames for starting an upload producer for an event that did not exist. It was
    // unreachable only because every caller happened to pass a config-derived event id — a property of the
    // callers, not of the gate. The default is gone for the same reason the cutoff and the reconcile have
    // none: a permissive default on a safety gate is how a caller ships without one.
    private val downloadEnabled: () -> Boolean?,
    private val log: Logger = Logger.withTag("DownloadController"),
    private val logScope: LogScope = LogScope.NoOp,
) {

    // Serializes all store-mutating flows. Both join (`provisionEvent`) and foreground fire `reconcile`,
    // and downloads complete on the URLSession delegate — without this, two triggers can both find an
    // asset importable before either marks it IMPORTED and import it twice (observed on device).
    private val mutex = Mutex()

    /**
     * Discover + plan + enqueue + import, idempotently. Safe to call on join and on every foreground:
     * already-imported and already-planned assets are no-ops, and only not-yet-staged resources enqueue.
     */
    suspend fun reconcile(eventId: String) = log.invocation(logScope, "reconcile", params = "eventId=$eventId") {
        // `!= true` covers BOTH non-answers: an upload-only membership (`false`) and no membership at all
        // (`null`). Neither enables the arm, and neither is inferred from the other.
        if (downloadEnabled() != true) {
            // Upload-only membership, or none: skip discovery entirely (no union fetch, no enqueue, no import).
            log.i { "reconcile skipped — this membership does not download" }
            return@invocation
        }
        // Before any drain, and before the lock: settle rows whose asset was created but never confirmed.
        adjudicateUnconfirmed()
        // A failed union fetch costs us this wake's DISCOVERY, not this wake's WORK. The import drain
        // below reads only the store and the staged bytes already on disk — no network — so returning
        // here would strand assets that are ready to import for no reason. That was harmless while a
        // failing fetch took minutes (the wake was over regardless); once the client carries an explicit
        // request timeout (capability `ios-app-shell`) a failure arrives in seconds with most of the
        // wake budget unspent, and skipping the drain wastes it.
        val assets = union.union(eventId).getOrElse {
            log.w(it) { "union fetch failed — keeping last state, draining staged imports anyway" }
            mutex.withLock { importReadyLocked() }
            return@invocation
        }
        mutex.withLock {
            var planned = 0
            for (asset in assets) {
                if (asset.deviceId == myDeviceId) continue // own contribution — already in this library
                val ref = AssetRef(asset.deviceId, asset.assetId)
                if (store.isImported(ref)) continue // delete-proof / cross-event dedup
                store.plan(ref, asset.creationDate, asset.resources.map {
                    PlannedResource(it.key, it.url, it.role, it.contentType, it.originalFilename)
                })
                planned++
            }
            log.i { "reconcile: ${assets.size} union asset(s), $planned foreign planned" }
            // Enqueue the not-yet-staged resources to the OS, then mark them in-flight so the status
            // line's download arrow can pulse (superseded once each stages). Idempotent: re-marking an
            // already-enqueued or already-staged resource is harmless (staged rows are excluded).
            val pending = store.pendingDownloads()
            jobs.enqueue(pending)
            pending.forEach { store.markEnqueued(it.ref, it.resource.resourceKey) }
            importReadyLocked()
        }
    }

    /**
     * A resource's bytes finished downloading and were moved to durable staging (called by the
     * background-`URLSession` delegate, possibly while backgrounded / on relaunch). Records it and
     * imports the asset if its set is now complete.
     */
    suspend fun onResourceStaged(ref: AssetRef, resourceKey: String, stagedPath: String) =
        log.invocation(logScope, "onResourceStaged", params = "key=$resourceKey") {
            adjudicateUnconfirmed() // outside the lock, per the guard's contract
            mutex.withLock {
                store.markStaged(ref, resourceKey, stagedPath)
                importReadyLocked()
            }
        }

    /** Import every asset whose resources are all staged and that is not yet imported. */
    suspend fun importReady() = log.invocation(logScope, "importReady") {
        adjudicateUnconfirmed()
        mutex.withLock { importReadyLocked() }
    }

    /**
     * Phase 1 of the guard (capability `photo-download`): settle the rows whose asset was created but
     * whose import was never confirmed — a process death, or a wait abandoned on its deadline.
     *
     * **Runs OUTSIDE [mutex], deliberately.** The presence lookup is a synchronous, thread-blocking
     * platform call that no timeout can abandon (cancellation is cooperative), so holding the lock across
     * it would let a stalled photo library block every reconcile, import, leave and switch behind it —
     * the exact pathology that bounding each import's wait exists to prevent. Off the lock it parks one
     * background thread instead.
     *
     * **Staleness between the phases is NOT harmless**, and both branches re-check for it under the lock
     * before writing. A row can settle between the lookup and the write — the completion callback runs on
     * the platform's queue and takes no lock — and applying either verdict to a row that has moved on
     * overwrites a live suppression handle: the asset stays in the library with nothing recording that it
     * must not be uploaded. (This paragraph used to claim the opposite, on the strength of
     * `markImported` being idempotent. Idempotent it is; harmless it is not, because the identifier it
     * writes may no longer be the row's.)
     *
     * Costs nothing in the ordinary case — no row carries a marker, so this is one store read that
     * returns nothing and no platform call at all.
     */
    private suspend fun adjudicateUnconfirmed() {
        val unconfirmed = store.unconfirmedImports()
        if (unconfirmed.isEmpty()) return

        val verdicts = presence.presence(unconfirmed.mapTo(mutableSetOf()) { it.createdLocalId })
        for (row in unconfirmed) {
            when (verdicts[row.createdLocalId] ?: AssetPresence.UNKNOWN) {
                // The asset is really there. Settle the row against the marker it already holds — never
                // against a fresh one, which is what overwrote the first copy's handle and orphaned it.
                AssetPresence.PRESENT -> mutex.withLock {
                    // Re-checked UNDER the lock: this verdict was computed outside it, and the row may
                    // have settled in between. `markImported` overwrites `createdLocalId`, so applying a
                    // stale PRESENT would replace a live suppression handle with a dead one — the same
                    // harm as a stale ABSENT, by a different route.
                    if (!store.isUnconfirmedWith(row.ref, row.createdLocalId)) {
                        log.i { "adjudicated ${row.ref.sourceAssetId}: verdict went stale — row already settled, discarded" }
                        return@withLock
                    }
                    store.markImported(row.ref, row.createdLocalId)
                    log.i { "adjudicated ${row.ref.sourceAssetId}: asset ${row.createdLocalId} exists — settled, not re-imported" }
                    releaseStagedBytes(row.ref) // settled by adjudication is still settled
                }
                // "Absent" is honest and WRONG to act on while this ref's outcome is unreported: the
                // library answers about COMMITTED state, so it cannot see an asset whose change block has
                // not committed. Clearing the marker of a live one drops it from the suppression set, so
                // the device re-uploads a photo it downloaded (Bugsink SNAPSYNC-9: 19 such clears, each
                // 9-44 ms after that same asset was created).
                //
                // The gate is the reported/unreported FACT, never an elapsed-time estimate of it: the
                // process is suspended for arbitrary spans between a change block and its completion
                // (measured 116 s and 254 s), so any wall-clock bound expires against transactions that
                // are alive — which is exactly what the import deadline did.
                AssetPresence.ABSENT -> mutex.withLock {
                    // THE GATE IS READ UNDER THE LOCK, and that placement is the whole fix.
                    //
                    // Reading it before the lock reproduces the defect on real hardware. Measured on an
                    // SE2: an import held the lock for its full 30 s deadline while three other staged
                    // resources each ran adjudication, saw `holds` answer false — the deadline had not
                    // fired yet, so nothing was recorded — and then queued on the mutex. The import then
                    // timed out, recorded its ref, and released; the first queued adjudication woke with
                    // a gate answer that was 30 s stale and cleared the marker of an asset that had been
                    // created 30 s earlier. The photo was re-imported as a second asset and the first was
                    // left in the library unsuppressed. That is SNAPSYNC-9, through the guard meant to
                    // prevent it.
                    //
                    // The row-staleness re-check below cannot substitute: at that instant the row really
                    // IS still unconfirmed with that marker, so it passes. Only re-reading the gate here
                    // sees the record the timeout just wrote.
                    if (unreported.holds(row.ref)) {
                        log.i { "adjudicated ${row.ref.sourceAssetId}: absent, but its outcome is unreported — left unconfirmed" }
                        return@withLock
                    }
                    // Re-checked under the lock, for the SAME reason the PRESENT branch is — and this is
                    // the branch that does the damage. Both the verdict AND the `holds` gate above were
                    // read outside the lock, and the completion callback runs on the platform's queue
                    // taking no lock at all. So between them the completion can forget this ref and settle
                    // its row: `holds` then answers false, and an unguarded clear strips the marker off a
                    // row that is already IMPORTED. That row is terminal, so it is never adjudicated or
                    // re-imported again — the asset stays in the library permanently unsuppressed, and
                    // upload discovery sends the downloaded photo back into the event. That is SNAPSYNC-9
                    // itself, through a narrower window: the field clears landed 9-44 ms after creation,
                    // so adjudication and the completion genuinely race at this granularity.
                    if (!store.isUnconfirmedWith(row.ref, row.createdLocalId)) {
                        log.i { "adjudicated ${row.ref.sourceAssetId}: verdict went stale — row already settled, discarded" }
                        return@withLock
                    }
                    // Nothing was created after all. Clear the marker FIRST: an import that fails before
                    // reaching the change block would otherwise leave it in place and skip the row forever.
                    store.clearCreatedLocalId(row.ref)
                    log.i { "adjudicated ${row.ref.sourceAssetId}: asset ${row.createdLocalId} is gone — marker cleared, will re-import" }
                }
                // Not answerable from what this grant can see. Change nothing; a miss here is not
                // absence, and treating it as absence is how a live marker gets cleared.
                AssetPresence.UNKNOWN ->
                    log.i { "adjudicated ${row.ref.sourceAssetId}: presence unknown — left unconfirmed, retried later" }
            }
        }
    }

    private suspend fun importReadyLocked() {
        for (importable in store.importableAssets()) {
            val ref = importable.ref
            when (val result = importer.import(ref, store.stagedResources(ref), importable.creationDate)) {
                is ImportResult.Imported -> {
                    // Reported, so absence is trustworthy about this ref again. A no-op in the ordinary
                    // case — nothing recorded it — but not when a PREVIOUS attempt was abandoned and this
                    // one succeeded: the completion's own `forget` covers the row settling off-lane, and
                    // this covers the report we are holding in our hand. Leaving it to the adapter alone
                    // puts the only write on the one path no test can reach.
                    unreported.forget(ref)
                    store.markImported(ref, result.createdLocalId)
                    log.i { "imported foreign asset ${ref.sourceAssetId} as ${result.createdLocalId}" }
                    // AFTER the confirming write, never before: a crash between them must leave extra
                    // bytes, not a row pointing at bytes that are gone (capability `download-store`).
                    releaseStagedBytes(ref)
                }
                is ImportResult.Failed ->
                    log.w { "import deferred for ${ref.sourceAssetId}: ${result.message}" } // retried later
                // A timeout is a statement about the DEVICE, not this photo (capability `photo-download`):
                // the one hang observed in the field was environmental, and the same asset imported in
                // under a second minutes later. Continuing would start an import per remaining asset
                // against a library that is not answering, abandoning a transaction for each — and every
                // abandoned transaction may still commit, which is a duplicate photo. Stop; the assets
                // stay importable and the next wake drains them.
                is ImportResult.TimedOut -> {
                    // We stopped waiting and never learned the outcome, so this ref's transaction may
                    // still commit. Recorded BEFORE returning, because the very next pass may adjudicate
                    // it — in the field the stale clears landed 9-44 ms after the asset was created — and
                    // an "absent" answer about a live transaction is what re-uploads a downloaded photo.
                    unreported.record(ref)
                    log.w { "import timed out for ${ref.sourceAssetId}: ${result.message} — outcome unreported, stopping this wake's drain" }
                    return
                }
            }
        }
    }

    /**
     * Free one settled asset's staged bytes and drop its resource rows, so the store never records a
     * staged path for a file that no longer exists — which is also what makes [releaseSettledBytes]
     * self-extinguishing. Best-effort: freeing disk is never worth failing an import over.
     */
    private suspend fun releaseStagedBytes(ref: AssetRef) {
        runCatching {
            val paths = store.stagedResources(ref).map { it.stagedPath }
            if (paths.isNotEmpty()) stagedBytes.release(paths)
            store.dropResources(ref)
        }.onFailure { log.w(it) { "releasing staged bytes for ${ref.sourceAssetId} failed — retried later" } }
    }

    /**
     * Reclaim the staged bytes of assets whose import is confirmed but whose files are still on disk —
     * everything installs accumulated before bytes were ever released (capability `download-store`).
     *
     * **Self-extinguishing**: releasing also drops the resource rows that made the work findable, so a
     * second run finds nothing. No flag, no migration, no run-once bookkeeping.
     *
     * Driven by the `flow/Foreground` trigger, unconditionally — the backlog belongs to the device, not
     * to a membership, so it is reclaimed while unjoined and under an upload-only one too. That call is
     * what makes it a reclaim rather than a capability; it shipped without one, and every install that
     * predates per-asset release kept its orphaned files. See the trigger's own note for why foreground
     * and not the download backstop.
     */
    suspend fun releaseSettledBytes() = log.invocation(logScope, "releaseSettledBytes") {
        val paths = runCatching { store.stagedPathsOfImportedAssets() }.getOrDefault(emptyList())
        if (paths.isEmpty()) return@invocation
        log.i { "releasing ${paths.size} staged file(s) of already-imported assets" }
        runCatching {
            stagedBytes.release(paths)
            mutex.withLock { store.dropResourcesOfImportedAssets() }
        }.onFailure { log.w(it) { "staged-byte reclaim failed — retried later" } }
    }

    /** Leave/switch: cancel in-flight transfers and drop non-terminal rows (imported rows persist). */
    suspend fun onLeaveOrSwitch() = log.invocation(logScope, "onLeaveOrSwitch") {
        mutex.withLock {
            jobs.cancelAll()
            // BEFORE the prune: afterwards the paths are gone with the rows and the files are stranded
            // with nothing referencing them (capability `download-store`).
            runCatching { stagedBytes.release(store.stagedPathsOfPrunableAssets()) }
                .onFailure { log.w(it) { "releasing prunable staged bytes failed — files left behind" } }
            store.pruneNonTerminal()
        }
    }
}
