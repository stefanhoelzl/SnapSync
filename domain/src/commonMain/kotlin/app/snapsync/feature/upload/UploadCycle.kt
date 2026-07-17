package app.snapsync.feature.upload

import app.snapsync.ports.CreateResult
import app.snapsync.ports.CycleResult
import app.snapsync.ports.Discovery
import app.snapsync.ports.DiscoveryStore
import app.snapsync.ports.PlatformJobState
import app.snapsync.ports.PlatformUploadJob
import app.snapsync.ports.BackgroundTransfer

import app.snapsync.model.LedgerState
import app.snapsync.feature.upload.LedgerWriter
import app.snapsync.model.Resource
import app.snapsync.model.SyncDecision
import app.snapsync.feature.upload.SyncEngine
import app.snapsync.model.SyncEvent
import app.snapsync.model.UploadError
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadRequest
import app.snapsync.model.Contribution
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.model.excludedAssetIds
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
    // joined to", and every answer is wrong. See [reconcile] and [Contribution] for the same reasoning
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
    private val onDiscovery: suspend (eventId: String, cutoff: String, discovery: Discovery) -> Unit,
    // Suppression port (capability `photo-download`): the set of `assetId`s of foreign assets this
    // device downloaded + imported. Read once per cycle; discovery drops these BEFORE fan-out so an
    // imported foreign asset (a fresh local id) is never re-uploaded (the echo). Read-only, backed in
    // iosMain by the app-written download store.
    //
    // Required, with **no default**: `{ emptySet() }` re-uploads every downloaded foreign photo back into
    // the event.
    private val suppressedAssetIds: suspend () -> Set<String>,
    // Denylisted-album membership (capability `photo-selection-policy`): the normalized `assetId`s that sit
    // in an album a messaging/social app made (WhatsApp, Telegram, …). Read once per cycle and dropped
    // alongside the other origin exclusions, BEFORE the engine and before `retainAssets`. Takes the cutoff,
    // which scopes the album member fetch.
    //
    // An injected port rather than a rule in the pure filter, because album membership is the one origin
    // fact that is NOT already on the resource — it needs a platform lookup. The POLICY (which titles)
    // stays in `commonMain` (`:capability:album`'s DENYLISTED_ALBUM_TITLES); this port only carries the
    // answer. Cost is O(albums), not O(assets).
    //
    // Required, with **no default**: `{ emptySet() }` uploads the member's WhatsApp album into a stranger's
    // event. A tier without an album source states `{ emptySet() }` at its call site, where a reviewer can
    // see it — that is the difference this requirement buys.
    private val albumExcludedAssetIds: suspend (cutoff: String) -> Set<String>,
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
) {
    suspend fun run(): CycleResult {
        // THE ENTRY GATE (capability `upload-lifecycle`) — before the direction gate, the reconcile, the
        // walk, and every hook. Three outcomes, and the difference between two of them is the difference
        // between a settled join and a false leave.
        val gate = readGate()
        val (config, membership) = when (gate) {
            is CycleGate.Skip -> {
                // Unreadable ≠ left. Touch NOTHING: no reconcile, no marker clear, no cursor reset, no
                // jobs. A clean completion; the next cycle — or the next unlock — retries. `detail` is the
                // root's forensics, logged verbatim: this line is the only way an unreadable membership is
                // visible on a device.
                log.w {
                    "skipping cycle — ${gate.detail.ifEmpty { "a required read failed" }}. NOT treating " +
                        "this as a leave; nothing minted, nothing reconciled, marker untouched."
                }
                return CycleResult.COMPLETED
            }
            CycleGate.NotJoined -> {
                // Definitively not joined: no item, an item that cannot decode, a missing baked host, or a
                // leave. This is where a leave clears the `joinedEventId` marker — it keeps the ledger,
                // cursor, and accumulator intact so a later provision of any event dedups against them.
                // Reaching here means the config really IS absent, never merely unread.
                runCatching { reconcile(null) }
                    .onFailure { log.w(it) { "leave-side marker clear failed" } }
                log.i { "skipping cycle — no joined event / host" }
                return CycleResult.COMPLETED
            }
            is CycleGate.Run -> gate.config to gate.membership
        }

        // THE DIRECTION GATE (capability `upload-lifecycle`) — ahead of the reconcile, the walk, job
        // creation, the manifest write, and the notify: a non-contributor must not enumerate its library to
        // discover it contributes nothing (the walk costs ~110 ms of PhotoKit XPC per asset). Nothing below
        // this line runs, and the discovery cursor is left exactly where it was.
        //
        // Skipping the acknowledgement pass is safe on both tiers: on iOS ≥26.1 the OS presents no jobs
        // because `stop()` deregistered the extension (`setUploadJobExtensionEnabled(false)` wipes the
        // configuration and every in-flight job), and the app-driven tier has no appex, so there is no
        // "acknowledge or the system errors 50008" obligation to honour here.
        val scope = when (val c = membership.contribution) {
            Contribution.None -> {
                log.i { "cycle skipped — this membership contributes nothing (direction excludes upload)" }
                return CycleResult.SKIPPED
            }
            is Contribution.Since -> c.cutoff
        }

        val eventId = config.eventId
        val engine = engineFor(config)
        // Phase 0 — re-join reconciliation (capability `event-rejoin-reconciliation`), BEFORE any upload
        // job is created. On a marker mismatch it seeds the ledger from the device's stored-file listing
        // so nothing already contributed re-uploads; on a settled join it is a no-op. A `false` return is
        // a deferral (the listing fetch failed or timed out): create nothing this cycle and report a clean
        // COMPLETED — a no-op, never a failure — so the next cycle retries with the marker still unset. A
        // THROW is treated identically to `false` (never a FAILED cycle), preserving the deferral the
        // roots previously applied with their own `runCatching`.
        val mayUpload = runCatching { reconcile(eventId) }
            .getOrElse { log.e(it) { "reconcile failed — deferring uploads this cycle" }; false }
        if (!mayUpload) {
            log.i { "reconcile deferred this cycle — creating no upload jobs" }
            return CycleResult.COMPLETED
        }

        // Phase 1 — first failures: re-point the system's single retry at a rebuilt edge URL
        // (stable, no expiry — the provider re-derives the identical destination locally).
        for (job in platform.fetchRetryJobs()) {
            val retry = adjudicateFailure(engine, job) ?: continue
            platform.retryJob(job, retry.job.request)
            engine.handle(SyncEvent.UploadStarted(retry.job))
        }

        // Phase 2 — terminal jobs. EVERY job MUST be acknowledged (the system errors 50008 —
        // "appex failed to acknowledge jobs for processing state" — for any it presents that we
        // leave un-acknowledged), so all arms acknowledge.
        var capHit = false
        // Real completions THIS cycle (a succeeded job with a recoverable key). Re-acks of an
        // already-COMPLETED key do NOT count — they are not new work. Gates the notify fan-out below.
        var completedThisCycle = 0
        // The `assetId`s (normalized) that genuinely completed this cycle — added to the event album
        // (capability `event-album`) once the terminal-job pass finishes.
        val completedAssetIds = mutableSetOf<String>()
        for (job in platform.fetchAckJobs()) {
            when {
                job.state == PlatformJobState.SUCCEEDED -> {
                    // Record COMPLETED only for a recoverable key: a blank/unrecoverable key would
                    // reconstruct a phantom `assetId=""` row. Acknowledge regardless — never leave a
                    // presented job un-acknowledged (the system errors 50008).
                    if (job.key.isNotBlank()) {
                        // Count only a GENUINELY-new completion: at-least-once delivery means the OS can
                        // re-hand a job whose key is already COMPLETED — that duplicate must not fire a
                        // spurious notify. Read the prior state before the (idempotent) engine write.
                        val wasCompleted = ledger.entry(job.key)?.state == LedgerState.COMPLETED
                        engine.handle(SyncEvent.UploadCompleted(reconstruct(job)))
                        if (!wasCompleted) {
                            completedThisCycle++
                            completedAssetIds.add(assetIdFromUploadKey(job.key))
                        }
                    }
                    platform.acknowledge(job)
                }
                ledger.entry(job.key)?.state == LedgerState.COMPLETED -> platform.acknowledge(job)
                else -> {
                    // Retry-spent failure: record FAILED, then re-create a fresh job — but only if the
                    // resource is still available (nil for released jobs) and the cap is not yet hit.
                    val retry = adjudicateFailure(engine, job)
                    if (retry != null && job.data != null && !capHit) {
                        when (platform.createJob(retry.job.request, retry.job.request.resource)) {
                            CreateResult.CREATED -> engine.handle(SyncEvent.UploadStarted(retry.job))
                            CreateResult.LIMIT_EXCEEDED -> capHit = true // rediscovery retries this key
                            CreateResult.FAILED -> Unit // not created → no UploadStarted; job acked below
                        }
                    }
                    platform.acknowledge(job) // always — never leave a presented job un-acknowledged
                }
            }
        }
        // Event album (capability `event-album`): add this cycle's genuinely-new completions to the
        // event album, best-effort, before any early return. Runs in whichever process ran the cycle.
        // The opt-in arrived with the gate, so it is checked once here rather than inside each root's
        // lambda — one of the four copies of that check.
        if (membership.saveToAlbum && completedAssetIds.isNotEmpty()) {
            runCatching { placeInAlbum(eventId, completedAssetIds) }
                .onFailure { log.w(it) { "event-album placement failed this cycle" } }
        }

        if (capHit) return CycleResult.PROCESSING // cursor NOT advanced

        // Phase 3 — discover new/changed resources; REQUESTED-skip filters everything in flight. The
        // cutoff came in with the contribution and is passed down, so a full enumeration is scoped at the
        // platform fetch rather than walked whole and filtered afterwards (capability
        // `photo-selection-policy`).
        val cutoff = scope
        val discovery = platform.discoverResources(store.loadToken(), cutoff)
        log.i { "discovered ${discovery.resources.size} resource(s)" }

        // Echo-suppression: drop resources of assets this device downloaded + imported (their fresh
        // local id would otherwise look like new work and re-upload the foreign photo). Filtered here,
        // before the engine sees them and before retainAssets, so no upload job is ever created.
        val suppressed = suppressedAssetIds()
        val unsuppressed = if (suppressed.isEmpty()) {
            discovery.resources
        } else {
            discovery.resources.filterNot { it.assetId in suppressed }
                .also { log.i { "suppressed ${discovery.resources.size - it.size} downloaded resource(s)" } }
        }

        // Origin exclusions (capability `photo-selection-policy`): drop assets that were never *taken* —
        // screenshots, screen recordings, GIFs, sub-floor-resolution received media, and members of a
        // denylisted album. Authoritative and platform-free: the iOS fetch predicate narrows by subtype as
        // an optimization, but it can express neither the floors nor `hasAdjustments` (both abort the
        // process), and the incremental walk takes no predicate at all — so this filter is what actually
        // decides, on every tier and on both walks.
        //
        // Excluded per ASSET, not per resource: an asset's resources stand or fall together, or a live
        // photo's paired video would survive its excluded primary as an orphan.
        val originExcluded = excludedAssetIds(unsuppressed) + albumExcludedAssetIds(cutoff)
        val unfiltered = if (originExcluded.isEmpty()) {
            unsuppressed
        } else {
            unsuppressed.filterNot { it.assetId in originExcluded }
                .also { log.i { "origin policy dropped ${unsuppressed.size - it.size} resource(s)" } }
        }

        // Capture-date cutoff (capability `photo-selection-policy`): drop resources whose asset `creationDate`
        // precedes the cutoff, so pre-cutoff bytes never upload. An asset with no `creationDate` (empty
        // string) sorts before any non-empty cutoff and is excluded.
        //
        // This filter stays **authoritative** even though the platform walk now narrows its own fetch by
        // the same bound: the walk may return a superset (its predicate is deliberately widened), and this
        // is what makes that optimization unable to change the admitted set.
        //
        // NB the cutoff is applied AFTER `unfiltered`, and `unfiltered` — not `liveResources` — is what
        // feeds the device manifest below. That split is deliberate; see the `onDiscovery` call.
        val liveResources = unfiltered
            .filter { (it.metadata[RESOURCE_META_CREATION_DATE] ?: "") >= cutoff }
            .also { log.i { "cutoff dropped ${unfiltered.size - it.size} pre-cutoff resource(s)" } }

        // Prune rows for assets the change feed reported removed (incremental, every cycle — even a
        // cap-truncated one — so a mid-upload deletion's stuck row is cleared promptly).
        for (assetId in discovery.removedAssetIds) {
            log.i { "pruning deleted asset $assetId" }
            ledger.deleteByAssetId(assetId)
        }

        var newWork = 0
        var alreadyUploaded = 0
        for (resource in liveResources) {
            val decision = engine.handle(SyncEvent.ResourceChanged(resource))
            if (decision is SyncDecision.Work) {
                newWork++
                when (platform.createJob(decision.job.request, resource)) {
                    CreateResult.CREATED -> engine.handle(SyncEvent.UploadStarted(decision.job))
                    CreateResult.LIMIT_EXCEEDED -> return CycleResult.PROCESSING // cursor NOT advanced
                    CreateResult.FAILED -> Unit // not created → no UploadStarted; retried next discovery
                }
            } else {
                alreadyUploaded++
            }
        }
        // Per-cycle enumeration summary (capability `diagnostic-logging`, D6): accountable for the whole
        // enumeration without a line per already-uploaded asset (the engine's per-asset AlreadyUploaded
        // skip stays silent). A cap-truncated cycle returns above, so this reflects a drained pass.
        log.i { "enumeration: ${liveResources.size} seen, $newWork new, $alreadyUploaded already-uploaded" }

        // Reconcile only on a fully-drained full enumeration (the same gate that advances the
        // cursor): `resources` then covers every current asset, so retainAssets prunes rows for assets
        // no longer present — the backstop for deletions missed while the change token was expired.
        // Skipped on incremental cycles and on cap-truncated ones (which returned PROCESSING above).
        if (discovery.fullEnumeration) {
            ledger.retainAssets(liveResources.mapTo(mutableSetOf()) { it.assetId })
        }

        // Feed the device manifest from THIS cycle's discovery (no second enumeration). Best-effort and
        // bounded by the impl — it must never fail or stall the cycle (byte jobs are already created).
        //
        // It is handed the ORIGIN-FILTERED set (`unfiltered`), NOT the raw discovery and NOT the
        // cutoff-filtered `liveResources`. The two exclusions land on opposite sides of the manifest's
        // device-global accumulator on purpose (capability `device-manifest`):
        //
        //  - The CUTOFF is per-membership, so it must stay OUT of the accumulator — another event's cutoff
        //    may admit an asset this one excludes. The accumulator keeps it; the per-event projection drops
        //    it. Hence `unfiltered`, not `liveResources`.
        //  - The ORIGIN exclusions are event-independent — a screenshot is a screenshot in every event, and
        //    no membership will ever admit one — so they go IN before the accumulator, costing it no
        //    per-event flexibility. Hence `unfiltered`, not `discovery`.
        //
        // Passing the raw discovery here (as this did before) put every excluded asset into the accumulator,
        // from which it projected into device.json, entered the event union, and was offered to every other
        // member as bytes that were never uploaded — because the cycle drops them above. A 404 for everyone.
        val manifestDiscovery = Discovery(
            resources = unfiltered,
            nextToken = discovery.nextToken,
            removedAssetIds = discovery.removedAssetIds,
            fullEnumeration = discovery.fullEnumeration,
        )
        runCatching { withTimeout(deviceManifestTimeoutMs) { onDiscovery(eventId, cutoff, manifestDiscovery) } }
            .onFailure { log.w(it) { "device.json production failed/timed out this cycle" } }

        // Notify the event's members (capability `upload-completion-notify`) — but only now, on a
        // fully-drained cycle that completed >= 1 upload, and AFTER the manifest PUT above: that is the
        // only point the event union reflects the just-completed assets, so a woken recipient finds
        // them. Best-effort and bounded by the impl (like `onDiscovery`); a failure never fails the
        // cycle. A cap-truncated cycle returned earlier (no notify); a drained cycle with no completion
        // skips it.
        if (completedThisCycle > 0) {
            runCatching { withTimeout(notifyTimeoutMs) { onBatchUploaded(eventId) } }
                .onFailure { log.w(it) { "event notify failed/timed out this cycle" } }
        }

        store.saveToken(discovery.nextToken) // advance only on a fully-drained cycle
        return CycleResult.COMPLETED
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
}
