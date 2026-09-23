@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.ios.upload

import app.snapsync.gallery.photoKitResourceRole
import app.snapsync.model.Resource
import app.snapsync.model.UploadError
import app.snapsync.model.UploadRequest
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.model.destinationPathOf
import app.snapsync.model.denormalizeAssetId
import app.snapsync.model.roleFromUploadKey
import app.snapsync.ports.BackgroundTransfer
import app.snapsync.ports.CreateResult
import app.snapsync.ports.TransferRecord
import app.snapsync.ports.PlatformUploadJob
import app.snapsync.logging.invocation
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Photos.PHAsset
import platform.Photos.PHAssetResource

/**
 * The simulator target's binding: a **substituted** OS upload-job queue.
 *
 * Rationale, the measurement and the expiry trigger are on the `expect` declaration. In short: on this
 * host the subsystem is not unscheduled, it is fatal — job creation raises an uncaught ObjC exception from
 * inside PhotoKit and terminates the process — so the substitute is what makes the OS-driven tier runnable
 * here at all.
 */
actual fun uploadJobQueue(
    log: Logger,
    ledger: TransferRecord,
): BackgroundTransfer = SimulatorUploadJobQueue(log, ledger)

/**
 * The third answer for a content type, matching `photoKitContentType`'s own last resort: the request's
 * header first, the resource's uniform type identifier second, this third.
 */
private const val GENERIC_CONTENT_TYPE: String = "application/octet-stream"

/**
 * Which set the OS is presenting a job in — `PHAssetResourceUploadJobAction`'s two values, named
 * neutrally so a caller states them without naming Apple's constants.
 */
enum class SimulatorJobAction { RETRY, ACKNOWLEDGE }

/**
 * One job the OS has finished with, as stated by whoever is playing the OS.
 *
 * A job is identified by its [destination] — the URL it was created (or last re-pointed) with — because that is the
 * only field the real queue reliably answers for every job, and the one the real adapter resolves a job's ledger row
 * by (`TransferRecord.entryForDestination`). A job the OS presents in BOTH sets — a first failure is, measured on an
 * SE2 (iOS 26.6) — is stated once per set.
 */
class FinishedUploadJob(
    /** The destination URL the job was created or re-pointed with. */
    val destination: String,
    /** Which fetch set this job is presented in. */
    val action: SimulatorJobAction,
    /** The platform state, in the vocabulary `PlatformVocabularyPinTest` pins against the SDK. */
    val state: PhotoKitJobState,
    /** The error to carry, where the state is not a success. The real queue answered none for a refused upload. */
    val error: UploadError?,
    /**
     * The resource the OS hands back on the job, or `null` to fetch it by identifier. The real queue answers the
     * job's own resource; a caller playing the OS who kept it passes it on.
     */
    val resource: Any? = null,
    /** The `Content-Type` the job's destination request carries — the OS keeps the request, header and all. */
    val contentType: String? = null,
)

/** One job the cycle asked the OS to create during an invocation. */
class CreatedUploadJob(
    val key: String,
    val destination: String,
    val headers: Map<String, String>,
    val contentType: String,
    /** True when this job replaces a retry-bucket job rather than being created from discovery. */
    val isRetry: Boolean,
    /** The resource the job uploads — what the OS keeps on the job and answers back with it. */
    val resource: Any? = null,
)

