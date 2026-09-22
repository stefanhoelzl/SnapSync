package app.snapsync.feature.upload

import app.snapsync.ports.CreateResult
import app.snapsync.ports.CycleResult
import app.snapsync.ports.Discovery
import app.snapsync.ports.PlatformUploadJob
import app.snapsync.ports.BackgroundTransfer
import app.snapsync.ports.UploadDiscovery

import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.isDone
import app.snapsync.feature.upload.LedgerWriter
import app.snapsync.model.Resource
import app.snapsync.model.SyncDecision
import app.snapsync.feature.upload.SyncEngine
import app.snapsync.model.SyncEvent
import app.snapsync.model.UploadError
import app.snapsync.model.UploadRequest
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.EventPhotoSet
import app.snapsync.model.admittedAssetIds
import app.snapsync.model.assetIdFromUploadKey
import co.touchlab.kermit.Logger
import kotlinx.coroutines.withTimeout

/**
 * One background-upload cycle, platform-free: adjudicate the system's returned jobs (completion +
 * retry), then walk the library, record what it found, and create jobs from the ledger — all gated by the
 * [engine]. This is the testable core: it depends only on the [engine], the [ledger] (to reconstruct
 * lifecycle jobs and to delete the rows of departed assets), and the [BackgroundTransfer] and
 * [UploadDiscovery] ports, so a fake platform + a real engine exercise the whole flow on the simulator
 * without touching PhotoKit.
 *
 * Every walk is a **full enumeration**; there is no persisted cursor (capability `ios-photokit-upload`,
 * "In-extension discovery by full enumeration"). Write-after-act: the engine records `REQUESTED` only on
 * [SyncEvent.UploadStarted], reported *after* a job is created/retried — so an in-flight `REQUESTED` always
 * implies a real job, and a cap-truncated cycle leaves its remainder `DISCOVERED` for the next cycle's work
 * read, with no residue store.
 *
 * Deleted-asset pruning keeps the ledger honest about what still exists on device (and stops a row left
 * non-`COMPLETED` by an asset deleted mid-upload from pinning `pending > 0` forever): an authoritative walk
 * deletes the in-window rows of assets it did not return, and a key that no longer resolves loses its row
 * (capability `sync-ledger`, "Deletion is a presence diff over an authoritative walk"). The upload tier's
 * process is the single writer — the extension on iOS ≥26.1, the app on iOS 18–26.0 — so this preserves
 * the invariant. No S3 object is ever deleted.
 */
