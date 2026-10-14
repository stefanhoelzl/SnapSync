package app.snapsync.rig

import app.snapsync.ios.registry.SimulatorExtensionRecord
import app.snapsync.ios.upload.CreatedUploadJob
import app.snapsync.ios.upload.FinishedUploadJob
import app.snapsync.ios.upload.SimulatorJobAction
import app.snapsync.ios.upload.SimulatorUploadJobs
import app.snapsync.levers.ExtensionRecordState
import app.snapsync.levers.FinishedJob
import app.snapsync.levers.JobCycle
import app.snapsync.levers.PlayedJob
import app.snapsync.levers.UploadJobLevers
import app.snapsync.mock.MockedSystem
import app.snapsync.ports.TransferFailure
import app.snapsync.ports.UploadJobState
import kotlinx.serialization.json.Json

/**
 * The simulator target: **the caller plays the OS**, so this invocation's job sets arrive in the request
 * body and the jobs the cycle creates leave in the response.
 *
 * The queue holds nothing between invocations, and that is the point rather than a simplification. The real
 * queue's durability lives outside the app process — `photolibraryd` holds it, and `process()` is handed the
 * current sets and hands back new ones — so keeping the book with whoever plays the OS reproduces that
 * topology instead of inventing a second one. It also means a relaunch cannot leave a queue disagreeing
 * with the durable ledger, and there is nothing here to serialize or migrate.
 *
 * The vocabulary is the **platform's own** (`JobState`, `JobAction`): `UploadJobState`'s cases are held against the
 * Photos klib by `:test:architecture`'s platform-vocabulary pin, so a case Apple adds fails the Kotlin bump rather than
 * reaching a scenario untaught. A caller playing the OS speaks the OS's terms.
 */

internal actual fun uploadJobService(world: MockWorld, process: () -> Int): HostService<UploadJobLevers> {
    // Where the launch mocks the queue, its typed service (`upload-queue`) plays it, and nothing here may race it.
    val queueMocked = world.isMocked(MockedSystem.UPLOAD_QUEUE)
    return uploadJobService(
        process = if (queueMocked) {
            refusing(QUEUE_MOCKED)
        } else {
            honoured { finished, jobLimit ->
                SimulatorUploadJobs.beginCycle(finished.map { it.platform() }, jobLimit = jobLimit ?: Int.MAX_VALUE)
                cycleOf(runExtensionCycle(process))
            }
        },
        perform = if (queueMocked) refusing(QUEUE_MOCKED) else honoured { job, fail -> performUploadJob(job, fail) },
        extensionRecord = if (world.isMocked(MockedSystem.EXTENSION_REGISTRY)) {
            refusing("the extension's registration is mocked on this launch; its record is the mock's")
        } else {
            honoured { registered, failNextWith -> extensionRecord(registered, failNextWith) }
        },
    )
}

/** A cycle the string entry runs: no finished jobs, the cap off — the OS handing in an empty queue. */
internal actual suspend fun beginStringCycle() = SimulatorUploadJobs.beginCycle(emptyList(), jobLimit = Int.MAX_VALUE)

internal actual suspend fun endStringCycle(raw: Int): String = json.encodeToString(JobCycle.serializer(), cycleOf(raw)) + "\n"

/**
 * The jobs this cycle created, **with their destination URL and headers verbatim**.
 *
 * Verbatim rather than summarised, because that is the one thing this host can prove that nothing else
 * can: the exact string the edge-URL builder composed, next to the backend's real answer to it. The
 * builder is pinned by `commonMain` tests on string-building alone, so until now nothing anywhere
 * demonstrated that a URL it composes is one the backend accepts.
 */
private suspend fun cycleOf(raw: Int): JobCycle = JobCycle(
    raw = raw,
    result = processingResultName(raw),
    jobLimit = SimulatorUploadJobs.jobLimit(),
    created = SimulatorUploadJobs.createdThisCycle().map { it.typed() },
)

private fun CreatedUploadJob.typed() = PlayedJob(destination, contentType, isRetry, headers)

private fun FinishedJob.platform() = FinishedUploadJob(
    destination = destination,
    action = SimulatorJobAction.valueOf(action.name),
    state = UploadJobState.valueOf(state.name),
    error = error?.let(::TransferFailure),
    contentType = contentType,
)

/**
 * The registration levers — what makes the disable→enable ritual's own contract drivable.
 *
 * Two scenarios it exists for, neither reachable anywhere before. **A stale record**: plant one
 * (`registered=true`) and the leading disable reports that a record existed and was removed, which is the
 * evidence the ritual is built on. **A refused enable**: arm `failNextWith=3202` and the enable fails
 * exactly as it does against a record a differently-signed build left behind — after which, on a device,
 * the OS never launches the extension and nothing else reports it. `0` disarms it.
 *
 * The failure is **one-shot**, because the ritual is a pair: a sticky lever would fail both halves and
 * could not express "the disable succeeded and the enable did not", which is the interesting case.
 */
private fun extensionRecord(registered: Boolean?, failNextWith: Long?): ExtensionRecordState {
    registered?.let { SimulatorExtensionRecord.setRegistered(it) }
    failNextWith?.let { SimulatorExtensionRecord.failNextWith(it.takeIf { code -> code != 0L }) }
    return ExtensionRecordState(SimulatorExtensionRecord.registered, SimulatorExtensionRecord.failNextWith)
}

private const val QUEUE_MOCKED = "the upload-job queue is mocked on this launch — its typed service (`upload-queue`) plays it"

private val json = Json { prettyPrint = true }
