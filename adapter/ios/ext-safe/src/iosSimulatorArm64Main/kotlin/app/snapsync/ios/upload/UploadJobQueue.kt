@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.ios.upload

import app.snapsync.gallery.PhotoKitResource
import app.snapsync.ports.ChangeOutcome
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.OsUploadJob
import app.snapsync.ports.OsUploadJobHandle
import app.snapsync.ports.OsUploadQueue
import app.snapsync.ports.TransferFailure
import app.snapsync.ports.UploadJobState
import app.snapsync.ports.UploadTarget
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.reflect.KClass

/**
 * The simulator target's binding: a **substituted** OS upload-job queue.
 *
 * Rationale, the measurement and the expiry trigger are on the `expect` declaration. In short: on this
 * host the subsystem is not unscheduled, it is fatal — job creation raises an uncaught ObjC exception from
 * inside PhotoKit and terminates the process — so the substitute is what makes the OS-driven tier runnable
 * here at all.
 */
actual fun uploadJobQueue(log: Logger): OsUploadQueue = SimulatorUploadJobQueue(log)

/**
 * The content type a created job reports when its request declared none — the services' own last resort.
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
    /** The platform state, in the port's vocabulary (`PlatformVocabularyPinTest` pins the SDK's set it maps from). */
    val state: UploadJobState,
    /** The error to carry, where the state is not a success. The real queue answered none for a refused upload. */
    val error: TransferFailure?,
    /**
     * The resource the OS hands back on the job, or `null` when it answers none — as the real queue does for a
     * succeeded job and a retry-spent one, whose live resource the upload services then find by identifier.
     */
    val resource: GalleryResourceHandle? = null,
    /** The `Content-Type` the job's destination request carries — the OS keeps the request, header and all. */
    val contentType: String? = null,
)

/** One job the cycle asked the OS to create during an invocation. */
class CreatedUploadJob(
    val destination: String,
    val headers: Map<String, String>,
    val contentType: String,
    /** True when this job replaces a retry-bucket job rather than being created from discovery. */
    val isRetry: Boolean,
    /** The resource the job uploads — what the OS keeps on the job and answers back with it. */
    val resource: GalleryResourceHandle? = null,
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
 * contract binding constructs a fresh one per clause (`docs/architecture.md`).
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
    internal suspend fun remove(
        destination: String,
    ) = mutex.withLock { finished.removeAll { it.destination == destination } }

    internal suspend fun record(job: CreatedUploadJob): CreateOutcome = mutex.withLock {
        if (created.size >= limit) {
            CreateOutcome.LIMIT_EXCEEDED
        } else {
            created += job
            CreateOutcome.CREATED
        }
    }

    /** How many jobs this cycle has created so far — for the adapter's own log line. */
    internal suspend fun createdCount(): Int = mutex.withLock { created.size }
}

/** The sets the app composes, and the rig's caller plays the OS through. */
object SimulatorUploadJobs : SimulatorJobSets()

/**
 * The substituted queue: the [OsUploadQueue] port over sets whoever plays the OS hands in — presented jobs, the ones
 * created, the in-flight limit. Every decision about a job (its row, its disposition, which offered retry is a key's) is
 * the upload services', exactly as over the real queue.
 *
 * It takes a resource of [payloadType] only — the type boundary the real adapter draws at a PhotoKit resource: a
 * resource of any other type is not a job. A contract binding on a host with no photo library passes its own
 * stand-in's type.
 */
internal class SimulatorUploadJobQueue(
    private val log: Logger,
    private val jobs: SimulatorJobSets,
    private val payloadType: KClass<*>,
) : OsUploadQueue {

    /** The substitute the app composes: the process-wide sets, and a PhotoKit resource as the only usable payload. */
    constructor(log: Logger) : this(log, SimulatorUploadJobs, PhotoKitResource::class)

    override suspend fun retryOffered(): List<OsUploadJob> = presented(SimulatorJobAction.RETRY)

    override suspend fun terminals(): List<OsUploadJob> = presented(SimulatorJobAction.ACKNOWLEDGE)

    private suspend fun presented(action: SimulatorJobAction): List<OsUploadJob> =
        run {
            jobs.inSet(action).map(::jobOf)
        }.also { log.i { "simulated.jobs(set=$action) = ${it.size} job(s)" } }

    override suspend fun create(resource: GalleryResourceHandle, target: UploadTarget): CreateOutcome =
        run body@{
            if (!payloadType.isInstance(resource)) {
                log.w { "create: the resource is not a PhotoKit resource — not creating" }
                return@body CreateOutcome.FAILED
            }
            jobs.record(target.asCreatedJob(isRetry = false, resource))
                .also { log.i { "queue: ${jobs.createdCount()} job(s) created this cycle" } }
        }.also { log.i { "simulated.create(to=${target.url}) = $it" } }

    override suspend fun retry(job: OsUploadJob, target: UploadTarget): ChangeOutcome {
        val offered = (job.handle as? Presented)?.job ?: return ChangeOutcome.Refused(null, "not a presented job")
        jobs.remove(offered.destination)
        jobs.record(target.asCreatedJob(isRetry = true, resource = offered.resource))
        return ChangeOutcome.Applied
    }

    override suspend fun acknowledge(job: OsUploadJob): ChangeOutcome {
        val presented = (job.handle as? Presented)?.job ?: return ChangeOutcome.Refused(null, "not a presented job")
        jobs.remove(presented.destination)
        return ChangeOutcome.Applied
    }

    private fun jobOf(job: FinishedUploadJob) = OsUploadJob(
        handle = Presented(job),
        destinationUrl = job.destination,
        contentType = job.contentType,
        state = job.state,
        failure = job.error,
        resource = job.resource,
    )

    private fun UploadTarget.asCreatedJob(isRetry: Boolean, resource: GalleryResourceHandle?) = CreatedUploadJob(
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

    /** A presented job, as the port hands it back. */
    private class Presented(val job: FinishedUploadJob) : OsUploadJobHandle
}
