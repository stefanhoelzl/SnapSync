package app.snapsync.ios.upload

import app.snapsync.engine.LedgerState
import app.snapsync.engine.LedgerWriter
import app.snapsync.engine.Resource
import app.snapsync.engine.SyncDecision
import app.snapsync.engine.SyncEngine
import app.snapsync.engine.SyncEvent
import app.snapsync.engine.UploadError
import app.snapsync.engine.UploadJob
import app.snapsync.engine.UploadRequest
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
) {
    suspend fun run(): CycleResult {
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
        for (job in platform.fetchAckJobs()) {
            when {
                job.state == PlatformJobState.SUCCEEDED -> {
                    engine.handle(SyncEvent.UploadCompleted(reconstruct(job)))
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
        if (capHit) return CycleResult.PROCESSING // cursor NOT advanced

        // Phase 3 — discover new/changed resources; REQUESTED-skip filters everything in flight.
        val discovery = platform.discoverResources(store.loadToken())
        log.i { "discovered ${discovery.resources.size} resource(s)" }

        // Prune rows for assets the change feed reported removed (incremental, every cycle — even a
        // cap-truncated one — so a mid-upload deletion's stuck row is cleared promptly).
        for (assetId in discovery.removedAssetIds) {
            log.i { "pruning deleted asset $assetId" }
            ledger.deleteByAssetId(assetId)
        }

        for (resource in discovery.resources) {
            val decision = engine.handle(SyncEvent.ResourceChanged(resource))
            if (decision is SyncDecision.Work) {
                when (platform.createJob(decision.job.request, resource)) {
                    CreateResult.CREATED -> engine.handle(SyncEvent.UploadStarted(decision.job))
                    CreateResult.LIMIT_EXCEEDED -> return CycleResult.PROCESSING // cursor NOT advanced
                    CreateResult.FAILED -> Unit // not created → no UploadStarted; retried next discovery
                }
            }
        }

        // Reconcile only on a fully-drained full enumeration (the same gate that advances the
        // cursor): `resources` then covers every current asset, so retainAssets prunes rows for assets
        // no longer present — the backstop for deletions missed while the change token was expired.
        // Skipped on incremental cycles and on cap-truncated ones (which returned PROCESSING above).
        if (discovery.fullEnumeration) {
            ledger.retainAssets(discovery.resources.mapTo(mutableSetOf()) { it.assetId })
        }

        store.saveToken(discovery.nextToken) // advance only on a fully-drained cycle
        return CycleResult.COMPLETED
    }

    /** Report a failure to the engine and return its `Retry` (records `FAILED`; `REQUESTED` deferred). */
    private suspend fun adjudicateFailure(job: PlatformUploadJob): SyncDecision.Retry? {
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
            assetId = entry?.assetId ?: "",
            contentType = job.contentType,
            metadata = emptyMap(),
            data = job.data ?: Unit, // engine [Resource.data] is non-null; payload unused for completion
        )
        return UploadJob(UploadRequest(url = "", headers = emptyMap(), resource = resource), entry?.attempt ?: 0)
    }
}
