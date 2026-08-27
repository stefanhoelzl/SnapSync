package app.snapsync.feature.upload

import app.snapsync.ports.CreateResult
import app.snapsync.ports.CycleResult
import app.snapsync.ports.Discovery
import app.snapsync.ports.DiscoveryStore
import app.snapsync.ports.PlatformUploadJob
import app.snapsync.ports.BackgroundTransfer

import app.snapsync.model.LedgerState
import app.snapsync.model.isDone
import app.snapsync.feature.upload.LedgerWriter
import app.snapsync.model.Resource
import app.snapsync.model.SyncDecision
import app.snapsync.feature.upload.SyncEngine
import app.snapsync.model.SyncEvent
import app.snapsync.model.UploadError
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadRequest
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.EventPhotoSet
import app.snapsync.model.assetIdFromUploadKey
import co.touchlab.kermit.Logger
import kotlinx.coroutines.withTimeout

/**
 * One background-upload cycle, platform-free: adjudicate the system's returned jobs (completion +
 * retry), then discover new/changed resources and create jobs — all gated by the [engine]. This is
 * the testable core: it depends only on the [engine], the [ledger] (to reconstruct lifecycle jobs
 * and to prune rows for deleted assets), the [BackgroundTransfer] port, and the [DiscoveryStore]
 * cursor, so a fake platform + a real engine exercise the whole flow on the simulator without
 * touching PhotoKit.
 *
 * Write-after-act: the engine records `REQUESTED` only on [SyncEvent.UploadStarted], reported *after*
 * a job is created/retried — so an in-flight `REQUESTED` always implies a real job and discovery can
 * safely skip it. The cursor advances only when the cycle fully drains (no `limitExceeded`), so a
 * cap-truncated cycle re-derives next time and the engine's `REQUESTED`-skip prevents duplicates —
 * no residue store.
 *
 * Deleted-asset pruning keeps the ledger honest about what still exists on device (and stops a row
 * left non-`COMPLETED` by an asset deleted mid-upload from pinning `pending > 0` forever): removed
 * assets reported by the change feed are pruned by key prefix each cycle, and a fully-drained full
 * enumeration reconciles the whole ledger against the live key-set. Pruning is the one direct
 * `LedgerWriter` write the cycle makes (everything else flows through the [engine]); the extension
 * is the single writer, so this preserves the invariant. No S3 object is ever deleted.
 */
