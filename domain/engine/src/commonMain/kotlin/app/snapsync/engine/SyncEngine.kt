package app.snapsync.engine

import co.touchlab.kermit.Logger

/**
 * The unit the sync domain transports (design.md §2.2). Constructed by the platform, never by
 * the engine — the sync domain knows only resources; assets live in a later layer above it.
 *
 * [filename] is pure identity: a plain string whose layout belongs to the caller (the future
 * asset layer; iOS composes `<cloudId>-<kind>.<ext>`). How it is represented at the transport
 * — encoding, placement — is the [UploadRequestProvider]'s responsibility.
 *
 * [version] is the platform's proof of content identity (iOS: the asset's modification date;
 * tests and the console: any string). The engine compares versions for equality only, never
 * parses them — equal means "the bytes the ledger remembers are the bytes you'd upload now".
 *
 * [metadata] is opaque to the engine; the provider turns it into upload headers.
 *
 * [data] is the opaque platform payload backing this resource (iOS: `PHAssetResource`; tests:
 * bytes) — always present, and never read by the engine or the provider: only the platform
 * that wrote it reads it back, at its own execution edge. Deliberately `Any`, not a generic —
 * a type parameter would infect every seam type while the engine never reads it, and erases to
 * `id` in the exported ObjC header anyway. It is the one non-serializable field: platforms
 * that persist jobs re-attach the payload on rehydration.
 */
class Resource(
    val filename: String,
    val contentType: String,
    val version: String,
    val metadata: Map<String, String>,
    val data: Any,
)

/**
 * What the platform observed (design.md §2.2). The platform drives: it reports observations at
 * its own pace and acts on the [SyncDecision]s the engine answers with. Events are observations,
 * never bookkeeping — reports may arrive more than once (at-least-once delivery is structural:
 * the platform cannot commit its actions and its reports atomically), and the engine's ledger
 * absorbs duplicates per key. On platform backpressure (e.g. iOS `limitExceeded`) the platform
 * simply stops reporting for the cycle — the engine holds nothing in flight.
 */
sealed interface SyncEvent {

    /** A resource exists with this content state (newly discovered, changed, or re-enumerated). */
    class ResourceChanged(val resource: Resource) : SyncEvent

    /** A previously issued upload failed; [job] is the newest retained job for it. */
    class UploadFailed(val job: UploadJob, val error: UploadError) : SyncEvent

    /**
     * A previously issued upload was observed to have succeeded; [job] is the newest retained
     * job for it. Reported at the platform's acknowledge edge, BEFORE acknowledging — the
     * write-then-act ordering makes the report duplicable rather than losable.
     */
    class UploadCompleted(val job: UploadJob) : SyncEvent

    /**
     * The platform created (or retried) the upload [job] — reported AFTER the create/retry call
     * succeeds (write-after-act). This is the *only* event that records `REQUESTED`: a
     * [ResourceChanged] decision mints the work but never records, so a `REQUESTED` entry always
     * implies a real in-flight job. A dropped report (created the job, died before reporting)
     * leaves no `REQUESTED`, which the next [ResourceChanged] re-derivation safely re-issues as a
     * bounded, idempotent duplicate — never a stranded key.
     */
    class UploadStarted(val job: UploadJob) : SyncEvent
}

/**
 * An upload failure, mapped from the platform's raw error at the seam. v1 policy ignores the
 * distinction (retry forever) — the taxonomy exists for logging today and for a future
 * attempt-budget policy.
 */
sealed interface UploadError {
    data object Network : UploadError
    data class Http(val status: Int) : UploadError
    data object Cancelled : UploadError
    data class Unknown(val detail: String) : UploadError
}

/**
 * A complete, executable upload: PUT the resource's bytes to [url] with exactly [headers].
 * Minted by an [UploadRequestProvider]. Carries its [resource] whole so a failed upload can
 * round-trip through [SyncEvent.UploadFailed] and be re-minted without any engine state.
 */
class UploadRequest(
    val url: String,
    val headers: Map<String, String>,
    val resource: Resource,
)

/**
 * One unit of platform work (design.md §2.2), carried by the [SyncDecision.Work] arms.
 *
 * [attempt] discriminates execution: `0` → create a new platform upload job; `> 0` → retry the
 * existing one (or acknowledge-and-recreate it, the platform's choice).
 *
 * Retention rule: the platform must be able to produce the newest [UploadJob] for each platform
 * job on demand — persist the serializable fields, re-attach [Resource.data] on rehydration
 * (minting reads only the string fields; only execution needs the payload).
 */
class UploadJob(
    val request: UploadRequest,
    val attempt: Int,
)

