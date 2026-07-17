package app.snapsync.engine

/**
 * The unit the sync domain transports (spec: sync-engine). Constructed by the platform, never by
 * the engine — the sync domain knows only resources; assets live in a later layer above it.
 *
 * [filename] is pure identity: a plain string whose layout belongs to the caller (the future
 * asset layer; iOS composes `<cloudId>-<kind>.<ext>`). How it is represented at the transport
 * — encoding, placement — is the [UploadRequestProvider]'s responsibility.
 *
 * [metadata] is opaque to the engine; the provider turns it into upload headers.
 *
 * [assetId] is the opaque identity of the asset this resource belongs to (several resources of one
 * photo share it). Like [filename] it is pure identity whose layout belongs to the caller (iOS: the
 * asset's `localIdentifier`, normalized; tests/console: any string). The engine carries it through
 * to the ledger but never interprets it — it plays no part in the decision.
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
    val assetId: String,
    val contentType: String,
    val metadata: Map<String, String>,
    val data: Any,
)

/**
 * What the platform observed (spec: sync-engine). The platform drives: it reports observations at
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
 * One unit of platform work (spec: sync-engine), carried by the [SyncDecision.Work] arms.
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

    /** The answer to a failure: the same resource, attempt + 1, freshly minted request. */
    class Retry(override val job: UploadJob) : Work

    /**
     * Nothing for the platform to do. Returned when the ledger already proves the content backed
     * up or in flight (a `COMPLETED`/`REQUESTED` entry — an uploaded resource is immutable), and
     * also as the (ignored) answer to the recording-only [SyncEvent.UploadCompleted] and
     * [SyncEvent.UploadStarted] reports.
     */
    data object AlreadyUploaded : SyncDecision
}
