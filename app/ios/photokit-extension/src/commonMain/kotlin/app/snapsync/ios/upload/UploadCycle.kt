package app.snapsync.ios.upload

import app.snapsync.engine.SyncDecision
import app.snapsync.engine.SyncEngine
import app.snapsync.engine.SyncEvent
import co.touchlab.kermit.Logger

/**
 * One background-upload cycle, platform-free: drain the system queue, discover changed resources,
 * and for each one let the [engine] decide whether to create a (dummy-destination) upload job. This
 * is the testable core — it depends only on the [SyncEngine] and the [UploadJobPlatform] port, so a
 * fake platform + a real engine exercise the whole discover→decide→create→drain flow on the
 * simulator without touching PhotoKit.
 *
 * The engine's decision is what gates job creation: `AlreadyUploaded` (a `COMPLETED` ledger proof
 * for the same version) creates nothing; any `Work` answer mints a job. Nothing records `COMPLETED`
 * in this slice — success is `REQUESTED` accumulating.
 */
class UploadCycle(
    private val engine: SyncEngine,
    private val platform: UploadJobPlatform,
    private val log: Logger = Logger.withTag("UploadCycle"),
) {
    suspend fun run() {
        platform.drainJobs()
        val resources = platform.discoverResources()
        if (resources.isEmpty()) {
            log.i { "no new resources this cycle" }
            return
        }
        for (resource in resources) {
            when (val decision = engine.handle(SyncEvent.ResourceChanged(resource))) {
                is SyncDecision.Work -> platform.createJob(decision.job.request, resource)
                SyncDecision.AlreadyUploaded -> Unit
            }
        }
    }
}
