package app.snapsync.upload

import app.snapsync.engine.LedgerState
import app.snapsync.engine.LedgerWriter
import app.snapsync.engine.Resource
import app.snapsync.engine.SyncDecision
import app.snapsync.engine.SyncEngine
import app.snapsync.engine.SyncEvent
import app.snapsync.engine.UploadError
import app.snapsync.engine.UploadJob
import app.snapsync.engine.UploadRequest
import app.snapsync.gallery.RESOURCE_META_CREATION_DATE
import app.snapsync.gallery.assetIdFromUploadKey
import co.touchlab.kermit.Logger

/**
 * One background-upload cycle, platform-free: adjudicate the system's returned jobs (completion +
 * retry), then discover new/changed resources and create jobs — all gated by the [engine]. This is
 * the testable core: it depends only on the [engine], the [ledger] (to reconstruct lifecycle jobs
 * and to prune rows for deleted assets), the [UploadJobPlatform] port, and the [DiscoveryStore]
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
    private val engine: SyncEngine,
    private val ledger: LedgerWriter,
    private val platform: UploadJobPlatform,
    private val store: DiscoveryStore,
    private val log: Logger = Logger.withTag("UploadCycle"),
    // Best-effort hook fired once per fully-drained cycle with that cycle's discovery — the device
    // manifest is built from THIS (no second PhotoKit enumeration). Default no-op for tests/harness.
    private val onDiscovery: suspend (Discovery) -> Unit = {},
    // Suppression port (capability `photo-download`): the set of `assetId`s of foreign assets this
    // device downloaded + imported. Read once per cycle; discovery drops these BEFORE fan-out so an
    // imported foreign asset (a fresh local id) is never re-uploaded (the echo). Read-only, backed in
    // iosMain by the app-written download store; default empty for tests/harness.
    private val suppressedAssetIds: suspend () -> Set<String> = { emptySet() },
    // Capture-date cutoff (capability `photo-date-cutoff`): the MINIMUM cutoff across the device's
    // memberships (v1: the single joined event's `EventConfig.minPhotoDate`). Read once per cycle;
    // discovery drops every resource whose asset `creationDate` precedes it BEFORE the engine sees it, so
    // a pre-cutoff photo's bytes are never uploaded. Applied to both the full and the incremental walk.
    //
    // Required, with **no default**: a cycle without a cutoff would upload the whole library. Every caller
    // reaches this only past a joined-event guard, so a cutoff always exists. There is no safe default to
    // offer tests either — `""` compares `>=` true against every `creationDate`.
    private val photoCutoff: suspend () -> String,
    // Re-join reconciliation (capability `event-rejoin-reconciliation`): the marker-gated seed that makes
    // already-stored resources `COMPLETED` before the producer runs, so a re-joined / switched /
    // reinstalled device re-uploads nothing it has already contributed. Returns whether the producer may
    // create jobs this cycle — `false` defers (a failed/timed-out device listing), and this cycle creates
    // nothing and leaves the ledger, cursor, and marker untouched so the next cycle retries.
    //
    // Required, with **no default**, for the same reason as [photoCutoff]: it lives in the CYCLE, not in
    // each tier's composition root, because the cycle is the only thing that runs on EVERY route to a
    // divergent ledger — a fresh join, an event switch, a leave-then-rejoin, and a delete-and-reinstall
    // (which no provisioning path observes at all: a cold relaunch of an already-joined app provisions
    // nothing). Root-wired reconciliation is exactly how the app-driven tier shipped without any, so a
    // defaulted `{ true }` would re-open that hole silently. The cycle stays event-agnostic — the root's
    // lambda closes over the eventId, like [onBatchUploaded].
    private val reconcile: suspend () -> Boolean,
    // Notify hook (capability `upload-completion-notify`): fired once per FULLY-DRAINED cycle that
    // recorded >= 1 real completion, AFTER `onDiscovery` (the device-manifest PUT) — the only moment the
    // event union reflects the just-completed assets, so recipients woken by the fan-out find them. The
    // cycle stays event-agnostic: the root's lambda closes over the eventId (and applies its own bounded
    // timeout, like `onDiscovery`). Best-effort — invoked under `runCatching`; default no-op for
    // tests/harness.
    private val onBatchUploaded: suspend () -> Unit = {},
    // Event-album placement (capability `event-album`): fired with the `assetId`s (normalized) that
    // GENUINELY completed this cycle, so the running process adds those own photos to the event album.
    // Runs in whichever process runs the cycle (extension on ≥26.1, app on 18–26.0). Best-effort —
    // invoked under `runCatching`; default no-op for tests/harness and album-off memberships.
    private val placeInAlbum: suspend (assetIds: Set<String>) -> Unit = {},
) {
    suspend fun run(): CycleResult {
        // Phase 0 — re-join reconciliation (capability `event-rejoin-reconciliation`), BEFORE any upload
        // job is created. On a marker mismatch it seeds the ledger from the device's stored-file listing
        // so nothing already contributed re-uploads; on a settled join it is a no-op. A `false` return is
        // a deferral (the listing fetch failed or timed out): create nothing this cycle and report a clean
        // COMPLETED — a no-op, never a failure — so the next cycle retries with the marker still unset. A
        // THROW is treated identically to `false` (never a FAILED cycle), preserving the deferral the
        // roots previously applied with their own `runCatching`.
        val mayUpload = runCatching { reconcile() }
            .getOrElse { log.e(it) { "reconcile failed — deferring uploads this cycle" }; false }
        if (!mayUpload) {
            log.i { "reconcile deferred this cycle — creating no upload jobs" }
            return CycleResult.COMPLETED
        }

        // Phase 1 — first failures: re-point the system's single retry at a rebuilt edge URL
        // (stable, no expiry — the provider re-derives the identical destination locally).
        for (job in platform.fetchRetryJobs()) {
            val retry = adjudicateFailure(job) ?: continue
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
                    val retry = adjudicateFailure(job)
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
        if (completedAssetIds.isNotEmpty()) runCatching { placeInAlbum(completedAssetIds) }

        if (capHit) return CycleResult.PROCESSING // cursor NOT advanced

        // Phase 3 — discover new/changed resources; REQUESTED-skip filters everything in flight. The
        // cutoff is read first and passed in, so a full enumeration is scoped at the platform fetch rather
        // than walked whole and filtered afterwards (capability `photo-date-cutoff`).
        val cutoff = photoCutoff()
        val discovery = platform.discoverResources(store.loadToken(), cutoff)
        log.i { "discovered ${discovery.resources.size} resource(s)" }

        // Echo-suppression: drop resources of assets this device downloaded + imported (their fresh
        // local id would otherwise look like new work and re-upload the foreign photo). Filtered here,
        // before the engine sees them and before retainAssets, so no upload job is ever created.
        val suppressed = suppressedAssetIds()
        val unfiltered = if (suppressed.isEmpty()) {
            discovery.resources
        } else {
            discovery.resources.filterNot { it.assetId in suppressed }
                .also { log.i { "suppressed ${discovery.resources.size - it.size} downloaded resource(s)" } }
        }

        // Capture-date cutoff (capability `photo-date-cutoff`): drop resources whose asset `creationDate`
        // precedes the cutoff, so pre-cutoff bytes never upload. An asset with no `creationDate` (empty
        // string) sorts before any non-empty cutoff and is excluded.
        //
        // This filter stays **authoritative** even though the platform walk now narrows its own fetch by
        // the same bound: the walk may return a superset (its predicate is deliberately widened), and this
        // is what makes that optimization unable to change the admitted set.
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
        runCatching { onDiscovery(discovery) }
            .onFailure { log.w(it) { "device-manifest hook failed this cycle" } }

        // Notify the event's members (capability `upload-completion-notify`) — but only now, on a
        // fully-drained cycle that completed >= 1 upload, and AFTER the manifest PUT above: that is the
        // only point the event union reflects the just-completed assets, so a woken recipient finds
        // them. Best-effort and bounded by the impl (like `onDiscovery`); a failure never fails the
        // cycle. A cap-truncated cycle returned earlier (no notify); a drained cycle with no completion
        // skips it.
        if (completedThisCycle > 0) {
            runCatching { onBatchUploaded() }
                .onFailure { log.w(it) { "upload-notify hook failed this cycle" } }
        }

        store.saveToken(discovery.nextToken) // advance only on a fully-drained cycle
        return CycleResult.COMPLETED
    }

    /** Report a failure to the engine and return its `Retry` (records `FAILED`; `REQUESTED` deferred). */
    private suspend fun adjudicateFailure(job: PlatformUploadJob): SyncDecision.Retry? {
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