/**
 * The per-invocation job sets — **the whole of this substitute's state, and it does not outlive a cycle.**
 *
 * The real queue's durability lives outside the app process: `photolibraryd` holds it, and `process()` is
 * handed the current sets and hands back new ones. Keeping the book with whoever plays the OS reproduces
 * that topology rather than inventing a second one, and it means this object cannot drift between cycles,
 * cannot survive a relaunch into a state the ledger disagrees with, and has nothing to serialize.
 *
 * Within an invocation the sets change as the real ones do: a job acknowledged or re-pointed leaves BOTH sets
 * (measured on an SE2, iOS 26.6: acknowledging a failed-once job removes it from the retry set too).
 *
 * The process-wide [SimulatorUploadJobs] is the one the app composes and the rig's caller plays the OS through; a
 * contract binding constructs a fresh one per clause (capability `port-contracts`).
 *
 * ## Every accessor is guarded, and that is not defensive
 *
 * An earlier version of this object said it needed no guarding, "because a cycle is invoked on one lane and
 * runs to completion before the next begins". **That is false, and it was measured false.** The cycle runs
 * under the extension root's own `Dispatchers.Default` scope, so which thread `createJob` lands on is not
 * something the caller that began the cycle controls. On 2026-08-26 the first run of three answered with an
 * empty `created` list while the adapter's own log showed three jobs created in that same cycle; the two
 * runs after it were correct.
 *
 * An intermittently empty answer is worse than a consistently wrong one: it reads as "the cycle created
 * nothing", which is a legitimate outcome, so a scenario would record a passing run that tested nothing.
 * Every accessor therefore takes the mutex, and the writes a cycle makes are published to whatever thread
 * reads them afterwards.
 */
open class SimulatorJobSets {

    private val mutex = Mutex()
    private val finished: MutableList<FinishedUploadJob> = mutableListOf()
    private var created: MutableList<CreatedUploadJob> = mutableListOf()
    private var limit: Int = Int.MAX_VALUE

    /** Hand in this invocation's sets. Clears whatever the previous invocation created. */
    suspend fun beginCycle(finished: List<FinishedUploadJob>, jobLimit: Int) = mutex.withLock {
        this.finished.clear()
        this.finished += finished
        this.created = mutableListOf()
        this.limit = jobLimit
    }

    /** Present one more finished job, as the OS does when a job it was running ends mid-invocation. */
    suspend fun present(job: FinishedUploadJob) = mutex.withLock { finished += job }

    /** What the cycle asked to create, in the order it asked. */
    suspend fun createdThisCycle(): List<CreatedUploadJob> = mutex.withLock { created.toList() }

    /**
     * The in-flight cap this invocation was handed. Reported back so a caller can tell a cap-truncated cycle from
     * one that simply had little to do.
     */
    suspend fun jobLimit(): Int = mutex.withLock { limit }

    internal suspend fun inSet(action: SimulatorJobAction): List<FinishedUploadJob> =
        mutex.withLock { finished.filter { it.action == action } }

    /** The job at [destination] leaves every set — acknowledged, or re-pointed. */
    internal suspend fun remove(destination: String) = mutex.withLock { finished.removeAll { it.destination == destination } }

    internal suspend fun record(job: CreatedUploadJob): CreateResult = mutex.withLock {
        if (created.size >= limit) {
            CreateResult.LIMIT_EXCEEDED
        } else {
            created += job
            CreateResult.CREATED
        }
    }

    /** How many jobs this cycle has created so far — for the adapter's own log line. */
    internal suspend fun createdCount(): Int = mutex.withLock { created.size }
}

/** The sets the app composes, and the rig's caller plays the OS through. */
object SimulatorUploadJobs : SimulatorJobSets()

/**
 * The substituted queue: the real adapter's decisions — row by recorded destination ([jobRowOf]), terminal
 * disposition, acknowledgement of every presented job — over sets whoever plays the OS hands in.
 *
 * [usablePayload] is the payload-type boundary the real adapter draws at `PHAssetResource`: a payload it rejects is
 * not a job. A contract binding on a host with no photo library passes its own resource stand-in here, as the
 * world's transfer double does.
 */
