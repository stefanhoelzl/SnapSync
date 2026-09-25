package app.snapsync.model

/**
 * The unit the sync domain transports (spec: background-upload). Constructed by the platform, never by
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
 * What the platform observed (spec: background-upload). The platform drives: it reports observations at
 * its own pace and acts on the [SyncDecision]s the engine answers with. Events are observations,
 * never bookkeeping — reports may arrive more than once (at-least-once delivery is structural:
 * the platform cannot commit its actions and its reports atomically), and the engine's ledger
 * absorbs duplicates per key. On platform backpressure (e.g. iOS `limitExceeded`) the platform
 * simply stops reporting for the cycle — the engine holds nothing in flight.
 */
sealed interface SyncEvent {

    /** A resource exists with this content state (newly discovered, changed, or re-enumerated). */
    class ResourceChanged(val resource: Resource) : SyncEvent

    /** A previously issued upload failed; [request] is the newest retained request for it. */
    class UploadFailed(val request: UploadRequest, val error: UploadError) : SyncEvent

    /**
     * The platform created (or retried) the upload for [request] — reported AFTER the create/retry call
     * succeeds (write-after-act). This is the *only* event that records `REQUESTED`: a
     * [ResourceChanged] decision mints the work but never records, so a `REQUESTED` entry always
     * implies a real in-flight job. A dropped report (created the job, died before reporting)
     * leaves no `REQUESTED`, which the next [ResourceChanged] re-derivation safely re-issues as a
     * bounded, idempotent duplicate — never a stranded key.
     */
    class UploadStarted(val request: UploadRequest) : SyncEvent
}

/**
 * An upload failure, mapped from the platform's raw error at the seam. The policy ignores the
 * distinction (retry forever) — the taxonomy exists for logging.
 */
sealed interface UploadError {
    data object Network : UploadError
    data class Http(val status: Int) : UploadError
    data object Cancelled : UploadError
    data class Unknown(val detail: String) : UploadError
}

/**
 * A complete, executable upload: PUT the resource's bytes to [url] with exactly [headers] — the one unit of
 * platform work (spec: background-upload), carried by the [SyncDecision.Work] arms. Minted by an
 * [UploadRequestProvider]. Carries its [resource] whole so a failed upload can round-trip through
 * [SyncEvent.UploadFailed] and be re-minted without any engine state.
 *
 * Retention rule: the platform must be able to produce the newest request for each platform job on demand —
 * persist the serializable fields, re-attach [Resource.data] on rehydration (minting reads only the string
 * fields; only execution needs the payload).
 *
 * There is no attempt count and no job wrapper: the engine retries forever, so a count would limit nothing,
 * and which platform call executes the work (create, or retry an existing job) is decided by where the work
 * came from, never by a number on it (decision record `changes/shrink-the-ledger-row`, D4).
 */
class UploadRequest(
    val url: String,
    val headers: Map<String, String>,
    val resource: Resource,
)

/**
 * The engine's answer to an event: what, if anything, the platform should do. The arms name
 * their provenance — platforms treat every [Work] identically (execute the request); the
 * distinction exists for logs, the harness journal, and future policy.
 */
sealed interface SyncDecision {

    /** The decision carries a request to execute. */
    sealed interface Work : SyncDecision {
        val request: UploadRequest
    }

    /** Not (provably) uploaded yet — includes re-answers for unconfirmed hopes. */
    class Upload(override val request: UploadRequest) : Work

    /** The answer to a failure: the same resource, a freshly minted request. */
    class Retry(override val request: UploadRequest) : Work

    /**
     * Nothing for the platform to do. Returned when the ledger already proves the content backed
     * up or in flight (a `COMPLETED`/`REQUESTED` entry — an uploaded resource is immutable), and
     * also as the (ignored) answer to the recording-only [SyncEvent.UploadStarted] report.
     */
    data object AlreadyUploaded : SyncDecision
}
