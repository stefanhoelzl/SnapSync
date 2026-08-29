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
import app.snapsync.ports.StagedResource
import app.snapsync.ports.UnconfirmedImport
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
     *    write land on nothing (capability `download-store`).
     *
     * **The reader that is NOT here is the point.** A superseded design
     * (`parked/settle-imports-by-transaction`) used one registry for these AND for wake quiescence — "is
     * this wake's work finished?" — and that one broke: membership begins at the CLAIM, so work that had
     * been launched but not yet claimed was invisible to it, and a wake could report itself finished with
     * imports pending. That question is not about any ref, so no superset argument covers it. It is
     * answered elsewhere and already: every trigger AWAITS its drain, and `OsReceipt` bounds the handler
     * (capability `ios-app-shell`). This field is private so that answer cannot be sought here.
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
        // A failed union fetch costs us this wake's DISCOVERY, not this wake's WORK. The import drain
        // below reads only the store and the staged bytes already on disk — no network — so returning
        // here would strand assets that are ready to import for no reason. That was harmless while a
        // failing fetch took minutes (the wake was over regardless); once the client carries an explicit
        // request timeout (capability `ios-app-shell`) a failure arrives in seconds with most of the
        // wake budget unspent, and skipping the drain wastes it.
        val assets = union.union(eventId).getOrElse {
            log.w(it) { "union fetch failed — keeping last state, draining staged imports anyway" }
            drainImportable()
            return@invocation
        }
        mutex.withLock {
            var planned = 0
            for (asset in assets) {
                if (asset.deviceId == myDeviceId) continue // own contribution — already in this library
                val ref = AssetRef(asset.deviceId, asset.assetId)
                if (store.isSettled(ref)) continue // imported or unimportable — delete-proof / cross-event dedup
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
        }
        // OUTSIDE the lock: the drain takes it per decision and releases it across each platform call.
        drainImportable()
    }

    /**
     * A resource's bytes finished downloading and were moved to durable staging (called by the
     * background-`URLSession` delegate, possibly while backgrounded / on relaunch). Records it and
     * imports the asset if its set is now complete.
     */
    suspend fun onResourceStaged(ref: AssetRef, resourceKey: String, stagedPath: String) =
        log.invocation(logScope, "onResourceStaged", params = "key=$resourceKey") {
            mutex.withLock { store.markStaged(ref, resourceKey, stagedPath) }
            drainImportable()
        }

    /** Import every asset whose resources are all staged and that is not yet imported. */
    suspend fun importReady() = log.invocation(logScope, "importReady") {
        drainImportable()
    }

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
    suspend fun sweepInterruptedImports() = log.invocation(logScope, "sweepInterruptedImports") {
        adjudicateUnconfirmed()
        drainImportable()
    }

    /**
     * The per-process recovery sweep (capability `photo-download`): settle the rows this process
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
     * store write GUARDED on the marker it was computed for (capability `download-store`). A row can settle
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

        val verdicts = presence.presence(unconfirmed.mapTo(mutableSetOf()) { it.createdLocalId })
        for (row in unconfirmed) {
            when (verdicts[row.createdLocalId] ?: AssetPresence.UNKNOWN) {
                // The asset is really there. Settle the row against the marker it already holds — never
                // against a fresh one, which is what overwrote the first copy's handle and orphaned it.
                AssetPresence.PRESENT -> mutex.withLock {
                    if (!settleAgainstMarker(row)) {
                        log.i { "adjudicated ${row.ref.sourceAssetId}: verdict went stale — row already settled, discarded" }
                        return@withLock
                    }
                    log.i { "adjudicated ${row.ref.sourceAssetId}: asset ${row.createdLocalId} exists — settled, not re-imported" }
                }
                // "Absent" is honest and WRONG to act on while an import for this ref is running here: the
                // library answers about COMMITTED state, so it cannot see an asset whose change block has
                // not committed. Clearing the marker of a live one drops it from the suppression set, so
                // the device re-uploads a photo it downloaded (Bugsink SNAPSYNC-9: 19 such clears, each
                // 9-44 ms after that same asset was created).
                //
                // The gate is the claimed/not-claimed FACT, never an elapsed-time estimate of it: the
                // process is suspended for arbitrary spans between a change block and its completion
                // (measured 116 s and 254 s), so any wall-clock bound expires against transactions that
                // are alive — which is exactly what the import deadline did before it was deleted.
                AssetPresence.ABSENT -> mutex.withLock {
                    // THE GATE IS READ UNDER THE LOCK, and that placement is the whole fix.
                    //
                    // Reading it before the lock reproduced the defect on real hardware. Measured on an
                    // SE2 against the PREVIOUS shape of this guard: an import held the lock for its full
                    // 30 s deadline while three other staged resources each ran adjudication, saw the gate
                    // answer "not held" — the deadline had not fired yet, so nothing was recorded — and
                    // then queued on the mutex. The import timed out, recorded its ref and released; the
                    // first queued adjudication woke with a gate answer that was 30 s stale and cleared
                    // the marker of an asset created 30 s earlier. The photo was re-imported as a second
                    // asset and the first was left unsuppressed. That is SNAPSYNC-9, through the guard
                    // meant to prevent it.
                    //
                    // The store's own marker guard cannot substitute: at that instant the row really IS
                    // still unconfirmed with that marker, so a guarded clear applies. Only reading the
                    // claim here, under the lock that also governs it, sees the truth.
                    if (row.ref in importing) {
                        log.i { "adjudicated ${row.ref.sourceAssetId}: absent, but its import is in flight — left unconfirmed" }
                        return@withLock
                    }
                    // THE SECOND ORACLE, and the one the library cannot be: did the photo library already
                    // TAKE this row's bytes? It takes a resource's file when it ingests it, and it ingests
                    // only as part of creating an asset — so their absence is positive evidence that a
                    // creation was submitted, available at exactly the moment `absent` cannot be trusted.
                    //
                    // Nothing else can have removed them: `releaseStagedBytes` runs only past a confirming
                    // write, `pruneNonTerminal` never drops a marker-carrying row, and the App-Group
                    // staging directory is not a location the OS reclaims.
                    //
                    // Measured (`changes/.../settle-imports-on-consumed-bytes/PROBE-FINDINGS.md`): after a
                    // SIGKILL mid-commit the file is gone at relaunch 6/6, and gone BEFORE the asset is
                    // visible — the exact state this reads. 8 of 9 runs at 25-43 MB reached it.
                    val stagedPaths = store.stagedResources(row.ref).map { it.stagedPath }
                    if (stagedPaths.isEmpty()) {
                        // Evidence neither way. A row carrying a marker and recording no staged resource
                        // has nothing to reason from, and this branch exists to stop reasoning without
                        // evidence — so it is treated exactly as `unknown`.
                        log.i { "adjudicated ${row.ref.sourceAssetId}: absent, but no staged resources to reason from — left unconfirmed" }
                        return@withLock
                    }
                    if (!stagedBytes.allPresent(stagedPaths)) {
                        // A creation WAS submitted. Settle against the marker the row already holds —
                        // identical handling to `present`, because the evidence differs and the conclusion
                        // does not. Clearing here is what re-uploads a downloaded photo into someone
                        // else's event, and the re-import it would enable cannot succeed anyway: the bytes
                        // it would read are the ones the library just took (measured, `3302`).
                        if (!settleAgainstMarker(row)) {
                            log.i { "adjudicated ${row.ref.sourceAssetId}: verdict went stale — row already settled, discarded" }
                            return@withLock
                        }
                        log.i {
                            "adjudicated ${row.ref.sourceAssetId}: absent, but its staged bytes were consumed — " +
                                "commit not yet visible, settled against marker ${row.createdLocalId}"
                        }
                        return@withLock
                    }
                    // Nothing was created after all. Clear the marker FIRST: an import that fails before
                    // reaching the change block would otherwise leave it in place and skip the row forever.
                    //
                    // Guarded on the marker AND on the row still being non-terminal, in the store's own
                    // write. Between the lookup and here the completion can settle this row from the
                    // platform's queue, taking no lock; an unguarded clear would then strip the marker off
                    // a row that is already IMPORTED. That row is terminal, so it is never adjudicated or
                    // re-imported again — the asset stays in the library permanently unsuppressed, and
                    // upload discovery sends the downloaded photo back into the event.
                    if (!store.clearCreatedLocalId(row.ref, row.createdLocalId)) {
                        log.i { "adjudicated ${row.ref.sourceAssetId}: verdict went stale — row already settled, discarded" }
                        return@withLock
                    }
                    log.i { "adjudicated ${row.ref.sourceAssetId}: asset ${row.createdLocalId} is gone — marker cleared, will re-import" }
                }
                // Not answerable from what this grant can see. Change nothing; a miss here is not
                // absence, and treating it as absence is how a live marker gets cleared.
                AssetPresence.UNKNOWN ->
                    log.i { "adjudicated ${row.ref.sourceAssetId}: presence unknown — left unconfirmed, retried later" }
            }
        }
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
    private suspend fun drainImportable() {
        // Attempted-in-this-pass, so a ref is offered at most ONCE per drain. Without it this loop
        // live-locks: a `Failed` import leaves its row importable **and** releases its claim, so the next
        // iteration selects the same ref and fails again, forever — spinning on any permanently bad
        // resource (an unmapped type, a corrupt staged file). The old batch form could not reach this,
        // because it iterated a fixed list; the per-ref form has to say so explicitly.
        val attempted = mutableSetOf<AssetRef>()
        while (true) {
            val claimed = mutex.withLock { claimNextImportableLocked(attempted) } ?: return
            attempted += claimed.ref
            importOne(claimed)
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
        return ClaimedImport(next.ref, next.creationDate, store.stagedResources(next.ref))
    }

    /**
     * Phase 2 of the drain, **outside [mutex]**: the photo-library call and the writes that record it.
     *
     * Traced with [invocation] because nothing bounds this call any more (capability `diagnostic-logging`):
     * an import that entered and never exited is visible only as an entry line with no matching exit, and
     * that line is the sole evidence a library stalled.
     *
     * The claim is released when the library REPORTS — i.e. when [PhotoLibraryImporter.import] **returns**,
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
        log.invocation(logScope, "import", params = "asset=${claimed.ref.sourceAssetId}") {
            val ref = claimed.ref
            // No try/catch: a throw leaves the ref claimed and propagates. See the KDoc above.
            val result = importer.import(ref, claimed.resources, claimed.creationDate)
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
                        // bytes, not a row pointing at bytes that are gone (capability `download-store`).
                        releaseStagedBytes(ref)
                    }
                    is ImportResult.Failed ->
                        // Two failures, two outcomes, and the library's own behaviour is what tells them
                        // apart (capability `photo-download`). It takes a resource's file at INGEST, before
                        // validating the content — so a content rejection leaves no bytes, and a staged
                        // resource is never re-downloaded. Retrying that imports from files that no longer
                        // exist, on every trigger, for the life of the install.
                        if (result.consumedResources) {
                            if (store.settleUnimportable(ref)) {
                                // ERROR, not WARN, and that severity is the decision (capability
                                // `crash-reporting`): this photo will never arrive, and it is otherwise
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
     * Free one settled asset's staged bytes and drop its resource rows, so the store never records a
     * staged path for a file that no longer exists — which is also what makes [releaseSettledBytes]
     * self-extinguishing. Best-effort: freeing disk is never worth failing an import over.
     */
    /**
     * Settle [row] against the marker it **already holds**, never against a fresh one — the single action
     * both evidence-bearing adjudication branches take (capability `photo-download`).
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

    /**
     * Leave/switch: cancel in-flight transfers and drop non-terminal rows (imported rows persist).
     *
     * An import already claimed is NOT cancelled. Its transaction may still commit, and cancelling would
     * re-open the window this capability's claim closes; its row is spared by the prune below, settles when
     * the library reports, and remains as a permanent suppression handle. So a leave does not fully clean —
     * one row can outlive it — and that is correct: the photo IS in the library, and the handle is the only
     * thing keeping it out of the upload universe.
     */
    suspend fun onLeaveOrSwitch() = log.invocation(logScope, "onLeaveOrSwitch") {
        mutex.withLock {
            jobs.cancelAll()
            releaseAndPruneLocked()
        }
    }

    /**
     * The download half of a durable-state reset (capability `ios-app-shell`, `POST /device/reset`).
     *
     * It lives HERE, not in the reset feature, because it must hold [mutex]: a ref is claimed under that
     * lock, so anything deciding what a prune may delete has to exclude new claims, not merely read a
     * snapshot of them. Reading the claimed set as a value and pruning later leaves a window in which a ref
     * is claimed in between — and that row, whose change block has not run, is then deleted, so its marker
     * write lands on nothing and the created asset is uploaded back into the event. The reset suspends
     * between its steps, so that window is wide.
     */
    suspend fun onDurableStateReset() = log.invocation(logScope, "onDurableStateReset") {
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
        runCatching { stagedBytes.release(stranded) }
            .onFailure { log.w(it) { "releasing pruned staged bytes failed — files left behind" } }
    }
}