class UploadCycle(
    // THE ENTRY GATE (capability `upload-lifecycle`): the three-state membership read, in the shared
    // vocabulary. Called once per `run()` — this cycle is long-lived, so a join, leave, or switch takes
    // effect on the next cycle without a relaunch.
    //
    // The DECISION is here rather than in each root because a root reaches it only for the tiers its
    // author enumerated. That is not hypothetical: the OS-invoked tier gated on `cycleGate` and the
    // app-driven tier read a two-state `StateFlow` that cannot express "unreadable" — reporting a failed
    // Keychain read as a leave, and clearing the join marker of a device that never left. A root supplies
    // the reads (its own storage, its own bundle); this decides.
    //
    // Required, with **no default**: a default would have to invent an answer for "what is this device
    // joined to", and every answer is wrong. See [reconcile] and [SelectionPolicy] for the same reasoning
    // applied after each shipped bug that a default caused.
    private val readGate: () -> CycleGate,
    // The engine for THIS cycle's config — the edge provider needs the host and the device id, and the
    // host arrives with the gate, not at construction. Called once, after the gate says Run.
    private val engineFor: (UploadConfig) -> SyncEngine,
    private val ledger: LedgerWriter,
    private val platform: BackgroundTransfer,
    private val store: DiscoveryStore,
    // Re-join reconciliation (capability `event-rejoin-reconciliation`): the marker-gated seed that makes
    // already-stored resources `COMPLETED` before the producer runs, so a re-joined / switched /
    // reinstalled device re-uploads nothing it has already contributed. Returns whether the producer may
    // create jobs this cycle — `false` defers (a failed/timed-out device listing), and this cycle creates
    // nothing and leaves the ledger, cursor, and marker untouched so the next cycle retries.
    //
    // Takes the eventId, mirroring the real reconciler 1:1 — `null` IS the leave side, which clears the
    // `joinedEventId` marker. Both calls are made HERE: the leave-side one used to be written identically
    // in both composition roots and in the harness, three copies of one decision, and the decision is
    // whether an absent config means "this device left".
    //
    // Required, with **no default**: it lives in the CYCLE, not in each tier's composition root, because
    // the cycle is the only thing that runs on EVERY route to a divergent ledger — a fresh join, an event
    // switch, a leave-then-rejoin, and a delete-and-reinstall (which no provisioning path observes at all:
    // a cold relaunch of an already-joined app provisions nothing). Root-wired reconciliation is exactly
    // how the app-driven tier shipped without any, so a defaulted `{ true }` would re-open that hole
    // silently.
    private val reconcile: suspend (eventId: String?) -> Boolean,
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
    private val onDiscovery: suspend (eventId: String, policy: SelectionPolicy) -> Boolean,
    // The echo-suppression and denylisted-album readers used to be injected HERE, so the cycle could
    // complete a config-derived policy. They moved to the one derivation in the shared composition
    // (capability `photo-selection-policy`), which the membership's policy supplier closes over — so this
    // cycle no longer reads either port, and cannot get the completion wrong or skip it.
    //
    // What made them required with **no default** still holds at their new home, and for the same reason:
    // `{ emptySet() }` re-uploads every downloaded foreign photo back into the event, or uploads the
    // member's WhatsApp album into a stranger's. A tier without an album source states that at its call
    // site, where a reviewer can see it.
    // Notify hook (capability `upload-completion-notify`): fired once per FULLY-DRAINED cycle that
    // recorded >= 1 real completion, AFTER `onDiscovery` (the device-manifest PUT) — the only moment the
    // event union reflects the just-completed assets, so recipients woken by the fan-out find them.
    // Bounded and `runCatching`ed here, like `onDiscovery`.
    //
    // Required, with **no default**. This is the weakest of the four — a missing notify degrades (other
    // members reconcile on their next foreground) rather than corrupts. It is required anyway because it is
    // the port the harness silently omitted, which is why `upload-completion-notify` had no integration
    // coverage at all.
    private val onBatchUploaded: suspend (eventId: String) -> Unit,
    // Event-album placement (capability `event-album`): fired with the `assetId`s (normalized) that
    // GENUINELY completed this cycle, so the running process adds those own photos to the event album.
    // Runs in whichever process runs the cycle (extension on ≥26.1, app on 18–26.0). Best-effort —
    // invoked under `runCatching`. Fired only when the membership opted in (`saveToAlbum`), which arrives
    // with the gate, so the opt-in check is no longer each root's to remember.
    private val placeInAlbum: suspend (eventId: String, assetIds: Set<String>) -> Unit,
    private val log: Logger = Logger.withTag("UploadCycle"),
    // The best-effort hooks' budgets. Defaulted, because unlike the ports above there IS a safe value: a
    // hook that overruns is retried next cycle, and both tiers want the same protection from a hung host.
    // The extension's is a hard constraint (a ~3-minute OS runtime cap; a `runBlocking` network call that
    // overruns gets the worker force-killed with error 50001); the app tier's is prudence. Same number,
    // different reasons — and now stated once instead of copied.
    private val deviceManifestTimeoutMs: Long = 12_000L,
    private val notifyTimeoutMs: Long = 8_000L,
    // How many rows one cycle RESOLVES from the ledger. Defaulted, like the budgets above, because there
    // is a safe value: it bounds a platform round-trip, never a durable write. What is CREATED is bounded
    // by the platform itself — which is the only thing that knows how many transfers it will take, and on
    // the app-driven tier the same limit bounds staged temp-file disk. So asking for more than the
    // platform will accept costs a resolve, not a stage. It exists at all because a first walk on a large
    // library records a row per outstanding resource, and an unbounded read would try to resolve them all.
    private val enqueueBatchSize: Int = 16,
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
     * derivation, the re-join seed, and the outcomes the platform is holding.
     *
     * It is not a read stage. The seed fetches over the network and may re-baseline the whole ledger;
     * this is where the cycle finds out — and repairs — what is true.
     */
    private suspend fun settle(): Settled {
        // THE ENTRY GATE (capability `upload-lifecycle`) — before the direction gate, the seed, the walk,
        // and every hook. Three outcomes, and the difference between two of them is the difference between
        // a settled join and a false leave.
        val gate = readGate()
        val (config, membership) = when (gate) {
            is CycleGate.Skip -> {
                // Unreadable != left. Touch NOTHING: no seed, no marker clear, no cursor reset, no jobs. A
                // clean completion; the next cycle — or the next unlock — retries. `detail` is the root's
                // forensics, logged verbatim: this line is the only way an unreadable membership is visible
                // on a device.
                log.w {
                    "skipping cycle — ${gate.detail.ifEmpty { "a required read failed" }}. NOT treating " +
                        "this as a leave; nothing minted, nothing reconciled, marker untouched."
                }
                return Settled.Short(CycleOutcome.Unreadable)
            }
            CycleGate.NotJoined -> {
                // Definitively not joined: no item, an item that cannot decode, a missing baked host, or a
                // leave. This is where a leave clears the `joinedEventId` marker — it keeps the ledger,
                // cursor, and accumulator intact so a later provision of any event dedups against them.
                // Reaching here means the config really IS absent, never merely unread.
                runCatching { reconcile(null) }
                    .onFailure { log.w(it) { "leave-side marker clear failed" } }
                log.i { "skipping cycle — no joined event / host" }
                return Settled.Short(CycleOutcome.NotJoined)
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

        // Phase 0 — re-join reconciliation (capability `event-rejoin-reconciliation`), BEFORE any upload
        // job is created. On a marker mismatch it seeds the ledger from the device's stored-file listing
        // so nothing already contributed re-uploads; on a settled join it is a no-op. A `false` return is
        // a deferral (the listing fetch failed or timed out): create nothing this cycle and report a clean
        // COMPLETED — a no-op, never a failure — so the next cycle retries with the marker still unset. A
        // THROW is treated identically to `false` (never a FAILED cycle), preserving the deferral the
        // roots previously applied with their own `runCatching`.
        //
        // It runs AHEAD of the direction gate. What it establishes — which of this device's resources are
        // already on the backend — is a fact about BYTES, which this system defines as independent of the
        // selection policy (capability `sync-ledger`); gating it on direction would make a
        // policy-independent fact wait on a policy-dependent branch. It is marker-gated and a no-op on a
        // settled join, so the cost is bounded to the first cycle after a join, switch, or reinstall — and
        // running it here means a member who later re-enables their direction re-uploads nothing.
        val seedSucceeded = runCatching { reconcile(eventId) }
            .getOrElse { log.e(it) { "re-join seed failed — deferring uploads this cycle" }; false }

        // THE DIRECTION GATE (capability `upload-lifecycle`) — ahead of the walk and job creation: a
        // non-contributor must not enumerate its library to discover it contributes nothing (the walk costs
        // ~110 ms of PhotoKit XPC per asset). The discovery cursor is left exactly where it was.
        //
        // It withholds NEW WORK, not settlement, and not the record of what is already uploaded. A declined
        // cycle still settles with the platform and still publishes its (empty) manifest — neither of which
        // creates upload work. No promotion, though: that places in the album and gates the notify, which a
        // non-contributor owes nobody. Rows the platform recorded UPLOADED stay that way, and are reconciled
        // from the device's stored-file listing on a re-join.
        if (!policy.contributes) {
            recreateRetrySpent(engine)
            log.i { "cycle skipped — this membership contributes nothing (direction excludes upload)" }
            return Settled.Short(CycleOutcome.Declined(eventId, policy, seedSucceeded))
        }

        // A DEFERRED SEED SETTLES TOO (capability `upload-lifecycle`). The obligation is owed to the
        // platform for jobs it has ALREADY presented, and it depends neither on whether this membership
        // still contributes — which the branch above has honoured since the 50008 measurement — nor on
        // whether the seed succeeded, which this branch used to get wrong. The two sat one below the
        // other stating opposite rules for one obligation, and no spec ever asked for this one:
        // `event-rejoin-reconciliation`'s "defers without settling" is about the ledger SEED, not about
        // the platform's returned jobs.
        if (!seedSucceeded) {
            recreateRetrySpent(engine)
            log.i { "re-join seed deferred this cycle — creating no upload jobs, and writing no manifest" }
            return Settled.Short(CycleOutcome.SeedDeferred)
        }

        // Provenance backfill (spec `sync-ledger`, migration 4.sqm): sweep every pre-provenance row
        // (`eventId = ''` — recorded before the ledger carried the column, or by a staged-revert
        // build) to this cycle's live event id. Seated HERE — after the seed succeeded — because
        // this is the one point that runs on BOTH tiers' cycles (the shared cycle is the single
        // writer's only entry) and never in a reader, and because a settled seed means the
        // membership this cycle records under is the one the marker agrees with (a switch's
        // `resetTo` has already re-baselined, so the sweep can never label another event's rows).
        // Idempotent and cheap: one UPDATE matching nothing on every cycle after the first.
        runCatching { ledger.backfillEventId(eventId) }
            .onFailure { log.w(it) { "eventId backfill failed this cycle — retried next cycle" } }

        // Phase 1 — first failures: re-point the system's single retry at a rebuilt edge URL
        // (stable, no expiry — the provider re-derives the identical destination locally). Below the
        // direction gate, because it creates jobs.
        for (job in platform.fetchRetryJobs()) {
            val retry = adjudicateFailure(engine, job) ?: continue
            platform.retryJob(job, retry.job.request)
            engine.handle(SyncEvent.UploadStarted(retry.job))
        }

        // Phase 2 — the outcomes the platform is holding, in its usual position for a contributing
        // membership: AFTER the seed settled, so the rows it writes are labelled with a membership the
        // marker agrees with (see the backfill note above).
        val capHit = recreateRetrySpent(engine)

        // A re-created retry may have hit the platform's job limit. That is carried as a FACT rather than
        // acted on here: the cycle walks anyway, because the walk is what produces the accounting a
        // backlogged device is otherwise invisible in, and because recording what it finds is what lets
        // the cursor advance. What the cap costs is only that this cycle cannot enqueue more — and the
        // work it could not re-create rests `FAILED`, which the ledger's work read returns next cycle
        // without needing a walk to re-derive it.
        return Settled.Proceeding(Ready(eventId, policy, engine, membership.saveToAlbum, capHit))
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
            // Phase 3 — discover new/changed resources; the engine's in-flight skip filters what is
            // already requested. The cutoff came in with the contribution and is passed down, so a full
            // enumeration is scoped at the platform fetch rather than walked whole and filtered afterwards
            // (capability `photo-selection-policy`).
            val discovery = platform.discoverResources(store.loadToken(), ready.policy)
            log.i { "discovered ${discovery.candidates.size} candidate asset(s)" }

            // THE ADMISSION (capability `photo-selection-policy`): one policy, applied once, deciding the
            // whole admitted set — the capture-date RANGE (both bounds), the origin exclusions, the echo
            // suppression, and the album denylist together. Every consumer of this cycle reads the set
            // below; none re-states a rule, which is what makes the drift that produced the ceiling bug
            // unrepresentable (see `SelectionPolicy`).
            //
            // It stays **authoritative** even though the platform walk narrows its own fetch by some of
            // the same rules: the walk may return a superset (its predicate is deliberately widened, and
            // the incremental walk takes no predicate at all), and this is what makes that optimization
            // unable to change the admitted set. `resources()` pays the per-asset round-trip ONLY for the
            // assets it kept.
            val admitted = EventPhotoSet(ready.policy) { discovery.candidates }.assets()
            val liveResources = admitted.flatMap { it.resources() }
                .also {
                    log.i {
                        "selection policy admitted ${admitted.size} of ${discovery.candidates.size} " +
                            "candidate(s) → ${it.size} resource(s)"
                    }
                }
            Decided.Planned(ready, CyclePlan(liveResources, discovery.removedAssetIds, discovery.nextToken))
        }
    }

    // --- stage 3: update -------------------------------------------------------------------------

    /**
     * Write what is ours: the removal marks, the jobs the platform will accept, and the manifest detail
     * of rows the walk can fill. Nothing here is visible to the event.
     */
    private suspend fun Decided.update(): CycleOutcome = when (this) {
        is Decided.Short -> outcome
        is Decided.Planned -> {
            val eventId = ready.eventId
            val engine = ready.engine

            // Record that the change feed reported these assets removed (incremental, every cycle — even
            // a truncated one — so a mid-upload deletion is reflected promptly). This is the ONLY deletion
            // input: it names the departed assets exactly, where an enumeration can only fail to mention
            // one.
            //
            // A MARK, not a delete. The row's statement — these bytes are on the backend — stays true,
            // because nothing on the device deletes an uploaded object (capability `scheduled-cleanup`
            // owns the only deletion, and it deletes whole events). Keeping the row is what stops a
            // restored asset re-uploading, and iOS keeps a deleted photo recoverable for 30 days: the same
            // order as an event's whole life. The manifest stops listing it because the projection
            // excludes absent rows (capability `device-manifest`), which is where a change in what this
            // device SHARES belongs.
            for (assetId in plan.removedAssetIds) {
                log.i { "asset $assetId left the library — marking its rows absent" }
                ledger.markAbsent(assetId)
            }

            // RECORD what the walk found; do not act on it. Every admitted resource the engine judges to
            // be new work gets a `DISCOVERED` row (capability `sync-ledger`), and every already-recorded
            // one still resting bare gets its manifest detail filled.
            //
            // This loop creates no upload job, and that is the change. While it did, it stopped at the
            // platform's job limit — so the resources past that point were never recorded anywhere, the
            // cursor could not advance past them, and the next cycle had to re-walk the whole library to
            // find them again. Nothing here can stop early, so the walk's facts are captured whole.
            var newWork = 0
            var alreadyUploaded = 0
            for (resource in plan.liveResources) {
                if (engine.handle(SyncEvent.ResourceChanged(resource)) is SyncDecision.Work) {
                    newWork++
                    ledger.recordDiscovered(resource, eventId)
                } else {
                    alreadyUploaded++
                    // Enrich a row that predates the manifest detail, or that the re-join seed took from a
                    // filename listing (capability `sync-ledger`). The engine writes nothing on an
                    // already-uploaded resource, so without this sweep a seeded row would never learn its
                    // capture date — and the device manifest, projected from the ledger, would silently
                    // drop this member's photos out of the event union after every re-join or reinstall.
                    //
                    // A capture date lives only in the photo library and only the walk reads it, so a bare
                    // row the cursor has advanced past would stay bare — and fail-closed out of every
                    // projection — until something forced a full re-enumeration. That is why this is a
                    // PRECONDITION of the cursor advance below, not an opportunistic sweep.
                    //
                    // Idempotent and bare-only, exactly like the `eventId` sentinel sweep: a row already
                    // enriched is never rewritten.
                    ledger.backfillManifestDetail(resource, eventId)
                }
            }

            // THE CURSOR ADVANCE (capability `ios-photokit-upload`). Every fact this walk produced is now
            // durable: the removals are marked, the new work is `DISCOVERED`, the bare rows are filled.
            // Nothing else the walk returned is read by anything.
            //
            // ORDERING, not atomicity, is what makes this safe. The writes above are idempotent, so a
            // process death between them and this line costs one re-derivation; persisting the token
            // first would discard resources no row records, which is unrecoverable and silent. That is
            // the same write-after-act discipline the engine uses for a job, applied one level up — and
            // it is the dual of an invariant the codebase already keeps on the other side, where every
            // operation that destroys rows behind the cursor also clears it (`ResetDeviceState`,
            // `ExtensionReconciler`, `OsDrivenUploadMechanism.stop`).
            //
            // It is deliberately BEFORE any job is created. The old condition — "every job was created" —
            // was a proxy for "every resource is recorded", and on a device with more outstanding work
            // than the platform's job limit the two are never the same, so the cursor stood still for as
            // long as the device was behind and every cycle re-enumerated the whole library.
            store.saveToken(plan.nextToken)

            val enqueued = enqueue(ready)
            // Truncated by either half: the settle pass could not re-create a retry, or this pass could
            // not create everything the ledger holds. Both mean the same thing to the pump — work remains.
            val truncated = ready.capHit || enqueued.truncated
            val audit = Enumeration(plan.liveResources.size, newWork, alreadyUploaded, truncated)
            if (truncated) CycleOutcome.Truncated(ready, audit) else CycleOutcome.Drained(ready, audit)
        }
    }

    /** What one enqueue pass did, for the outcome that reports it. */
    private class Enqueued(val created: Int, val truncated: Boolean)

    /**
     * Create upload jobs from **the ledger**, not from the walk (capability `sync-ledger`).
     *
     * This is the half of the change that makes the cursor advance safe. The rows needing a job span
     * `DISCOVERED` (seen, never attempted) and `FAILED` (attempted, came back) — one fact to a producer,
     * differing only in history — so the remainder a truncated cycle left behind and a failure that has
     * been sitting since the cursor settled are picked up by the same read, on the next cycle, with no
     * re-enumeration between them.
     *
     * A key that resolves to nothing has left the library since its row was written. That is marked, not
     * failed: "the asset is gone" and "the upload did not work" have different causes and different
     * fixes, and the port's partial contract exists so this seam can tell them apart.
     *
     * The batch bounds only what is RESOLVED. What is created is bounded by the platform, which is the
     * only thing that knows how many transfers it will take — and on the app-driven tier that same limit
     * bounds staged temp-file disk, so asking for more than it will accept costs a resolve, never a
     * write.
     */
    private suspend fun enqueue(ready: Ready): Enqueued {
        val rows = ledger.rowsNeedingJob(enqueueBatchSize)
        if (rows.isEmpty()) return Enqueued(created = 0, truncated = false)

        val byKey = platform.resourcesFor(rows.mapTo(mutableSetOf()) { it.key }).associateBy { it.filename }
        var created = 0
        for (row in rows) {
            val resource = byKey[row.key]
            if (resource == null) {
                log.i { "asset ${row.assetId} is gone — cannot resolve ${row.key}; marking its rows absent" }
                ledger.markAbsent(row.assetId)
                continue
            }
            // Through the engine, never around it: it is the one place that decides whether a key uploads,
            // and it mints the request. A row that settled between the read above and here answers
            // `AlreadyUploaded` and is skipped.
            val decision = ready.engine.handle(SyncEvent.ResourceChanged(resource))
            if (decision !is SyncDecision.Work) continue
            when (platform.createJob(decision.job.request, resource)) {
                CreateResult.CREATED -> {
                    ready.engine.handle(SyncEvent.UploadStarted(decision.job))
                    created++
                }
                // Backpressure, not failure. The row stays as it was — it still needs a job — so the next
                // cycle finds it in the same read with nothing to remember in between.
                CreateResult.LIMIT_EXCEEDED -> return Enqueued(created, truncated = true)
                CreateResult.FAILED -> Unit // not created → no UploadStarted; the row still needs a job
            }
        }
        return Enqueued(created, truncated = false)
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
            // nothing at all; a definitively-absent one has already cleared its marker; a deferred seed
            // leaves the ledger unable to say what this device shares (capability `device-manifest`).
            CycleOutcome.Unreadable, CycleOutcome.NotJoined, CycleOutcome.SeedDeferred -> Unit

            // A membership that shares nothing publishes an EMPTY manifest: that is the honest statement
            // of its state, and leaving a stale one in place would keep advertising photos the member has
            // stopped sharing. Suppressed if the seed deferred — see [writeDeviceManifest].
            is CycleOutcome.Declined -> writeDeviceManifest(eventId, policy, seedSucceeded)

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
                publishManifestAndNotify(ready, promoted = promoteUploaded(ready), seedSucceeded = true)
            }

            is CycleOutcome.Drained -> {
                logEnumeration(audit)
                publishManifestAndNotify(ready, promoted = promoteUploaded(ready), seedSucceeded = true)
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
            "enumeration: ${audit.seen} seen, ${audit.newWork} new, " +
                "${audit.alreadyUploaded} already-uploaded$tail"
        }
    }

    /**
     * The device manifest (capability `device-manifest`) and then — only if it actually changed — the
     * completion notify (capability `upload-completion-notify`).
     *
     * The manifest is a PROJECTION of the ledger's settled rows, so nothing is handed over but the event
     * and the admission: every row this cycle recorded or backfilled is already durable, and the
     * projection reads them. Bounded and best-effort inside [writeDeviceManifest], so a hung host cannot
     * stall a cycle and no root has to remember to bound it.
     *
     * The notify needs BOTH halves, and each rules out a different wasted wake. [promoted] > 0 means
     * this cycle actually settled something — without it, the first manifest of an event (an empty
     * projection, which is genuinely a change from nothing) would wake every member to fetch nothing.
     * The manifest having changed means the union now states something it did not — without it, a cycle
     * that promoted a row the projection excludes, or that could not confirm its write, would wake
     * members to a union they have already seen.
     *
     * The old trigger was "this cycle DRAINED and promoted at least one row", and the word that was wrong
     * is *drained*: a device with more outstanding work than the platform's job limit never drains, so it
     * never notified — while the promotion pass ran anyway, before the short-circuit, CONSUMING the
     * signal it could not announce and leaving a later cycle that did drain with nothing to report.
     * Promoting and notifying now happen here, together, on every outcome that publishes at all, so
     * neither can be spent by a cycle that cannot act on it.
     */
    private suspend fun publishManifestAndNotify(ready: Ready, promoted: Int, seedSucceeded: Boolean) {
        val published = writeDeviceManifest(ready.eventId, ready.policy, seedSucceeded)
        if (promoted == 0 || !published) return
        runCatching { withTimeout(notifyTimeoutMs) { onBatchUploaded(ready.eventId) } }
            .onFailure { log.w(it) { "event notify failed/timed out this cycle" } }
    }

    // --- the stage vocabulary --------------------------------------------------------------------

    /** What the walk saw, in the form the write stage consumes. */
    private class CyclePlan(
        val liveResources: List<Resource>,
        val removedAssetIds: List<String>,
        val nextToken: ByteArray,
    )

    /** The enumeration audit's operands (capability `diagnostic-logging`). */
    private class Enumeration(
        val seen: Int,
        val newWork: Int,
        val alreadyUploaded: Int,
        val truncated: Boolean,
    )

    /** A settled cycle that may create work: the facts every later stage needs. */
    private class Ready(
        val eventId: String,
        val policy: SelectionPolicy,
        val engine: SyncEngine,
        val saveToAlbum: Boolean,
        /**
         * A retry the settle pass could not re-create because the platform's job limit was already
         * reached. It forces `PROCESSING` — there is outstanding work — but it withholds nothing else:
         * the row rests `FAILED` and the ledger's work read returns it next cycle.
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

        /** Definitively not joined; the leave-side marker clear has already run. */
        data object NotJoined : CycleOutcome {
            override val result get() = CycleResult.COMPLETED
        }

        /** The re-join seed deferred, so the ledger cannot say what this device shares. */
        data object SeedDeferred : CycleOutcome {
            override val result get() = CycleResult.COMPLETED
        }

        /** This membership's direction excludes upload. */
        class Declined(
            val eventId: String,
            val policy: SelectionPolicy,
            val seedSucceeded: Boolean,
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
         * was rejected: it is exactly the cycle whose remaining backlog most needs stating, and once the
         * walk only RECORDS what it finds, running it also keeps the cursor moving.
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
     * Report a failure to the engine and return its `Retry` (records `FAILED`; `REQUESTED` deferred).
     * Takes the engine rather than reading a field: it is built per cycle from that cycle's config.
     */
    private suspend fun adjudicateFailure(engine: SyncEngine, job: PlatformUploadJob): SyncDecision.Retry? {
        if (job.key.isBlank()) return null // unrecoverable key — never record a phantom row
        val failed = reconstruct(job)
        val error = job.error ?: UploadError.Unknown("unspecified")
        return engine.handle(SyncEvent.UploadFailed(failed, error)) as? SyncDecision.Retry
    }

    /**
     * Rebuild the engine [UploadJob] for a returned platform job from the ledger (attempt) and
     * the job's own facts. The request URL/headers are placeholders — completion never reads them, and
     * the retry path re-mints a fresh request via the provider.
     */
    private suspend fun reconstruct(job: PlatformUploadJob): UploadJob {
        val entry = ledger.entry(job.key)
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
        return UploadJob(UploadRequest(url = "", headers = emptyMap(), resource = resource), entry?.attempt ?: 0)
    }


    /**
     * Publish the device manifest for [eventId] under [policy] — or **suppress the write** when
     * [seedSucceeded] is false (capability `device-manifest`).
     *
     * The suppression exists because the manifest is a **full-state** document. Publishing one built from
     * an incomplete ledger does not under-report, it **un-lists**: every resource missing from the
     * projection stops being offered to the other members, even though its bytes are on the backend. That
     * became a live hazard the moment a narrowing scope change started retracting listings deliberately
     * (capability `reconfigure-membership`) — before that, a short manifest and an intended one were
     * indistinguishable in consequence, so nothing had to tell them apart.
     *
     * The known unsettled path is a deferred re-join reconciliation: it seeds the ledger from the device's
     * stored-file listing, and returns `false` when that listing fails or times out. The ledger then does
     * not yet know about resources this device really has uploaded.
     *
     * A read failure inside the hook suppresses the write too, structurally: the projection's rows are read
     * there, so if that read throws, nothing is produced and nothing is published.
     *
     * **The two outcomes are logged distinctly, deliberately.** "Could not determine what this device
     * shares" and "this device shares nothing" have opposite causes and opposite fixes, and collapsing them
     * would make an outage read as a deliberate withdrawal (law: absence is never silent).
     */
    private suspend fun writeDeviceManifest(
        eventId: String,
        policy: SelectionPolicy,
        seedSucceeded: Boolean,
    ): Boolean {
        if (!seedSucceeded) {
            log.i {
                "device.json NOT written this cycle — the ledger is unsettled, so the projection would " +
                    "un-list resources that are really uploaded. The previous manifest stands."
            }
            return false
        }
        // Best-effort and bounded here, so a hung host can never stall a cycle and no root has to
        // remember to bound it (both used to, with the same two constants — one copied from the other,
        // along with a justification that only applied to the tier it was copied FROM).
        //
        // The answer is what gates the notify (capability `upload-completion-notify`): `false` covers
        // "the projection was unchanged, so nothing was PUT" and "the write failed or timed out" alike,
        // and both mean the same thing to a recipient — the union does not list anything it did not list
        // before, so waking anyone would be a wasted background launch.
        return runCatching { withTimeout(deviceManifestTimeoutMs) { onDiscovery(eventId, policy) } }
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
     * The engine is still the thing that decides: `UploadFailed` records `FAILED` at the incremented
     * attempt and answers a freshly-minted request. That the row is already `FAILED` from the adapter's
     * guarded write is harmless — the record is an idempotent upsert, and the attempt bump is exactly what
     * a re-created job wants.
     *
     * Creates no job for work not already begun, writes no manifest, enumerates nothing, and touches no
     * discovery cursor — which is what lets a direction-declined cycle run it. The only jobs it can create
     * are replacements for failures the platform already tried.
     */
    private suspend fun recreateRetrySpent(engine: SyncEngine): Boolean {
        var capHit = false
        for (job in platform.drainTerminals()) {
            // At-least-once: the platform can hand back a failure for a key that has since settled (its
            // own guarded write already declined to touch it). Adjudicating anyway would drive the engine
            // to record FAILED over a COMPLETED row and re-upload bytes that are stored — the failure
            // this whole change exists to stop, arriving by a different door.
            if (ledger.entry(job.key)?.state?.isDone == true) continue
            val retry = adjudicateFailure(engine, job) ?: continue
            if (job.data == null || capHit) continue
            when (platform.createJob(retry.job.request, retry.job.request.resource)) {
                CreateResult.CREATED -> engine.handle(SyncEvent.UploadStarted(retry.job))
                CreateResult.LIMIT_EXCEEDED -> capHit = true // rediscovery retries this key
                CreateResult.FAILED -> Unit // not created → no UploadStarted
            }
        }
        return capHit
    }

    /**
     * Phase 2b — the promotion pass, shared by both tiers.
     *
     * `UPLOADED` rows are the completions this cycle learns about: their bytes are stored, and what they
     * still owe is the event-album placement and the completion notify. Place, then promote; the notify
     * fires later, after the device-manifest write, because the manifest projects `COMPLETED` rows and a
     * recipient woken before it lands would find a union that does not list these assets yet. That is why
     * the promotion happens **here** rather than after the manifest: promoting first is what puts them in
     * it.
     *
     * Promotion does not wait on the placement succeeding. Both effects are best-effort, as they were
     * before, and gating the row on them would invent a permanently-stuck state — `UPLOADED` counts as
     * pending everywhere, so a device whose album or notify kept failing would read "uploading" forever
     * over photos that are already stored.
     *
     * A repeat placement after a crash between the two is free: `addAssets` on an asset already in the
     * collection is a no-op (measured, simulator, iOS 26.5 — `changes/fix-lost-upload-acks`).
     *
     * Runs in [publish], FIRST, and that order is load-bearing: the manifest is a projection of settled
     * rows, so a row promoted after the write would be missing from the projection the notify wakes
     * recipients to read. It publishes nothing itself but it settles what the manifest then states, which
     * is why it belongs with the outward effects rather than with the ledger writes.
     *
     * Answers how many rows it promoted — one half of the notify's trigger, the other being whether the
     * manifest changed. That count is safe to use again now that promotion and notify happen in the same
     * place: it can no longer be spent by a cycle that goes on to publish nothing.
     */
    private suspend fun promoteUploaded(ready: Ready): Int {
        val rows = ledger.uploadedRows()
        if (rows.isEmpty()) return 0
        if (ready.saveToAlbum) {
            val assetIds = rows.mapTo(mutableSetOf()) { assetIdFromUploadKey(it.key) }
            runCatching { placeInAlbum(ready.eventId, assetIds) }
                .onFailure { log.w(it) { "event-album placement failed this cycle" } }
        }
        rows.forEach { ledger.promote(it.key) }
        log.i { "promoted ${rows.size} uploaded row(s) to COMPLETED" }
        return rows.size
    }

}