/**
 * The engine's answer to an event: what, if anything, the platform should do. The arms name
 * their provenance — platforms treat every [Work] identically (execute the job); the
 * distinction exists for logs, the harness journal, and future policy.
 */
sealed interface SyncDecision {

    /** The decision carries a job to execute. */
    sealed interface Work : SyncDecision {
        val job: UploadJob
    }

    /** Not (provably) uploaded yet — includes re-answers for unconfirmed hopes. */
    class Upload(override val job: UploadJob) : Work

    /** Uploaded before, but the content changed ([Resource.version] differs). */
    class ReUpload(override val job: UploadJob) : Work

    /** The answer to a failure: the same resource, attempt + 1, freshly minted request. */
    class Retry(override val job: UploadJob) : Work

    /**
     * Nothing for the platform to do. Returned when the ledger already proves the content backed
     * up or in flight (a `COMPLETED`/`REQUESTED` entry at the same version), and also as the
     * (ignored) answer to the recording-only [SyncEvent.UploadCompleted] and [SyncEvent.UploadStarted]
     * reports.
     */
    data object AlreadyUploaded : SyncDecision
}

/**
 * The engine's request-minting seam (design.md §2.2): mints the executable request for a
 * resource. Implementations: S3 presigner, dumb-HTTP test provider.
 *
 * Contract:
 * - `resource.filename → destination` is **deterministic and injective** — this is where
 *   upload idempotency lives. Encoding and placement (a `photos/` path prefix, or carrying
 *   identity as a header on transports that do) are the provider's alone.
 * - The returned request carries the **same [Resource] instance** it was given.
 *   [Resource.data] is never read.
 * - Failures are thrown, never masked — the engine doesn't catch (the event counts as
 *   unprocessed, the ledger is left untouched, and re-handling is safe).
 * - The provider is invoked only for [SyncDecision.Work] answers — never when the engine
 *   skips ([SyncDecision.AlreadyUploaded]).
 */
interface UploadRequestProvider {
    suspend fun provide(resource: Resource): UploadRequest
}

/**
 * The decision core (design.md §2.2): platforms drive it with [SyncEvent] observations, it
 * answers with [SyncDecision]s. Its only state is the [ledger] — the durable per-key memory of
 * what was requested, completed, and failed, written exclusively by this engine.
 *
 * Decision rules ([SyncEvent.ResourceChanged] is a **pure query** — it reads the ledger and mints a
 * request for `Work` answers, but writes nothing): a key is skipped when the ledger holds it
 * `COMPLETED` **or** `REQUESTED` at the same [Resource.version] (`REQUESTED` means a job is in
 * flight — see write-after-act below); a `FAILED` or absent entry, or any differing version, yields
 * `Work`.
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
) {

    private val log = Logger.withTag("SyncEngine")

    /**
     * Logging (design.md §7, field diagnostics — the headless iOS extension's only observability):
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
            is SyncDecision.ReUpload -> logWork("ReUpload", decision)
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
        val sameVersion = entry?.version == resource.version
        // COMPLETED/REQUESTED = backed up or in flight → skip if unchanged, else supersede.
        // FAILED or absent → fresh upload.
        return when (entry?.state) {
            LedgerState.COMPLETED, LedgerState.REQUESTED ->
                if (sameVersion) SyncDecision.AlreadyUploaded else SyncDecision.ReUpload(mint(resource, attempt = 0))
            LedgerState.FAILED, null -> SyncDecision.Upload(mint(resource, attempt = 0))
        }
    }

    private suspend fun retry(failed: UploadJob): SyncDecision {
        val resource = failed.request.resource
        val job = UploadJob(provider.provide(resource), failed.attempt + 1)
        // Record FAILED only. The retry's REQUESTED is written when the platform reports
        // UploadStarted for the freshly created retry job (write-after-act).
        ledger.recordFailed(resource.filename, failed.attempt, resource.version)
        return SyncDecision.Retry(job)
    }

    private suspend fun complete(job: UploadJob): SyncDecision {
        val resource = job.request.resource
        ledger.recordCompleted(resource.filename, job.attempt, resource.version)
        return SyncDecision.AlreadyUploaded
    }

    /** The sole site that records REQUESTED: the platform created/retried the job (write-after-act). */
    private suspend fun started(job: UploadJob): SyncDecision {
        val resource = job.request.resource
        ledger.recordRequested(resource.filename, job.attempt, resource.version)
        return SyncDecision.AlreadyUploaded
    }

    private suspend fun mint(resource: Resource, attempt: Int): UploadJob =
        UploadJob(provider.provide(resource), attempt)
}
