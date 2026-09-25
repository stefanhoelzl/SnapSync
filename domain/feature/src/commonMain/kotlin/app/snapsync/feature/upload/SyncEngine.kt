package app.snapsync.feature.upload

import app.snapsync.model.LedgerState
import app.snapsync.model.Resource
import app.snapsync.model.destinationPathOf
import app.snapsync.model.SyncDecision
import app.snapsync.model.SyncEvent
import app.snapsync.model.UploadRequest
import app.snapsync.model.UploadRequestProvider
import co.touchlab.kermit.Logger

/**
 * The decision core (spec: background-upload): platforms drive it with [SyncEvent] observations, it
 * answers with [SyncDecision]s. Its only state is the [ledger] — the durable per-key memory of
 * what was requested, completed, and still needs a job. The engine records requests and failures; a
 * completion is recorded by the platform itself, where it is told, through the ledger's guarded terminal
 * write (capability `photo-sharing`).
 *
 * Decision rules ([SyncEvent.ResourceChanged] is a **pure query** — it reads the ledger and mints a
 * request for `Work` answers, but writes nothing): a key is skipped when the ledger holds it
 * `COMPLETED` **or** `REQUESTED` (an uploaded resource is immutable, so a `COMPLETED` key is never
 * re-uploaded; `REQUESTED` means a job is in flight — see write-after-act below); a `DISCOVERED` or
 * absent entry yields `Work`.
 *
 * Write-after-act: the engine changes the ledger only on its two lifecycle observations —
 * [SyncEvent.UploadStarted] → `REQUESTED`, [SyncEvent.UploadFailed] → back to `DISCOVERED` —
 * each an idempotent per-key upsert that never overwrites a settled row (the ledger's guard, not a
 * decision of this engine: a late `UploadFailed` over a `COMPLETED` key still answers `Retry`, and the
 * record is simply declined). Because `REQUESTED` is recorded only *after* the
 * platform reports it created the job, a `REQUESTED` entry always implies a real in-flight job, which
 * is what makes skipping it safe. A provider failure during minting throws before any write, so the
 * event counts as unprocessed; replayed/at-least-once reports converge instead of drifting.
 *
 * Concurrency: at most one [handle] call in flight per engine — all known drivers are
 * sequential loops; a concurrent driver must serialize (or a future slice reintroduces the
 * guarantee it pays for).
 *
 * Policy: **retry forever** — every failure yields [SyncDecision.Retry] with a newly minted request,
 * so expired destinations heal on retry. No attempt budget, no give-up, and so no attempt count
 * (decision record `changes/shrink-the-ledger-row`).
 */
class SyncEngine(
    private val provider: UploadRequestProvider,
    private val ledger: LedgerWriter,
) {

    private val log = Logger.withTag("SyncEngine")

    /**
     * Logging (spec: privacy-security, field diagnostics — the headless iOS extension's only observability):
     * a failure WARNs with its mapped error, every issued [SyncDecision.Work] INFOs its arm + key, and the
     * [SyncEvent.UploadStarted] confirmation INFOs "started". The skip on
     * re-enumeration ([SyncDecision.AlreadyUploaded] for
     * [SyncEvent.ResourceChanged]) is silent — it fires per change-cycle and would drown the signal.
     * Logs are diagnostics, never asserted: the decision methods stay pure, all logging lives here at
     * the dispatch seam.
     */
    suspend fun handle(event: SyncEvent): SyncDecision {
        if (event is SyncEvent.UploadFailed) {
            log.w { "failed key=${event.request.resource.filename} error=${event.error}" }
        }
        val decision = when (event) {
            is SyncEvent.ResourceChanged -> decide(event.resource)
            is SyncEvent.UploadFailed -> retry(event.request)
            is SyncEvent.UploadStarted -> started(event.request)
        }
        when (decision) {
            is SyncDecision.Upload -> logWork("Upload", decision)
            is SyncDecision.Retry -> logWork("Retry", decision)
            SyncDecision.AlreadyUploaded -> when (event) {
                is SyncEvent.UploadStarted -> log.i { "started key=${event.request.resource.filename}" }
                else -> Unit
            }
        }
        return decision
    }

    private fun logWork(arm: String, decision: SyncDecision.Work) {
        log.i { "$arm key=${decision.request.resource.filename}" }
    }

    /**
     * Whether [resource] is work — the answer [handle] of a [SyncEvent.ResourceChanged] would give, WITHOUT
     * minting the request that answer carries. For a caller that only records what the walk found and acts
     * later: minting there built a request (and read the device token) only to throw it away, and the act
     * re-derives it through [handle], which mints the one that is used.
     *
     * The same classification as [decide] ([needsJob]), so the engine stays the one place deciding whether a
     * key uploads. Writes nothing. A `true` answer logs the line [handle]'s `Upload` arm logs, so the device log
     * reads exactly as when the caller asked [handle].
     */
    suspend fun isWork(resource: Resource): Boolean =
        needsJob(ledger.entry(resource.filename)?.state).also { work ->
            if (work) log.i { "Upload key=${resource.filename}" }
        }

    /** Pure query: read the ledger, mint for `Work`, write nothing (recording is [started]). */
    private suspend fun decide(resource: Resource): SyncDecision =
        if (needsJob(ledger.entry(resource.filename)?.state)) {
            SyncDecision.Upload(provider.provide(resource))
        } else {
            SyncDecision.AlreadyUploaded
        }

    /**
     * The one classification of a ledger state: does its key need a job?
     *
     * COMPLETED/REQUESTED = uploaded or in flight → skip (an uploaded resource is immutable).
     * DISCOVERED or absent → fresh upload. DISCOVERED is a row the walk wrote, or one a failure returned,
     * for a resource with nothing in flight, so re-deriving it must answer `Work` exactly as an absent row
     * does — otherwise the state the cycle writes to remember its own backlog would suppress that backlog.
     * Exhaustive with no `else`, so a new state fails to compile until it is classified here.
     */
    private fun needsJob(state: LedgerState?): Boolean = when (state) {
        LedgerState.COMPLETED, LedgerState.REQUESTED -> false
        LedgerState.DISCOVERED, null -> true
    }

    private suspend fun retry(failed: UploadRequest): SyncDecision {
        val resource = failed.resource
        // The retry's credential comes from the store of record: the failure may be the `401` of a token another
        // process has renewed since this process last read it (capability `background-upload`).
        val request = provider.provideForRetry(resource)
        // Return the row to DISCOVERED only. The retry's REQUESTED is written when the platform reports
        // UploadStarted for the freshly created retry job (write-after-act).
        ledger.recordFailed(resource)
        return SyncDecision.Retry(request)
    }

    /** The sole site that records REQUESTED: the platform created/retried the job (write-after-act). */
    private suspend fun started(request: UploadRequest): SyncDecision {
        ledger.recordRequested(request.resource, destinationPathOf(request.url))
        return SyncDecision.AlreadyUploaded
    }
}
