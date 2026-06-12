package app.snapsync.sync

/**
 * The unit the sync domain transports (design.md §2.2). Constructed by the platform, never by
 * the engine — the sync domain knows only resources; assets live in a later layer above it.
 *
 * [filename] is pure identity: a plain string whose layout belongs to the caller (the future
 * asset layer; iOS composes `<cloudId>-<kind>.<ext>`). How it is represented at the transport
 * — encoding, placement — is the [UploadRequestProvider]'s responsibility.
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
    val metadata: Map<String, String>,
    val data: Any,
)

/**
 * What the platform observed (design.md §2.2). The platform drives: it submits events at its
 * own pace and executes the [UploadJob]s the engine answers with. On platform backpressure
 * (e.g. iOS `limitExceeded`) it simply stops submitting for the cycle — the engine holds
 * nothing in flight.
 */
sealed interface SyncEvent {

    /** A resource needs backing up (newly discovered or changed). */
    class ResourceChanged(val resource: Resource) : SyncEvent

    /** A previously issued upload failed; [job] is the newest retained job for it. */
    class UploadFailed(val job: UploadJob, val error: UploadError) : SyncEvent
}

/**
 * An upload failure, mapped from the platform's raw error at the seam. v1 policy ignores the
 * distinction (retry forever) — the taxonomy exists for logging today and for a future
 * attempt-budget policy.
 */
sealed interface UploadError {
    data object Network : UploadError
    class Http(val status: Int) : UploadError
    data object Cancelled : UploadError
    class Unknown(val detail: String) : UploadError
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
 * The engine's answer to an event (design.md §2.2): one unit of platform work.
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
 * The engine's single dependency seam (design.md §2.2): mints the executable request for a
 * resource. Implementations: S3 presigner, dumb-HTTP test provider.
 *
 * Contract:
 * - `resource.filename → destination` is **deterministic and injective** — this is where
 *   upload idempotency lives. Encoding and placement (a `photos/` path prefix, or carrying
 *   identity as a header on transports that do) are the provider's alone.
 * - The returned request carries the **same [Resource] instance** it was given.
 *   [Resource.data] is never read.
 * - Must tolerate concurrent [provide] calls.
 * - Failures are thrown, never masked — the engine doesn't catch (the event counts as
 *   unprocessed and may be re-handled safely).
 */
interface UploadRequestProvider {
    suspend fun provide(resource: Resource): UploadRequest
}

/**
 * The stateless decision core (design.md §2.2): platforms drive it with [SyncEvent]s, it
 * answers with [UploadJob]s to execute.
 *
 * Statelessness is the contract: no ledger, no store, no discovery — every answer derives only
 * from the event itself, so **re-handling any event is always safe** (the provider's injective
 * filename→destination mapping makes duplicate executions idempotent overwrites). [handle] may
 * be called concurrently; the engine is exactly as thread-safe as its provider. Provider
 * failures propagate — an event whose handling threw counts as unprocessed.
 *
 * Policy (v1): **retry forever** — every failure yields a fresh job with a newly minted
 * request, so expired destinations heal on retry. No attempt budget, no give-up.
 */
class SyncEngine(private val provider: UploadRequestProvider) {

    suspend fun handle(event: SyncEvent): UploadJob = when (event) {
        is SyncEvent.ResourceChanged ->
            UploadJob(provider.provide(event.resource), attempt = 0)

        is SyncEvent.UploadFailed ->
            UploadJob(provider.provide(event.job.request.resource), attempt = event.job.attempt + 1)
    }
}
