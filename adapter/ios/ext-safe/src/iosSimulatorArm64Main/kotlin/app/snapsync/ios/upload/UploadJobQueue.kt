@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.ios.upload

import app.snapsync.gallery.photoKitResourceRole
import app.snapsync.ios.discovery.IosDiscovery
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.UploadError
import app.snapsync.model.UploadRequest
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.model.denormalizeAssetId
import app.snapsync.model.roleFromUploadKey
import app.snapsync.ports.BackgroundTransfer
import app.snapsync.ports.CreateResult
import app.snapsync.ports.Discovery
import app.snapsync.ports.LedgerStore
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
    discovery: IosDiscovery,
    ledger: LedgerStore,
): BackgroundTransfer = SimulatorUploadJobQueue(log, discovery, ledger)

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

/** One job the OS has finished with, as stated by whoever is playing the OS. */
class FinishedUploadJob(
    /** The ledger key — the destination URL's last path segment, exactly as the real adapter reads it. */
    val key: String,
    /** Which fetch set this job is presented in. */
    val action: SimulatorJobAction,
    /** The platform state, in the vocabulary `PlatformVocabularyPinTest` pins against the SDK. */
    val state: PhotoKitJobState,
    /** The error to carry, where the state is not a success. */
    val error: UploadError?,
)

/** One job the cycle asked the OS to create during an invocation. */
class CreatedUploadJob(
    val key: String,
    val destination: String,
    val headers: Map<String, String>,
    val contentType: String,
    /** True when this job replaces a retry-bucket job rather than being created from discovery. */
    val isRetry: Boolean,
)

/**
 * The per-invocation job sets — **the whole of this substitute's state, and it does not outlive a cycle.**
 *
 * The real queue's durability lives outside the app process: `photolibraryd` holds it, and `process()` is
 * handed the current sets and hands back new ones. Keeping the book with whoever plays the OS reproduces
 * that topology rather than inventing a second one, and it means this object cannot drift between cycles,
 * cannot survive a relaunch into a state the ledger disagrees with, and has nothing to serialize.
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
object SimulatorUploadJobs {

    private val mutex = Mutex()
    private var finished: List<FinishedUploadJob> = emptyList()
    private var created: MutableList<CreatedUploadJob> = mutableListOf()
    private var limit: Int = Int.MAX_VALUE

    /** Hand in this invocation's sets. Clears whatever the previous invocation created. */
    suspend fun beginCycle(finished: List<FinishedUploadJob>, jobLimit: Int) = mutex.withLock {
        this.finished = finished
        this.created = mutableListOf()
        this.limit = jobLimit
    }

    /** What the cycle asked to create, in the order it asked. */
    suspend fun createdThisCycle(): List<CreatedUploadJob> = mutex.withLock { created.toList() }

    /**
     * The OS's in-flight job cap in force for this cycle. `createJob` answers `LIMIT_EXCEEDED` at or above
     * it, which is what drives a cap-truncated cycle: creation stops, the cursor is left un-advanced, and
     * the result is `PROCESSING`.
     */
    suspend fun jobLimit(): Int = mutex.withLock { limit }

    internal suspend fun inSet(action: SimulatorJobAction): List<FinishedUploadJob> =
        mutex.withLock { finished.filter { it.action == action } }

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

/**
 * A [BackgroundTransfer] over [SimulatorUploadJobs] — the four job verbs, and nothing else.
 *
 * **Discovery is delegated**, exactly as [IosPhotoKitUploadPlatform] delegates it. The change-token walk,
 * the `PHAsset` fetches and the selection policy's inputs are real platform behaviour that works on this
 * host, and answering them here would throw away the most valuable coverage the host offers.
 *
 * **Ledger adjudication is shared, not re-implemented**: `drainTerminals` applies the same
 * [terminalDisposition] the PhotoKit queue applies, so this host and a device cannot disagree about what a
 * terminal job means. That was the one place a substitute could quietly lie.
 */