internal class SimulatorUploadJobQueue(
    private val log: Logger,
    private val ledger: TransferRecord,
    private val jobs: SimulatorJobSets = SimulatorUploadJobs,
    private val usablePayload: (Any?) -> Boolean = { it is PHAssetResource },
) : BackgroundTransfer {

    override suspend fun fetchRetryJobs(): List<PlatformUploadJob> =
        log.invocation("platform.fetchRetryJobs", result = { "${it.size} job(s)" }) {
            jobs.inSet(SimulatorJobAction.RETRY).mapNotNull { job ->
                when (val row = rowFor(job)) {
                    is JobRow.Found -> job.asPlatformJob(row.key)
                    // Answered here, never handed to the cycle — as the real adapter does.
                    JobRow.Pruned, JobRow.Unmappable -> { jobs.remove(job.destination); null }
                }
            }
        }

    /**
     * Record every terminal job into the ledger, acknowledge it, and hand up only retry-spent failures whose
     * resource is live — the real adapter's `drainTerminals`, over the handed-in set.
     */
    override suspend fun drainTerminals(): List<PlatformUploadJob> =
        log.invocation("platform.drainTerminals", result = { "${it.size} job(s)" }) {
            val out = ArrayList<PlatformUploadJob>()
            for (job in jobs.inSet(SimulatorJobAction.ACKNOWLEDGE)) {
                val row = rowFor(job)
                if (row is JobRow.Found) {
                    val resource = resourceOf(job, row.key)
                    val disposition = terminalDisposition(job.state, resourceIsLive = resource != null)
                    if (!ledger.markTerminal(row.key, disposition.outcome)) {
                        log.i { "terminal ${row.key} -> ${disposition.outcome} applied to no row" }
                    }
                    if (disposition.reCreate) out += job.asPlatformJob(row.key, resource)
                }
                jobs.remove(job.destination)
            }
            out
        }

    override suspend fun createJob(request: UploadRequest, resource: Resource): CreateResult =
        log.invocation("platform.createJob(key=${resource.filename})", result = { "$it" }) {
            if (!usablePayload(resource.data)) {
                log.w { "createJob: resource payload is not a PHAssetResource — not creating" }
                return@invocation CreateResult.FAILED
            }
            if (!isUploadDestination(request.url)) {
                log.w { "createJob: malformed destination URL — not creating" }
                return@invocation CreateResult.FAILED
            }
            jobs.record(request.asCreatedJob(resource.filename, isRetry = false, resource.data))
                .also { log.i { "queue: ${jobs.createdCount()} job(s) created this cycle" } }
        }

    override suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest) =
        log.invocation("platform.retryJob(key=${job.key})") {
            val offered = jobs.inSet(SimulatorJobAction.RETRY).firstOrNull { (rowFor(it) as? JobRow.Found)?.key == job.key }
            if (offered == null) {
                log.w { "retryJob: no live .retry job for ${job.key} — it settled underneath us" }
                return@invocation
            }
            jobs.remove(offered.destination)
            jobs.record(request.asCreatedJob(job.key, isRetry = true, job.data ?: offered.resource))
            Unit
        }

    private suspend fun rowFor(job: FinishedUploadJob): JobRow {
        val path = destinationPathOf(job.destination)
        return jobRowOf(path, ledger.entryForDestination(path)?.key)
    }

    private fun UploadRequest.asCreatedJob(key: String, isRetry: Boolean, resource: Any?) = CreatedUploadJob(
        key = key,
        destination = url,
        headers = headers,
        contentType = headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: GENERIC_CONTENT_TYPE,
        isRetry = isRetry,
        resource = resource,
    )

    private fun FinishedUploadJob.asPlatformJob(key: String, resource: Any? = resourceOf(this, key)) =
        PlatformUploadJob(
            key = key,
            contentType = jobContentType(contentType, (resource as? PHAssetResource)?.uniformTypeIdentifier),
            error = error,
            data = resource,
        )

    /** The job's own resource where the OS player kept it; otherwise fetched by identifier (see [resourceForKey]). */
    private fun resourceOf(job: FinishedUploadJob, key: String): Any? =
        if (job.state == PhotoKitJobState.SUCCEEDED) null else job.resource ?: resourceForKey(key)

    /**
     * The live resource the real OS would have handed back on the job object, fetched by identifier.
     *
     * A stand-in for a job field, not discovery (capability `ios-photokit-upload`: a substituted queue MAY fetch it
     * by identifier, and SHALL NOT route it through `UploadDiscovery`). `null` when the asset has left the library.
     */
    private fun resourceForKey(key: String): PHAssetResource? {
        val localId = denormalizeAssetId(assetIdFromUploadKey(key))
        val role = roleFromUploadKey(key)
        val asset = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(localId), null).firstObject() as? PHAsset
            ?: return null
        return PHAssetResource.assetResourcesForAsset(asset)
            .filterIsInstance<PHAssetResource>()
            .firstOrNull { photoKitResourceRole(it.type) == role }
    }
}
