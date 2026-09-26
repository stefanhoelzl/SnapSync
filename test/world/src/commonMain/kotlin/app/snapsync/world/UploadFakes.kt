package app.snapsync.world

import app.snapsync.model.SelectionPolicy
import app.snapsync.model.Resource
import app.snapsync.model.UploadError
import app.snapsync.model.ChangeOutcome
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadJobSet
import app.snapsync.model.UploadJobState
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadSourceKind
import app.snapsync.model.UploadTarget
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.model.destinationPathOf
import app.snapsync.ports.Discovery
import app.snapsync.ports.Upload
import app.snapsync.ports.UploadHandlers
import app.snapsync.ports.UploadDiscovery
import app.snapsync.ports.GalleryReader
import app.snapsync.services.gallery.GalleryDiscovery
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.isSuccess
import app.snapsync.model.runCatchingCancellable

/** One job the world's uploader was asked to create: the upload key it was tagged with, and the type it declared. */
class CreatedUpload(val filename: String, val contentType: String) {
    /** The photo's id, recovered from the key (`<assetId>-<role>.<ext>`). */
    val assetId: String get() = assetIdFromUploadKey(filename)
}

/**
 * An operator-driven, **inspectable** [Upload] (`docs/testing.md`): the world's stand-in for the platform's upload-job
 * queue, modelled on the PhotoKit tier — jobs the operator drives between cycles, presented in the platform's two
 * sets. It decides nothing: which ledger row a job belongs to and what its end means are the REAL upload service's,
 * composed over it exactly as over a device queue.
 *
 * - `create` enqueues a PENDING job and answers `CREATED`, unless the settable [jobLimit] in-flight cap is reached
 *   (`LIMIT_EXCEEDED`), [failCreate] is set, or the source is not the world's platform handle (`FAILED`) — its photos
 *   carry `Unit`, as a device's carry a `PHAssetResource`.
 * - [completeJob] performs the job's own request — a real `PUT` to the URL, with the headers, the engine minted — over
 *   [network], the network an OS transfer crosses. A `2xx` makes it terminal-succeeded; anything else fails it exactly
 *   as [failJob] would, with the status the backend answered. A completed object is one the chosen backend accepted.
 * - [failJob] fails a job with a chosen [UploadError]. A first failure is offered for its single free retry
 *   ([UploadJobSet.RETRY_OFFERED]); a failure after it is presented as terminal and handed back for re-creation.
 *
 * It serves no library read. The change feed and the key resolve are [FakeUploadDiscovery]'s.
 */
class FakeUpload(
    /** The network an OS transfer crosses: the world's backend's bare client, or a binding's fixture engine. */
    private val network: HttpClient,
) : Upload {

    /** Failure lever: the OS in-flight job cap. `create` answers `LIMIT_EXCEEDED` at/above it. */
    var jobLimit: Int = Int.MAX_VALUE

    /** Failure lever: `create` answers `FAILED` (an unusable payload). */
    var failCreate: Boolean = false

    private val jobs = mutableListOf<FakeJob>()

    /** Inspection: every job created (retry chains visible via repeated keys). */
    val created = mutableListOf<CreatedUpload>()

    override val accepts: UploadSourceKind = UploadSourceKind.RESOURCE

    /** The queue raises no events: its terminal jobs are presented when asked, as PhotoKit's are. */
    override fun listen(handlers: UploadHandlers) = Unit

    private class FakeJob(
        val key: String,
        val contentType: String,
        val data: Any,
        /** The request the OS would perform — replaced by the fresh one a retry hands in. */
        var target: UploadTarget,
    ) {
        var state: UploadJobState = UploadJobState.PENDING
        var error: UploadError? = null
        var retriedOnce: Boolean = false
    }

    private fun FakeJob.view() = UploadJob(
        handle = this,
        tag = key,
        destinationPath = destinationPathOf(target.url),
        contentType = contentType,
        state = state,
        error = error,
        source = UploadSource.Resource(data).takeIf { state != UploadJobState.SUCCEEDED },
    )

    override suspend fun jobs(set: UploadJobSet): List<UploadJob> = when (set) {
        UploadJobSet.RETRY_OFFERED -> jobs.filter { it.state == UploadJobState.FAILED && !it.retriedOnce }
        UploadJobSet.TERMINAL -> jobs.filter {
            it.state == UploadJobState.SUCCEEDED || (it.state == UploadJobState.FAILED && it.retriedOnce)
        }
        UploadJobSet.IN_FLIGHT -> jobs.filter { it.state == UploadJobState.PENDING }
    }.map { it.view() }

    override suspend fun retry(job: UploadJob, target: UploadTarget): ChangeOutcome {
        val j = job.handle as? FakeJob ?: return ChangeOutcome.Refused(null, "not this queue's job")
        j.retriedOnce = true
        j.target = target
        j.state = UploadJobState.PENDING // in-flight again after the single free retry
        j.error = null
        return ChangeOutcome.Applied
    }

    override suspend fun acknowledge(job: UploadJob): ChangeOutcome {
        jobs.remove(job.handle)
        return ChangeOutcome.Applied
    }

    override suspend fun cancel(job: UploadJob): ChangeOutcome {
        jobs.remove(job.handle)
        return ChangeOutcome.Applied
    }

    override suspend fun create(source: UploadSource, target: UploadTarget, tag: String): UploadCreateOutcome {
        if (failCreate) return UploadCreateOutcome.FAILED
        val data = (source as? UploadSource.Resource)?.handle ?: return UploadCreateOutcome.FAILED
        if (data != Unit) return UploadCreateOutcome.FAILED
        if (jobs.size >= jobLimit) return UploadCreateOutcome.LIMIT_EXCEEDED
        val contentType = target.headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
            ?: "application/octet-stream"
        jobs.add(FakeJob(tag, contentType, data, target))
        created.add(CreatedUpload(tag, contentType))
        return UploadCreateOutcome.CREATED
    }

    // ---- operator actions -----------------------------------------------------------------------

    /**
     * Let the "OS" perform a created job: its request goes over [network], and the job settles on what the backend
     * answered — succeeded on a `2xx`, failed with that status otherwise, or with [UploadError.Network] when the request
     * never got an answer.
     */
    suspend fun completeJob(key: String) {
        val j = jobs.firstOrNull { it.key == key && it.state == UploadJobState.PENDING } ?: return
        val status = runCatchingCancellable {
            network.put(j.target.url) {
                headers { j.target.headers.forEach { (name, value) -> append(name, value) } }
                setBody(TRANSFERRED_BYTES)
            }.status
        }.getOrElse {
            j.state = UploadJobState.FAILED
            j.error = UploadError.Network
            return
        }
        if (status.isSuccess()) {
            j.state = UploadJobState.SUCCEEDED
        } else {
            j.state = UploadJobState.FAILED
            j.error = UploadError.Http(status.value)
        }
    }

    /** Fail a created job with a chosen [error], driving the real retry chain next cycle. */
    fun failJob(key: String, error: UploadError) {
        val j = jobs.firstOrNull { it.key == key && it.state == UploadJobState.PENDING } ?: return
        j.state = UploadJobState.FAILED
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
