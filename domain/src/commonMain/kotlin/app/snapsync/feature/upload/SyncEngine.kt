package app.snapsync.feature.upload

import app.snapsync.model.LedgerState
import app.snapsync.model.Resource
import app.snapsync.model.SyncDecision
import app.snapsync.model.SyncEvent
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadRequestProvider
import co.touchlab.kermit.Logger

/**
 * The decision core (spec: sync-engine): platforms drive it with [SyncEvent] observations, it
 * answers with [SyncDecision]s. Its only state is the [ledger] — the durable per-key memory of
 * what was requested, completed, and failed, written exclusively by this engine.
 *
 * Decision rules ([SyncEvent.ResourceChanged] is a **pure query** — it reads the ledger and mints a
 * request for `Work` answers, but writes nothing): a key is skipped when the ledger holds it
 * `COMPLETED` **or** `REQUESTED` (an uploaded resource is immutable, so a `COMPLETED` key is never
 * re-uploaded; `REQUESTED` means a job is in flight — see write-after-act below); only a `FAILED` or
 * absent entry yields `Work`.
 *
 * Write-after-act: the ledger changes only on the three lifecycle observations — [SyncEvent.UploadStarted]
 * → `REQUESTED`, [SyncEvent.UploadFailed] → `FAILED`, [SyncEvent.UploadCompleted] → `COMPLETED` —
 * each an unconditional idempotent per-key upsert. Because `REQUESTED` is recorded only *after* the
 * platform reports it created the job, a `REQUESTED` entry always implies a real in-flight job, which
 * is what makes skipping it safe. A provider failure during minting throws before any write, so the
 * event counts as unprocessed; replayed/at-least-once reports converge instead of drifting.
 *
 * Concurrency: at most one [handle] call in flight per engine — all known drivers are
 * sequential loops; a concurrent driver must serialize (or a future slice reintroduces the
 * guarantee it pays for).
 *
 * Policy (v1): **retry forever** — every failure yields [SyncDecision.Retry] with a newly
 * minted request, so expired destinations heal on retry. No attempt budget, no give-up.
 */
class SyncEngine(
    private val provider: UploadRequestProvider,
    private val ledger: LedgerWriter,
    // The joined event this engine records under (provenance on every written row — spec
    // `sync-ledger`). Available by construction: the engine is minted per cycle from that cycle's
    // config (`engineFor`), which is where the eventId arrives. Required, with **no default**: ""
    // is the pre-provenance sentinel, and a defaulted "" would silently mint sentinel rows forever.
    private val eventId: String,
) {

    private val log = Logger.withTag("SyncEngine")

    /**
     * Logging (spec: diagnostic-logging, field diagnostics — the headless iOS extension's only observability):
     * a failure WARNs with its mapped error, every issued [SyncDecision.Work] INFOs its arm + key +
     * attempt, and the [SyncEvent.UploadStarted] / [SyncEvent.UploadCompleted] confirmations INFO
     * "started" / "completed". The skip on re-enumeration ([SyncDecision.AlreadyUploaded] for
     * [SyncEvent.ResourceChanged]) is silent — it fires per change-cycle and would drown the signal.
     * Logs are diagnostics, never asserted: the decision methods stay pure, all logging lives here at
     * the dispatch seam.
     */
    suspend fun handle(event: SyncEvent): SyncDecision {
        if (event is SyncEvent.UploadFailed) {
            val resource = event.job.request.resource
            log.w { "failed key=${resource.filename} attempt=${event.job.attempt} error=${event.error}" }
        }
        val decision = when (event) {
            is SyncEvent.ResourceChanged -> decide(event.resource)
            is SyncEvent.UploadFailed -> retry(event.job)
            is SyncEvent.UploadCompleted -> complete(event.job)
            is SyncEvent.UploadStarted -> started(event.job)
        }
        when (decision) {
            is SyncDecision.Upload -> logWork("Upload", decision)
            is SyncDecision.Retry -> logWork("Retry", decision)
            SyncDecision.AlreadyUploaded -> when (event) {
                is SyncEvent.UploadCompleted -> logLifecycle("completed", event.job)
                is SyncEvent.UploadStarted -> logLifecycle("started", event.job)
                else -> Unit
            }
        }
        return decision
    }

    private fun logLifecycle(arm: String, job: UploadJob) {
        val resource = job.request.resource
        log.i { "$arm key=${resource.filename} attempt=${job.attempt}" }
    }

    private fun logWork(arm: String, decision: SyncDecision.Work) {
        val resource = decision.job.request.resource
        log.i { "$arm key=${resource.filename} attempt=${decision.job.attempt}" }
    }

    /** Pure query: read the ledger, mint for `Work`, write nothing (recording is [started]). */
    private suspend fun decide(resource: Resource): SyncDecision {
        val entry = ledger.entry(resource.filename)
        // COMPLETED/UPLOADED/REQUESTED = uploaded or in flight → skip (an uploaded resource is immutable).
        // UPLOADED skips for the same reason COMPLETED does — its bytes ARE stored; what it still owes is
        // the album placement and the notify, which the cycle's promotion pass performs, never a re-upload.
        // FAILED or absent → fresh upload. Only new keys ever upload.
        return when (entry?.state) {
            LedgerState.COMPLETED, LedgerState.UPLOADED, LedgerState.REQUESTED -> SyncDecision.AlreadyUploaded
            LedgerState.FAILED, null -> SyncDecision.Upload(mint(resource, attempt = 0))
        }
    }

    private suspend fun retry(failed: UploadJob): SyncDecision {
        val resource = failed.request.resource
        val job = UploadJob(provider.provide(resource), failed.attempt + 1)
        // Record FAILED only. The retry's REQUESTED is written when the platform reports
        // UploadStarted for the freshly created retry job (write-after-act).
        ledger.recordFailed(resource, failed.attempt, eventId)
        return SyncDecision.Retry(job)
    }

    private suspend fun complete(job: UploadJob): SyncDecision {
        val resource = job.request.resource
        ledger.recordCompleted(resource, job.attempt, eventId)
        return SyncDecision.AlreadyUploaded
    }

    /** The sole site that records REQUESTED: the platform created/retried the job (write-after-act). */
    private suspend fun started(job: UploadJob): SyncDecision {
        val resource = job.request.resource
        ledger.recordRequested(resource, job.attempt, eventId)
        return SyncDecision.AlreadyUploaded
    }

    private suspend fun mint(resource: Resource, attempt: Int): UploadJob =
        UploadJob(provider.provide(resource), attempt)
}