private class SimulatorUploadJobQueue(
    private val log: Logger,
    private val discovery: IosDiscovery,
    private val ledger: LedgerStore,
) : BackgroundTransfer {

    override suspend fun discoverResources(sinceToken: ByteArray?, policy: SelectionPolicy): Discovery =
        log.invocation("platform.discoverResources", result = { "${it.candidates.size} candidate(s)" }) {
            discovery.discover(sinceToken, policy)
        }

    // Shared with the device tier: the id-scoped resolve lives in `IosDiscovery` beside the walk, because
    // both are PhotoKit fetches and only the job lifecycle differs.
    override suspend fun resourcesFor(keys: Set<String>): List<Resource> =
        log.invocation("platform.resourcesFor", params = "${keys.size} key(s)", result = { "${it.size} resource(s)" }) {
            discovery.resourcesFor(keys)
        }

    override suspend fun fetchRetryJobs(): List<PlatformUploadJob> =
        log.invocation("platform.fetchRetryJobs", result = { "${it.size} job(s)" }) {
            SimulatorUploadJobs.inSet(SimulatorJobAction.RETRY).map { it.asPlatformJob() }
        }

    /**
     * Record every presented terminal job and return the retry-spent failures the cycle can re-create.
     *
     * There is no acknowledge step: acknowledgement exists so the OS stops presenting a job, and here the
     * caller decides what to present next. Its absence is not a silent simplification — the real queue's
     * un-acknowledged job reappears on the next fetch, which the caller reproduces by presenting it again.
     */
    override suspend fun drainTerminals(): List<PlatformUploadJob> =
        log.invocation("platform.drainTerminals", result = { "${it.size} job(s)" }) {
            SimulatorUploadJobs.inSet(SimulatorJobAction.ACKNOWLEDGE).mapNotNull { job ->
                val resource = resourceForKey(job.key)
                val disposition = terminalDisposition(job.state, resourceIsLive = resource != null)
                if (!ledger.markTerminal(job.key, disposition.ledgerState)) {
                    log.i { "terminal ${job.key} -> ${disposition.ledgerState} applied to no row" }
                }
                if (disposition.reCreate) job.asPlatformJob(resource) else null
            }
        }

    override suspend fun createJob(request: UploadRequest, resource: Resource): CreateResult =
        log.invocation("platform.createJob(key=${resource.filename})", result = { "$it" }) {
            SimulatorUploadJobs.record(request.asCreatedJob(resource.filename, isRetry = false))
                .also { log.i { "queue: ${SimulatorUploadJobs.createdCount()} job(s) created this cycle" } }
        }

    override suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest) =
        log.invocation("platform.retryJob(key=${job.key})") {
            SimulatorUploadJobs.record(request.asCreatedJob(job.key, isRetry = true))
            Unit
        }

    private fun UploadRequest.asCreatedJob(key: String, isRetry: Boolean) = CreatedUploadJob(
        key = key,
        destination = url,
        headers = headers,
        contentType = headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: GENERIC_CONTENT_TYPE,
        isRetry = isRetry,
    )

    private fun FinishedUploadJob.asPlatformJob(resource: PHAssetResource? = resourceForKey(key)) =
        PlatformUploadJob(
            key = key,
            contentType = resource?.uniformTypeIdentifier ?: GENERIC_CONTENT_TYPE,
            error = error,
            data = resource,
        )

    /**
     * Recover the live `PHAssetResource` a ledger key names, or `null` when the asset or the resource is
     * gone.
     *
     * The real queue never needs this: the OS hands the resource back on the job object. Here a job
     * arrives as a plain key, so the resource has to be found again — and it is **load-bearing rather than
     * a convenience**. `drainTerminals` must return retry-spent failures *whose resource is still live* for
     * the cycle to re-create them; a substitute that always answered `null` would take the legal
     * "resource no longer live" branch every time, so the re-create path would degrade **silently** and a
     * scenario asserting it would pass having tested nothing.
     *
     * Key-derived identity is the established idiom here, not a rig invention: the event-album add path
     * already recovers a `PHAsset` from a completed upload's key the same way, and the retried `Resource`
     * is one the cycle rebuilds from the key alone.
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
