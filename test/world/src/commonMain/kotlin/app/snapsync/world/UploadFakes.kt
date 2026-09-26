package app.snapsync.world

import app.snapsync.model.SelectionPolicy
import app.snapsync.model.Resource
import app.snapsync.model.UploadError
import app.snapsync.model.UploadRequest
import app.snapsync.model.CreateResult
import app.snapsync.ports.Discovery
import app.snapsync.ports.PlatformUploadJob
import app.snapsync.ports.BackgroundTransfer
import app.snapsync.ports.TransferRecord
import app.snapsync.ports.UploadDiscovery
import app.snapsync.ports.GalleryReader
import app.snapsync.services.gallery.GalleryDiscovery
import app.snapsync.model.TerminalOutcome
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.isSuccess
import app.snapsync.model.runCatchingCancellable

/**
 * An operator-driven, **inspectable** [BackgroundTransfer] (`docs/testing.md`): the
 * world's stand-in for the iOS `IosBackgroundTransfer`. It models the OS upload-job lifecycle as a
 * queue an operator drives between cycles:
 *
 * - `createJob` enqueues a PENDING job and returns `CREATED`, unless the settable [jobLimit] in-flight
 *   cap is reached (`LIMIT_EXCEEDED`) or [failCreate] is set (`FAILED`).
 * - [completeJob] performs the job's own request — a real `PUT` to the URL, with the headers, the engine
 *   minted — over [network], the network an OS transfer crosses. A `2xx` moves the job to the terminal
 *   bucket, so the next `drainTerminals` records it `COMPLETED`; anything else fails it exactly as [failJob]
 *   would, with the status the backend answered (`docs/testing.md`). There is no
 *   store-direct deposit: a completed object is one the chosen backend itself accepted.
 * - [failJob] moves a job to the retry bucket carrying a chosen [UploadError], driving the real engine
 *   retry chain. A first failure surfaces via `fetchRetryJobs` (the system's single free retry); a
 *   second failure of the same job returns its row to `DISCOVERED` through [drainTerminals] and is handed
 *   back for the cycle to re-create.
 *
 * Like both real adapters, this one RECORDS terminal outcomes into the [ledger] itself rather than
 * handing them up — that is the seam's contract now, and a fake that returned them instead would let a
 * green suite hide the very defect this models.
 *
 * It serves no library read. The change feed and the key resolve are [FakeUploadDiscovery]'s, bound beside
 * this queue exactly as a device root binds `GalleryDiscovery` beside its transport.
 */