class UploadCycle(
    // THE ENTRY GATE (capability `upload-lifecycle`): the three-state membership read, in the shared
    // vocabulary. Called once per `run()` — this cycle is long-lived, so a join, leave, or switch takes
    // effect on the next cycle without a relaunch.
    //
    // The DECISION is here rather than in each root because a root reaches it only for the tiers its
    // author enumerated. That is not hypothetical: the OS-invoked tier gated on `cycleGate` and the
    // app-driven tier read a two-state `StateFlow` that cannot express "unreadable" — reporting a failed
    // Keychain read as a leave. A root supplies the reads (its own storage, its own bundle); this decides.
    //
    // Required, with **no default**: a default would have to invent an answer for "what is this device
    // joined to", and every answer is wrong. See [SelectionPolicy] for the same reasoning applied after each
    // shipped bug that a default caused.
    private val readGate: suspend () -> CycleGate,
    // The engine for THIS cycle's config — the edge provider needs the host and the device id, and the
    // host arrives with the gate, not at construction. Called once, after the gate says Run.
    private val engineFor: (UploadConfig) -> SyncEngine,
    private val ledger: LedgerWriter,
    private val platform: BackgroundTransfer,
    // What the cycle reads from the photo library — the full-enumeration walk and the id-scoped key resolve.
    // Not the transport's: both tiers read the library identically, and only the transfer lifecycle differs.
    private val library: UploadDiscovery,
    // There is no re-join reconciliation here. The cycle used to detect a membership change nobody
    // announced (a persisted join marker compared on every run) and re-seed the ledger from the device
    // listing. Every change of membership is now an explicit app action, and the join loads the ledger
    // itself (capability `upload-state-reconciliation`, "A join loads the ledger from the per-device
    // listing"), so this cycle reads the ledger it is given and never fetches the listing.
    // Best-effort hook fired once per fully-drained cycle with that cycle's discovery — the device
    // manifest is built from THIS (no second PhotoKit enumeration). Bounded and `runCatching`ed HERE, so a
    // hung host can never stall a cycle and no root has to remember to bound it (both used to, with the
    // same two constants — one copied from the other, along with a justification that only applied to the
    // tier it was copied FROM).
    //
    // Required, with **no default**: a no-op means this device's photos never enter the event union — they
    // upload, and nobody can see them. That is the invisible failure, and `{}` states it silently.
    // Answers whether it PUBLISHED — `false` when the projection was unchanged since the last successful
    // write, so nothing was PUT. That answer is the notify's whole trigger (capability
    // `upload-completion-notify`), which is why it is a Boolean rather than Unit: "the union now lists
    // something new" is a fact only the producer's skip-if-unchanged record knows.
    private val onDiscovery: suspend (eventId: String, policy: SelectionPolicy, manifestVersion: Long) -> Boolean,
    // The echo-suppression and denylisted-album readers used to be injected HERE, so the cycle could
    // complete a config-derived policy. They moved to the one derivation in the shared composition
    // (capability `photo-selection-policy`), which the membership's policy supplier closes over — so this
    // cycle no longer reads either port, and cannot get the completion wrong or skip it.
    //
    // What made them required with **no default** still holds at their new home, and for the same reason:
    // `{ emptySet() }` re-uploads every downloaded foreign photo back into the event, or uploads the
    // member's WhatsApp album into a stranger's. A tier without an album source states that at its call
    // site, where a reviewer can see it.
    // Event-album placement (capability `event-album`): fired with the `assetId`s (normalized) this cycle is
    // about to enqueue for the FIRST time, so the running process adds those own photos to the event album
    // before their upload jobs exist. Runs in whichever process runs the cycle (extension on ≥26.1, app on
    // 18–26.0). Best-effort — invoked under `runCatching`. Fired only when the membership opted in
    // (`saveToAlbum`), which arrives with the gate, so the opt-in check is no longer each root's to remember.
    private val placeInAlbum: suspend (eventId: String, assetIds: Set<String>) -> Unit,
    private val log: Logger = Logger.withTag("UploadCycle"),
    // The best-effort hooks' budgets. Defaulted, because unlike the ports above there IS a safe value: a
    // hook that overruns is retried next cycle, and both tiers want the same protection from a hung host.
    // The extension's is a hard constraint (a ~3-minute OS runtime cap; a `runBlocking` network call that
    // overruns gets the worker force-killed with error 50001); the app tier's is prudence. Same number,
    // different reasons — and now stated once instead of copied.
    private val deviceManifestTimeoutMs: Long = 12_000L,
) {
    /**
     * One cycle, in four stages: **settle** establishes what is true, **decide** reads, **update**
     * writes what is ours, **publish** writes what the event can see.
     *
     * `publish()` is the ONLY producer of a [CycleResult], so no path can return without passing through
     * the publication decision. That is not tidiness: five publications used to be reachable only by
     * falling through to the end of this function, so either early return withheld all five — and on a
     * device whose outstanding work exceeds the platform's job limit, one of those two returns is taken on
     * every cycle, forever, with no error and no log line (capability `upload-lifecycle`).
     */
    suspend fun run(): CycleResult {
        val settled = settle()
        val decided = settled.decide()
        val outcome = decided.update()
        return outcome.publish()
    }

    // --- stage 1: settle -------------------------------------------------------------------------

    /**
     * Establish what is true before anything is decided: the entry gate, the membership's one policy
     * derivation, and the outcomes the platform is holding.
     *
     * It is not a read stage: settling the platform's returned jobs writes the ledger.
     */
    private suspend fun settle(): Settled {
        // THE ENTRY GATE (capability `upload-lifecycle`) — before the direction gate, the walk,
        // and every hook. Five outcomes, and the difference between two of them is the difference between
        // a settled join and a false leave.
        val gate = readGate()
        val (config, membership) = when (gate) {
            is CycleGate.Skip -> {
                // Unreadable != left. Touch NOTHING: no ledger write, no manifest, no jobs. A
                // clean completion; the next cycle — or the next unlock — retries. `detail` is the root's
                // forensics, logged verbatim: this line is the only way an unreadable membership is visible
                // on a device.
                log.w {
                    "skipping cycle — ${gate.detail.ifEmpty { "a required read failed" }}. NOT treating " +
                        "this as a leave; nothing minted, nothing written."
                }
                return Settled.Short(CycleOutcome.Unreadable)
            }
            CycleGate.NotJoined -> {
                // Definitively not joined: no item, an item that cannot decode, a missing baked host, or a
                // leave. Reaching here means the config really IS absent, never merely unread — and it is
                // still not this cycle's to clear anything: the leave that got us here cleared the ledger
                // itself (capability `leave-event`).
                log.i { "skipping cycle — no joined event / host" }
                return Settled.Short(CycleOutcome.NotJoined)
            }
            is CycleGate.Withheld -> {
                // This process may not create now (the extension without a full grant; the app without usable
                // access, or switched off by the rig). Still owed: recording and acknowledging what the platform
                // presented (50008 otherwise). Not owed, and not safe: reading the library or creating work.
                acknowledgePresented(engineFor(gate.config))
                log.i {
                    "cycle withheld — this process may not create now; presented jobs acknowledged, " +
                        "nothing created, nothing published"
                }
                return Settled.Short(CycleOutcome.Withheld)
            }
            is CycleGate.Run -> gate.config to gate.membership
        }

        // ONE derivation, invoked once per cycle (capability `photo-selection-policy`). The membership
        // carries a supplier rather than a built policy because the derivation reads two ports, and the
        // entry-gate translation that produced the membership must stay port-pure (capability
        // `upload-lifecycle`). The supplier is closed over in the shared composition, where the config and
        // both readers are in scope; a non-contributing membership still invokes neither reader.
        val policy = membership.policy()
        val eventId = config.eventId
        val engine = engineFor(config)

        // THE DIRECTION GATE (capability `upload-lifecycle`) — ahead of the walk and job creation: a
        // non-contributor must not enumerate its library to discover it contributes nothing (the walk costs
        // ~110 ms of PhotoKit XPC per asset).
        //
        // It withholds NEW WORK, not settlement, and not the record of what is already uploaded. A declined
        // cycle still settles with the platform and still publishes its (empty) manifest — neither of which
        // creates upload work. Nor does it place anything in the event album: placement rides job creation,
        // which a non-contributor does not reach.
        if (!policy.contributes) {
            recreateRetrySpent(engine)
            log.i { "cycle skipped — this membership contributes nothing (direction excludes upload)" }
            return Settled.Short(CycleOutcome.Declined(eventId, policy, membership.manifestVersion))
        }

        // Phase 1 — first failures: re-point the system's single retry at a rebuilt edge URL
        // (stable, no expiry — the provider re-derives the identical destination locally). Below the
        // direction gate, because it creates jobs.
        for (job in platform.fetchRetryJobs()) {
            // A job whose row the walk removed is not retried: the photo left the library or the selection
            // (capability `upload-lifecycle`). A transport hands over only jobs whose row exists and answers
            // the rest itself, so skipping one here leaves nothing un-acknowledged.
            if (isGone(job)) continue
            val retry = adjudicateFailure(engine, job) ?: continue
            platform.retryJob(job, retry.request)
            engine.handle(SyncEvent.UploadStarted(retry.request))
        }

        // Phase 2 — the outcomes the platform is holding.
        val capHit = recreateRetrySpent(engine)

        // A re-created retry may have hit the platform's job limit. That is carried as a FACT rather than
        // acted on here: the cycle walks anyway, because the walk is what produces the accounting a
        // backlogged device is otherwise invisible in, and because an authoritative walk is what retracts a
        // departed photo. What the cap costs is only that this cycle cannot enqueue more — and the
        // work it could not re-create rests `DISCOVERED`, which the ledger's work read returns next cycle
        // without needing a walk to re-derive it.
        return Settled.Proceeding(
            Ready(eventId, policy, membership.manifestVersion, engine, membership.saveToAlbum, capHit),
        )
    }

    // --- stage 2: decide -------------------------------------------------------------------------

    /**
     * Read the library and decide, writing nothing. A cycle killed inside this stage has changed no
     * durable state.
     *
     * **What licenses this split from [update]:** a decision taken here is still valid there, because
     * `LedgerWriter` is the ledger's only writer, this cycle is its only entry, and the pump is
     * single-flight — and the platform's own delegate reaches storage only through the guarded
     * `markTerminal`, never through a read-then-write. If either property stops holding, deciding here and
     * acting there becomes a duplicate-upload path, and nothing in the compiler will say so.
     */
    private suspend fun Settled.decide(): Decided = when (this) {
        is Settled.Short -> Decided.Short(outcome)
        is Settled.Proceeding -> {
            // Phase 3 — walk the library: a full enumeration, every cycle. The capture range came in with the
            // contribution and is passed down, so the walk is scoped at the platform fetch rather than walked
            // whole and filtered afterwards (capability `photo-selection-policy`).
            val discovery = library.discover(ready.policy)
            log.i { "discovered ${discovery.candidates.size} candidate asset(s)" }

            // THE ADMISSION (capability `photo-selection-policy`): one policy, applied once, deciding the
            // whole admitted set — the capture-date RANGE (both bounds), the origin exclusions, the echo
            // suppression, and the album denylist together. Every consumer of this cycle reads the set
            // below; none re-states a rule, which is what makes the drift that produced the ceiling bug
            // unrepresentable (see `SelectionPolicy`).
            //
            // It stays **authoritative** even though the platform walk narrows its own fetch by some of
            // the same rules: the walk may return a superset (its predicate is deliberately widened, and a
            // selection snapshot takes no predicate at all), and this is what makes that optimization
            // unable to change the admitted set. `resources()` pays the per-asset round-trip ONLY for the
            // assets it kept.
            val admitted = EventPhotoSet(ready.policy) { discovery.candidates }.assets()

            // …and of those, only for the assets the ledger does not fully know (capability `sync-ledger`, "A
            // walk re-reads only the assets the ledger does not fully know"). Every walk is a full enumeration,
            // so without this each cycle would repeat one synchronous platform round-trip per photo already
            // recorded — the member's whole in-window library, every cycle. An uploaded resource is immutable
            // and the ledger keeps no content version, so re-reading a recorded asset could only answer
            // "already uploaded"; a row that still needs a job is found by the ledger's work read, not by the
            // walk. An asset with a BARE row is read, because only the walk can fill its detail — which is
            // also what completes a join-time load that listed only some of an asset's roles.
            val rows = ledger.manifestRows()
            val byAsset = rows.groupBy { it.assetId }
            val fullyKnown = byAsset.filterValues { group -> group.none { it.needsManifestDetail } }.keys
            val toRead = admitted.filter { it.facts.assetId !in fullyKnown }
            // The admitted assets whose rows this walk is about to date — the join-time load's bare rows.
            // They are never enqueued (their bytes are stored), so this walk is the only moment that places
            // them in the event album (capability `event-album`).
            val healing = toRead.mapTo(mutableSetOf()) { it.facts.assetId }.filterTo(mutableSetOf()) { id ->
                byAsset[id]?.any { it.needsManifestDetail } == true
            }
            val liveResources = toRead.flatMap { it.resources() }
                .also {
                    log.i {
                        "selection policy admitted ${admitted.size} of ${discovery.candidates.size} " +
                            "candidate(s); ${admitted.size - toRead.size} fully known, ${toRead.size} read " +
                            "→ ${it.size} resource(s)"
                    }
                }
            val presentAssetIds = discovery.candidates.mapTo(mutableSetOf()) { it.facts.assetId }
            Decided.Planned(
                ready,
                CyclePlan(
                    liveResources,
                    skipped = admitted.size - toRead.size,
                    healing = healing,
                    departedKeys = if (discovery.fullEnumeration) {
                        departedKeys(rows, presentAssetIds, ready.policy)
                    } else {
                        // A library the platform could not read is no evidence that anything left it
                        // (capability `sync-ledger`). A read selection snapshot IS authoritative — under a
                        // partial grant the selection is the gallery — and an unread one never gets here.
                        emptyList()
                    },
                ),
            )
        }
    }

    /**
     * The rows an **authoritative** walk shows are gone (capability `sync-ledger`, "Deletion is a presence
     * diff over an authoritative walk"): every row whose asset is inside the policy's window and that the walk
     * did not return, whatever its state.
     *
     * **Presence is the walk's whole candidate set**, never the admitted set. The retired retain-live
     * reconcile was fed the admitted set, so raising a capture cutoff discarded the `COMPLETED` rows of
     * photos still in the library; being in the library is not a question of scope.
     *
     * **The window is the rows' own admission** ([admittedAssetIds]) — the derivation the manifest and the
     * enqueue already take. The ledger is device-global and the walk is bounded by the capture range, so a
     * row outside that range is no evidence either way; a raised cutoff moves rows OUT of the set this may
     * delete. A bare row's empty date sorts before every cutoff, so it is never judged here. Deciding by
     * this derivation rather than by comparing dates keeps the capture-date rule in one place.
     *
     * **Whatever the row's state** — an in-flight (`REQUESTED`) row included. The photo left the library or,
     * under a partial grant, the selection, so it leaves the manifest this cycle rather than when its job
     * settles. The job may still land its bytes; its guarded terminal write then matches no row and applies to
     * nothing, and a failure presented for the key is answered and forgotten (see [isGone]). Decision record:
     * `changes/selection-is-the-walk` (D2).
     */
    private suspend fun departedKeys(
        // Every row the ledger holds; after the absence sweep none is excluded.
        rows: List<LedgerEntry>,
        present: Set<String>,
        policy: SelectionPolicy,
    ): List<String> {
        val inWindow = admittedAssetIds(rows, policy)
        return rows
            .filter { it.assetId in inWindow && it.assetId !in present }
            .map { it.key }
    }

    // --- stage 3: update -------------------------------------------------------------------------

    /**
     * Write what is ours: the walk's deletions, the jobs the platform will accept, and the manifest detail
     * of rows the walk can fill. Nothing here is visible to the event.
     */
    private suspend fun Decided.update(): CycleOutcome = when (this) {
        is Decided.Short -> outcome
        is Decided.Planned -> {
            val engine = ready.engine

            // The walk's own deletion (capability `sync-ledger`): the in-window rows of assets an authoritative
            // walk did not return. Before anything is recorded, and long before `publish` projects the
            // manifest, so a departed photo is never listed by the cycle that saw it leave.
            if (plan.departedKeys.isNotEmpty()) {
                log.i { "${plan.departedKeys.size} row(s) of assets the walk no longer returns — deleting them" }
                ledger.deleteKeys(plan.departedKeys)
            }

            // Place the assets whose bare rows this walk is about to date, BEFORE their detail is written:
            // once dated a row is no longer bare, so a death between the two would leave the photo unplaced
            // by this path. Placed first, a death before the backfill leaves the row bare and the next walk
            // places it again — a no-op.
            placeHealed(ready, plan.healing)

            // RECORD what the walk found; do not act on it. Every admitted resource the engine judges to
            // be new work gets a `DISCOVERED` row (capability `sync-ledger`), and every already-recorded
            // one still resting bare gets its manifest detail filled.
            //
            // This loop creates no upload job. While it did, it stopped at the platform's job limit — so the
            // resources past that point were recorded nowhere and only a later walk could find them again.
            // Nothing here can stop early, so the walk's facts are captured whole.
            val newWork = mutableListOf<Resource>()
            var alreadyUploaded = 0
            for (resource in plan.liveResources) {
                if (engine.handle(SyncEvent.ResourceChanged(resource)) is SyncDecision.Work) {
                    newWork += resource
                } else {
                    alreadyUploaded++
                    // Enrich a row that predates the manifest detail, or that the join-time load took from a
                    // filename listing (capability `sync-ledger`). The engine writes nothing on an
                    // already-uploaded resource, so without this sweep a seeded row would never learn its
                    // capture date — and the device manifest, projected from the ledger, would silently
                    // drop this member's photos out of the event union after every join.
                    //
                    // A capture date lives only in the photo library and only the walk reads it — which is
                    // why a bare row's asset is always read, never skipped as fully known.
                    //
                    // Idempotent and bare-only: a row already enriched is never rewritten.
                    ledger.backfillManifestDetail(resource)
                }
            }
            // ONE batch write for the walk's new work, so it lands whole or not at all. The walk skips an asset
            // whose rows all exist, so recording a Live Photo's primary and then dying before its paired video
            // would leave the video unrecorded for good; in one transaction there is no such gap.
            ledger.recordDiscovered(newWork)

            val enqueued = enqueue(ready)
            // Truncated by either half: the settle pass could not re-create a retry, or this pass could
            // not create everything the ledger holds. Both mean the same thing to the pump — work remains.
            val truncated = ready.capHit || enqueued.truncated
            val audit = Enumeration(
                seen = plan.liveResources.size,
                skipped = plan.skipped,
                newWork = newWork.size,
                alreadyUploaded = alreadyUploaded,
                deleted = plan.departedKeys.size,
                truncated = truncated,
            )
            if (truncated) CycleOutcome.Truncated(ready, audit) else CycleOutcome.Drained(ready, audit)
        }
    }

    /** What one enqueue pass did, for the outcome that reports it. */
    private class Enqueued(val created: Int, val truncated: Boolean)

    /**
     * Create upload jobs from **the ledger**, not from the walk (capability `sync-ledger`).
     *
     * The rows needing a job are the `DISCOVERED` ones — seen and never attempted, or attempted and returned
     * there by a failure — so the remainder a truncated cycle left behind and
     * a failure that has been sitting for many cycles are picked up by the same read, on the next cycle, with
     * no walk re-deriving them (the walk skips assets the ledger already fully knows).
     *
     * A key that resolves to nothing has left the library since its row was written — or, under a partial
     * grant, left the selection. Its row is deleted, not failed: "the asset is gone" and "the upload did not
     * work" have different causes and different fixes, and the port's partial contract exists so this seam
     * can tell them apart. Deleted **by key**, because this read selects rows by key: an asset's other rows
     * — a Live Photo's already-uploaded primary beside its unresolvable paired video — are not this pass's
     * evidence, and a re-selected or restored photo is simply discovered again.
     *
     * **The rows are admitted first** (capability `photo-selection-policy`). A row is an upstream-filtered
     * structure: it records that the policy admitted its asset *when the row was written*, and a
     * membership's policy changes under it — so a member who raises their cutoff leaves rows behind that
     * the current policy excludes, and uploading them would send the photos they just chose not to share.
     * [admittedAssetIds] is the same derivation the device manifest projects through, so what leaves the
     * device and what it declares cannot disagree.
     *
     * The platform's refusal bounds only what is RESOLVED and CREATED — the ADMITTED rows, never the read.
     * Bounding the read would starve: rows come back in a stable key order, so excluded rows sorting ahead of
     * admitted ones would fill the slice on every cycle and the admitted work further down would never be
     * reached. The platform is the only thing that knows how many transfers it will take — and on the
     * app-driven tier that same limit bounds staged temp-file disk, so asking for more than it will accept
     * costs nothing: the pass stops at the refusal, before the next resolve.
     */
    private suspend fun enqueue(ready: Ready): Enqueued {
        val needJob = ledger.rowsNeedingJob()
        if (needJob.isEmpty()) return Enqueued(created = 0, truncated = false)

        val admitted = admittedAssetIds(needJob, ready.policy)
        // Admit, THEN create: no excluded row costs a platform round-trip.
        val eligible = needJob.filter { it.assetId in admitted }
        if (eligible.isEmpty()) {
            log.i { "${needJob.size} row(s) need a job; the membership's policy admits none of them" }
            return Enqueued(created = 0, truncated = false)
        }

        // Then create until the PLATFORM refuses (capability `ios-url-session-upload`, "The producer tops up from
        // the ledger"), one row at a time: resolve it, create its job, and stop at the first refusal before the
        // next resolve. Both transports refuse honestly, and the refusal is what reports truncation. Measured
        // resolve cost ~4.5 ms per request + ~3.45 ms per photo, and only under a full grant (a partial grant
        // resolves from the snapshot in hand); batching saved a few hundred ms per hundred photos, which did not
        // pay for the chunk. Decision record: `changes/selection-is-the-walk` (D5).
        var created = 0
        for (row in eligible) {
            when (createOne(ready, row)) {
                CreateResult.CREATED -> created++
                // Backpressure, not failure — and the only signal that work remains. The row stays as it was
                // (it still needs a job), so the next cycle finds it in the same read.
                CreateResult.LIMIT_EXCEEDED -> return Enqueued(created, truncated = true)
                CreateResult.FAILED, null -> Unit
            }
        }
        return Enqueued(created, truncated = false)
    }

    /**
     * Resolve [row] and create its job, answering what the platform said — or null when no creation was
     * attempted. A row whose key resolves to nothing has left the library (or the selection) and is deleted by
     * key — see [enqueue].
     */
    private suspend fun createOne(ready: Ready, row: LedgerEntry): CreateResult? {
        val resource = library.resourcesFor(setOf(row.key)).firstOrNull { it.filename == row.key }
        if (resource == null) {
            log.i { "cannot resolve ${row.key} — its asset is gone; deleting that row" }
            ledger.deleteKeys(listOf(row.key))
            return null
        }
        placeFirstEnqueued(ready, row)
        // Through the engine, never around it: it is the one place that decides whether a key uploads, and it
        // mints the request. A row that settled between the read above and here answers `AlreadyUploaded` and
        // is skipped.
        val decision = ready.engine.handle(SyncEvent.ResourceChanged(resource))
        if (decision !is SyncDecision.Work) return null
        return platform.createJob(decision.request, resource).also { result ->
            if (result == CreateResult.CREATED) ready.engine.handle(SyncEvent.UploadStarted(decision.request))
            // FAILED: not created → no UploadStarted; the row still needs a job.
        }
    }

    /**
     * Event-album placement (capability `event-album`) for the photo this pass is about to enqueue: a
     * `DISCOVERED` row whose resource resolved. One best-effort call.
     *
     * **Before** any job is created, deliberately. Creating a job records `REQUESTED` durably, so a process
     * death between that write and a later placement would leave a photo that no pass ever places — nothing
     * reads a `REQUESTED` row for that. Placed first, a creation that fails, hits the platform's limit, or is
     * interrupted leaves the row `DISCOVERED`, and the next cycle places it again for free: adding an asset
     * already in the collection is a no-op (measured, simulator, iOS 26.5 — `changes/fix-lost-upload-acks`).
     *
     * A failure the ledger returned to `DISCOVERED` is placed again when this pass re-creates it — harmless for
     * the same reason, and the album gather already re-places the whole own set with no record, so nothing
     * relies on "placed once". The row has already been admitted by the membership's current policy, so a
     * photo a narrowing change excluded is never placed. Placement never gates job creation.
     *
     * Decision records: `changes/retire-uploaded-state` (D2), `changes/shrink-the-ledger-row` (D5).
     */
    private suspend fun placeFirstEnqueued(ready: Ready, row: LedgerEntry) {
        if (!ready.saveToAlbum || row.state != LedgerState.DISCOVERED) return
        runCatching { placeInAlbum(ready.eventId, setOf(row.assetId)) }
            .onFailure { log.w(it) { "event-album placement failed this cycle" } }
    }

    /**
     * Event-album placement (capability `event-album`) for the assets whose bare rows this walk dates: the
     * own photos the join-time load seeded `COMPLETED` from the device's stored-file listing.
     *
     * The second own-photo placement moment beside [placeFirstEnqueued], and needed for the same reason the
     * gather exists: a photo whose bytes are already stored is never enqueued, so no enqueue places it. The
     * provision's gather cannot place it either — it runs before any walk has dated the row, and the policy
     * excludes a row with no date. On iOS >=26.1 that walk runs in the extension, so the app could not order
     * a gather after it; placing here works in whichever process runs the cycle.
     *
     * One best-effort call, keeping no record: a repeat add is a no-op, so a later gather re-adding these is
     * harmless. The set is admitted by the membership's current policy by construction (it is a subset of
     * the walk's admitted set).
     */
    private suspend fun placeHealed(ready: Ready, assetIds: Set<String>) {
        if (!ready.saveToAlbum || assetIds.isEmpty()) return
        runCatching { placeInAlbum(ready.eventId, assetIds) }
            .onFailure { log.w(it) { "event-album placement of healed rows failed this cycle" } }
    }

    /**
     * Write what the event can see: the enumeration audit line, the device manifest, and the completion
     * notify. Decided over the outcome, exhaustively, so a new outcome cannot inherit a publication
     * policy nobody chose for it (capability `upload-lifecycle`).
     *
     * The ONLY producer of a [CycleResult] — which is what makes "a path that returns without
     * publishing" unwritable rather than merely discouraged.
     */
    private suspend fun CycleOutcome.publish(): CycleResult {
        when (this) {
            // Nothing was established, so nothing may be said. An unreadable membership must touch
            // nothing at all; a definitively-absent one has no event to publish to.
            CycleOutcome.Unreadable, CycleOutcome.NotJoined -> Unit

            // A temporary state of this process, not of the membership: publish NOTHING. The empty manifest a
            // declined direction publishes would remove this device's photos from every member's view the
            // moment a grant flipped.
            CycleOutcome.Withheld -> Unit

            // A membership that shares nothing publishes an EMPTY manifest: that is the honest statement
            // of its state, and leaving a stale one in place would keep advertising photos the member has
            // stopped sharing.
            is CycleOutcome.Declined -> writeDeviceManifest(eventId, policy, manifestVersion)

            // The platform stopped accepting jobs partway through — and this cycle publishes anyway.
            //
            // It used to publish nothing, and that is the failure this change exists to remove: a device
            // with more outstanding work than the platform's job limit takes this branch on every cycle,
            // so its manifest was never refreshed and the photos it had successfully uploaded never
            // entered the event union. Measured in the field: 26 consecutive cycles, 65 uploads completed,
            // zero manifest writes, the union unchanged for two hours while the app was open and working.
            //
            // Nothing about the manifest needs a drained pass. It is a projection of the ledger's settled
            // rows (capability `device-manifest`) — every row this cycle recorded is already durable, and
            // the declined branch below has always written one without any walk at all.
            is CycleOutcome.Truncated -> {
                logEnumeration(audit)
                writeDeviceManifest(ready.eventId, ready.policy, ready.manifestVersion)
            }

            is CycleOutcome.Drained -> {
                logEnumeration(audit)
                writeDeviceManifest(ready.eventId, ready.policy, ready.manifestVersion)
            }
        }
        return result
    }

    /**
     * The per-cycle enumeration summary (capability `diagnostic-logging`): accountable for the whole
     * enumeration without a line per already-uploaded asset (the engine's per-asset skip stays silent).
     *
     * Emitted whether or not every resource it accounts for got a job, and a cycle that stopped creating
     * early says so. That cycle is the one whose accounting is needed most — it is the state in which a
     * backlog accumulates — and it used to emit nothing at all: a field log covering two hours of a
     * device that never drained contains this line zero times, so the remaining backlog was not readable
     * from it at any point.
     */
    private fun logEnumeration(audit: Enumeration) {
        log.i {
            val tail = if (audit.truncated) " — TRUNCATED, the platform took no more jobs this cycle" else ""
            "enumeration: ${audit.seen} seen, ${audit.skipped} asset(s) fully known and not re-read, " +
                "${audit.newWork} new, ${audit.alreadyUploaded} already-uploaded, ${audit.deleted} deleted$tail"
        }
    }

    // --- the stage vocabulary --------------------------------------------------------------------

    /** What the walk saw, in the form the write stage consumes. */
    private class CyclePlan(
        val liveResources: List<Resource>,
        /** Admitted assets the ledger already fully knows, whose resources the walk therefore did not read. */
        val skipped: Int,
        /** Admitted assets with a bare row this walk dates — placed in the event album before it does. */
        val healing: Set<String>,
        /** The rows an authoritative walk shows are gone — empty for a walk that is not authoritative. */
        val departedKeys: List<String>,
    )

    /** The enumeration audit's operands (capability `diagnostic-logging`). */
    private class Enumeration(
        val seen: Int,
        val skipped: Int,
        val newWork: Int,
        val alreadyUploaded: Int,
        val deleted: Int,
        val truncated: Boolean,
    )

    /** A settled cycle that may create work: the facts every later stage needs. */
    private class Ready(
        val eventId: String,
        val policy: SelectionPolicy,
        /** The manifest version the gate read first; the publish carries it (capability `device-manifest`). */
        val manifestVersion: Long,
        val engine: SyncEngine,
        val saveToAlbum: Boolean,
        /**
         * A retry the settle pass could not re-create because the platform's job limit was already
         * reached. It forces `PROCESSING` — there is outstanding work — but it withholds nothing else:
         * the row rests `DISCOVERED` and the ledger's work read returns it next cycle.
         */
        val capHit: Boolean,
    )

    /** [settle]'s result: either a cycle that may proceed, or one whose outcome is already known. */
    private sealed interface Settled {
        class Proceeding(val ready: Ready) : Settled
        class Short(val outcome: CycleOutcome) : Settled
    }

    /** [decide]'s result. `Short` forwards an outcome established before the walk. */
    private sealed interface Decided {
        class Planned(val ready: Ready, val plan: CyclePlan) : Decided
        class Short(val outcome: CycleOutcome) : Decided
    }

    /**
     * What a cycle established, and therefore what it may publish. Exhaustive: a new variant stops
     * [publish] compiling until someone decides what it says.
     */
    private sealed interface CycleOutcome {
        val result: CycleResult

        /** A required input could not be read. Nothing was touched. */
        data object Unreadable : CycleOutcome {
            override val result get() = CycleResult.COMPLETED
        }

        /**
         * Definitively not joined. `SKIPPED`, not `COMPLETED`: with no membership there is nothing to wake for,
         * and the pump re-arms its heartbeat on anything but `SKIPPED` — so an unjoined device whose triggers
         * now reach the app engine would carry a self-re-submitting `BGProcessingTask` for no event. The join
         * is what arms it (capability `upload-lifecycle`, "No membership, no arm").
         */
        data object NotJoined : CycleOutcome {
            override val result get() = CycleResult.SKIPPED
        }

        /** This process may not create now: the presented jobs were acknowledged, nothing was created. */
        data object Withheld : CycleOutcome {
            override val result get() = CycleResult.SKIPPED
        }

        /** This membership's direction excludes upload. */
        class Declined(
            val eventId: String,
            val policy: SelectionPolicy,
            val manifestVersion: Long,
        ) : CycleOutcome {
            override val result get() = CycleResult.SKIPPED
        }

        /**
         * The platform stopped accepting jobs before the ledger's work was exhausted.
         *
         * It carries an audit because a truncated cycle is the one whose accounting is needed most: it
         * is the state in which a backlog is accumulating, and without the line a device log shows the
         * candidates going in and a handful of jobs coming out with nothing stating the difference.
         *
         * Every truncated cycle has walked, so the audit is always present. The alternative — short-
         * circuiting a settle-pass cap hit before discovery, to save a library read it could not act on —
         * was rejected: it is exactly the cycle whose remaining backlog most needs stating, and an
         * authoritative walk is also what retracts a departed photo.
         */
        class Truncated(val ready: Ready, val audit: Enumeration) : CycleOutcome {
            override val result get() = CycleResult.PROCESSING
        }

        /** Every admitted resource was recorded and every job the platform would take was created. */
        class Drained(val ready: Ready, val audit: Enumeration) : CycleOutcome {
            override val result get() = CycleResult.COMPLETED
        }
    }

    /**
     * Report a failure to the engine and return its `Retry` (returns the row to `DISCOVERED`; `REQUESTED`
     * deferred). Takes the engine rather than reading a field: it is built per cycle from that cycle's config.
     */
    private suspend fun adjudicateFailure(engine: SyncEngine, job: PlatformUploadJob): SyncDecision.Retry? {
        if (job.key.isBlank()) return null // unrecoverable key — never record a phantom row
        val failed = reconstruct(job)
        val error = job.error ?: UploadError.Unknown("unspecified")
        return engine.handle(SyncEvent.UploadFailed(failed, error)) as? SyncDecision.Retry
    }

    /**
     * Rebuild the engine's [UploadRequest] for a returned platform job from the job's own facts. The URL and
     * headers are placeholders — completion never reads them, and the retry path re-mints a fresh request via
     * the provider.
     */
    private fun reconstruct(job: PlatformUploadJob): UploadRequest {
        val resource = Resource(
            filename = job.key,
            // Derive the assetId from the key (the shared inverse of `uploadKey`) rather than the ledger
            // entry: the row may have been pruned (a mid-upload deletion, a full-enumeration retain), and
            // `entry?.assetId ?: ""` then wrote a phantom `assetId=""` COMPLETED row. The key is the
            // reliable source — `filename` IS `<assetId>-<role>.<ext>`.
            assetId = assetIdFromUploadKey(job.key),
            contentType = job.contentType,
            metadata = emptyMap(),
            data = job.data ?: Unit, // engine [Resource.data] is non-null; payload unused for completion
        )
        return UploadRequest(url = "", headers = emptyMap(), resource = resource)
    }


    /**
     * Publish the device manifest for [eventId] under [policy], carrying the [manifestVersion] the gate read
     * first (capability `device-manifest`).
     */
    private suspend fun writeDeviceManifest(
        eventId: String,
        policy: SelectionPolicy,
        manifestVersion: Long,
    ): Boolean {
        // Best-effort and bounded here, so a hung host can never stall a cycle and no root has to
        // remember to bound it (both used to, with the same two constants — one copied from the other,
        // along with a justification that only applied to the tier it was copied FROM).
        //
        // The answer is what gates the notify (capability `upload-completion-notify`): `false` covers
        // "the projection was unchanged, so nothing was PUT" and "the write failed or timed out" alike,
        // and both mean the same thing to a recipient — the union does not list anything it did not list
        // before, so waking anyone would be a wasted background launch.
        return runCatching { withTimeout(deviceManifestTimeoutMs) { onDiscovery(eventId, policy, manifestVersion) } }
            .onFailure { log.w(it) { "device.json production failed/timed out this cycle" } }
            .getOrDefault(false)
    }

    /**
     * Phase 2 — terminal jobs. EVERY job MUST be acknowledged (the system errors 50008 —
     * "appex failed to acknowledge jobs for processing state" — for any it presents that we leave
     * un-acknowledged), so all arms acknowledge, and so does a cycle the direction gate declines: the
     * obligation is owed to the OS for jobs it already presented, and it does not depend on whether this
     * membership still contributes.
     *
     * Terminal facts no longer arrive here. The platform records them itself, where the platform tells
     * it, into a row that survives the process ([BackgroundTransfer.drainTerminals]); what comes back is
     * only work the cycle must still do — a failure whose resource is still live, so it can be re-created
     * now instead of waiting for a discovery pass to re-derive it.
     *
     * The engine is still the thing that decides: `UploadFailed` returns the row to `DISCOVERED` and answers a
     * freshly-minted request. That the row is already `DISCOVERED` from the adapter's guarded write is
     * harmless — the record is an idempotent upsert. The record never reaches a settled row either way: the store's record write is
     * guarded on the done states, so the skip below is an early exit that saves the job, not the ledger's
     * only protection.
     *
     * Creates no job for work not already begun, writes no manifest, and enumerates nothing — which is
     * what lets a direction-declined cycle run it. The only jobs it can create
     * are replacements for failures the platform already tried.
     */
    /**
     * The **narrow** settle of a withheld cycle (capability `upload-lifecycle`, "Settling with the platform is
     * owed regardless of the cycle's other outcomes"): record and acknowledge every terminal job the platform
     * presented — the drain does both — and adjudicate the retry-spent failures it hands back, returning their
     * rows to `DISCOVERED`. Unlike [recreateRetrySpent] it creates no job — not even a retry's replacement — because
     * a withheld process may not create (decision record `changes/both-uploaders-active`, D3).
     */
    private suspend fun acknowledgePresented(engine: SyncEngine) {
        for (job in platform.drainTerminals()) {
            if (isGone(job)) continue
            if (ledger.entry(job.key)?.state?.isDone == true) continue
            adjudicateFailure(engine, job)
        }
    }

    /**
     * Whether [job]'s row is gone — deleted by an authoritative walk because its photo left the library or,
     * under a partial grant, the selection, possibly while the job was in flight (capability `upload-lifecycle`,
     * "A presented job whose row is gone is answered and nothing more"). Such a job is answered and nothing
     * more: no engine event, no retry, no re-creation.
     *
     * Checked before the engine, because the engine cannot tell: its failure record is an upsert guarded only
     * against a SETTLED row, so a late failure for a missing one recreated it — bare, with no capture date, so
     * never admitted, never in a walk's window, never deleted, and pending forever. Decision record:
     * `changes/selection-is-the-walk` (D3).
     */
    private suspend fun isGone(job: PlatformUploadJob): Boolean =
        (ledger.entry(job.key) == null).also { gone ->
            if (gone) log.i { "presented job ${job.key} has no row — its photo left; answered, nothing written" }
        }

    private suspend fun recreateRetrySpent(engine: SyncEngine): Boolean {
        var capHit = false
        val returned = platform.drainTerminals()
        for (job in returned) {
            if (isGone(job)) continue
            // At-least-once: the platform can hand back a failure for a key that has since settled (its
            // own guarded write already declined to touch it). Adjudicating anyway would drive the engine
            // to record a failure over a COMPLETED row and re-upload bytes that are stored — the failure
            // this whole change exists to stop, arriving by a different door.
            if (ledger.entry(job.key)?.state?.isDone == true) continue
            val retry = adjudicateFailure(engine, job) ?: continue
            if (job.data == null || capHit) continue
            when (platform.createJob(retry.request, retry.request.resource)) {
                CreateResult.CREATED -> engine.handle(SyncEvent.UploadStarted(retry.request))
                CreateResult.LIMIT_EXCEEDED -> capHit = true // rediscovery retries this key
                CreateResult.FAILED -> Unit // not created → no UploadStarted
            }
        }
        return capHit
    }

}