class FakeBackgroundTransfer(
    /** The network an OS transfer crosses: the world's backend's bare client, or a binding's fixture engine. */
    private val network: HttpClient,
    /** The same ledger the composed cycle writes — this adapter records terminal outcomes into it. */
    private val ledger: TransferRecord,
) : BackgroundTransfer {

    /** Failure lever: the OS in-flight job cap. `createJob` returns `LIMIT_EXCEEDED` at/above it. */
    var jobLimit: Int = Int.MAX_VALUE

    /** Failure lever: `createJob` returns `FAILED` (a malformed destination / unusable payload). */
    var failCreate: Boolean = false

    private var handleSeq = 0
    private val jobs = mutableListOf<FakeJob>()

    /** Inspection: every resource a job was created for (retry chains visible via repeated keys). */
    val created = mutableListOf<Resource>()

    /**
     * The OS job states this fake models. Private on purpose: the platform-neutral enum moved into the
     * PhotoKit adapter when terminal facts stopped crossing the port, and a test harness has no business
     * depending on one tier's technology vocabulary.
     */
    private enum class FakeJobState { PENDING, SUCCEEDED, FAILED }

    private class FakeJob(
        val key: String,
        val contentType: String,
        val data: Any,
        val handle: Int,
        /** The request the OS would perform — replaced by the fresh one a retry hands in. */
        var request: UploadRequest,
    ) {
        var state: FakeJobState = FakeJobState.PENDING
        var error: UploadError? = null
        var retriedOnce: Boolean = false
    }

    private fun FakeJob.view() = PlatformUploadJob(key, contentType, error, data)

    override suspend fun fetchRetryJobs(): List<PlatformUploadJob> =
        jobs.filter { it.state == FakeJobState.FAILED && !it.retriedOnce }.map { it.view() }

    /**
     * Record what the "OS" has finished, settle it, and hand back only the retry-spent failures.
     *
     * A succeeded job becomes `COMPLETED`, exactly as both adapters record it.
     */
    override suspend fun drainTerminals(): List<PlatformUploadJob> {
        val terminal = jobs.filter {
            it.state == FakeJobState.SUCCEEDED || (it.state == FakeJobState.FAILED && it.retriedOnce)
        }
        val out = mutableListOf<PlatformUploadJob>()
        for (j in terminal) {
            val succeeded = j.state == FakeJobState.SUCCEEDED
            ledger.markTerminal(j.key, if (succeeded) TerminalOutcome.COMPLETED else TerminalOutcome.FAILED)
            if (!succeeded) out += j.view()
        }
        jobs.removeAll(terminal) // settled with the "OS", exactly as both adapters acknowledge in place
        return out
    }

    override suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest) {
        // Matched by key: the seam no longer carries an opaque system handle.
        val j = jobs.firstOrNull { it.key == job.key && it.state == FakeJobState.FAILED } ?: return
        j.retriedOnce = true
        j.request = request
        j.state = FakeJobState.PENDING // in-flight again after the single free retry
        j.error = null
    }

    /**
     * Refuses what a real tier refuses before any job exists (`BackgroundTransferContract`): a payload that is not
     * the world's platform handle — its photos carry `Unit`, as a device's carry a `PHAssetResource` — and a
     * destination that is not a URL. Both answer `FAILED`, so the cycle records no `REQUESTED` for them.
     */
    override suspend fun createJob(request: UploadRequest, resource: Resource): CreateResult {
        if (failCreate) return CreateResult.FAILED
        if (resource.data != Unit || request.url.isBlank()) return CreateResult.FAILED
        if (jobs.size >= jobLimit) return CreateResult.LIMIT_EXCEEDED
        jobs.add(FakeJob(resource.filename, resource.contentType, resource.data, handleSeq++, request))
        created.add(resource)
        return CreateResult.CREATED
    }

    // ---- operator actions -----------------------------------------------------------------------

    /**
     * Let the "OS" perform a created job: its request goes over [network], and the job settles on what the
     * backend answered — acknowledged on a `2xx`, failed with that status otherwise, or with
     * [UploadError.Network] when the request never got an answer.
     */
    suspend fun completeJob(key: String) {
        val j = jobs.firstOrNull { it.key == key && it.state == FakeJobState.PENDING } ?: return
        val status = runCatchingCancellable {
            network.put(j.request.url) {
                headers { j.request.headers.forEach { (name, value) -> append(name, value) } }
                setBody(TRANSFERRED_BYTES)
            }.status
        }.getOrElse {
            j.state = FakeJobState.FAILED
            j.error = UploadError.Network
            return
        }
        if (status.isSuccess()) {
            j.state = FakeJobState.SUCCEEDED
        } else {
            j.state = FakeJobState.FAILED
            j.error = UploadError.Http(status.value)
        }
    }

    /** Fail a created job with a chosen [error], driving the real retry chain next cycle. */
    fun failJob(key: String, error: UploadError) {
        val j = jobs.firstOrNull { it.key == key && it.state == FakeJobState.PENDING } ?: return
        j.state = FakeJobState.FAILED
        j.error = error
    }

    /** Inspection: the keys of every live (in-flight/terminal-unacked) job. */
    fun liveJobKeys(): List<String> = jobs.map { it.key }

    private companion object {
        /** A minimal JPEG: the world's photos carry no bytes, and no backend reads these back. */
        val TRANSFERRED_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
    }
}

/**
 * The world's rigging around the cycle's two library reads: the same `GalleryDiscovery` service the device
 * roots compose, over the world's gallery, plus the operator's lever and the inspection a test uses to tell a
 * cycle that walked from one that enqueued from the ledger.
 *
 * Nothing here answers differently from the service except the one lever, [makeWalkUnreadable], which answers
 * the next walk the way a device answers a library it could not read.
 */
class FakeUploadDiscovery(gallery: GalleryReader) : UploadDiscovery {

    private val honest: UploadDiscovery = GalleryDiscovery(gallery)

    /** Every key ever asked for, counted with repeats (see [resourcesFor]). */
    var resolvedKeyCount = 0

    private var unreadable = false

    /** Inspection: how many times the discovery feed was consumed — 0 proves a cycle enqueued from the ledger. */
    var discoverCalls = 0
        private set

    /** Inspection: every ledger key the cycle asked this fake to resolve. */
    val resolvedKeys = mutableSetOf<String>()

    /**
     * Resolve ledger keys through the honest fake, **observably**: [resolvedKeys] is how a test asserts that
     * a cycle enqueued from the ledger rather than from the discovery feed (capability `photo-sharing`).
     */
    override suspend fun resourcesFor(keys: Set<String>): List<Resource> {
        resolvedKeys += keys
        // How many keys were resolved in total, not how many distinct ones — the surplus this bound exists
        // to remove is repeated work on rows the platform was never going to take, and a set hides it.
        resolvedKeyCount += keys.size
        return honest.resourcesFor(keys)
    }

    override suspend fun discover(policy: SelectionPolicy): Discovery {
        discoverCalls++
        if (unreadable) {
            unreadable = false
            // What a device answers for a library it could not read: nothing, and NOT authoritative — so the
            // cycle deletes nothing on the strength of an empty answer (capability `photo-sharing`).
            return Discovery(candidates = emptyList(), fullEnumeration = false)
        }
        return honest.discover(policy)
    }

    // ---- operator actions -----------------------------------------------------------------------

    /**
     * Make the next walk unreadable: no candidates, and not authoritative — the case the cycle's deletion gate
     * exists for (`docs/testing.md`).
     */
    fun makeWalkUnreadable() {
        unreadable = true
    }
}
