@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.snapsync.ios.upload

import app.snapsync.contracts.BackgroundTransferContract
import app.snapsync.contracts.BackgroundTransferState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FixtureAnswer
import app.snapsync.contracts.FixtureObjects
import app.snapsync.contracts.Host
import app.snapsync.contracts.Landed
import app.snapsync.contracts.TransferFixture
import app.snapsync.contracts.TransferUnderTest
import app.snapsync.contracts.runEntry
import app.snapsync.contracts.verify
import app.snapsync.engine.iosLedgerStore
import app.snapsync.model.LedgerState
import app.snapsync.model.Resource
import app.snapsync.model.toLedgerRow
import app.snapsync.model.UploadRequest
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.model.destinationPathOf
import app.snapsync.ports.BackgroundTransfer
import app.snapsync.ports.CreateResult
import app.snapsync.ports.PlatformUploadJob
import co.touchlab.kermit.Logger
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test

/**
 * The simulator target's upload-job substitute held to the clauses the upload extension's recording is
 * (capability `port-contracts`): the queue every simulator scenario creates jobs through is licensed by the contract
 * its real implementation passes inside the extension on a device.
 *
 * The substitute expects someone to play the OS; here [PlayedOs] does, in memory and as the device was measured to
 * (SE2, iOS 26.6): a job ends the moment it is created, as its fixture route answers — a success in the acknowledge
 * set; a first refusal in BOTH sets, with no error and its resource kept; a refusal after the free retry in the
 * acknowledge set only; a `hold` route never. Each clause gets fresh sets and a fresh ledger.
 */
class SimulatorUploadJobQueueContractTest {

    /** A resource on a host with no photo library: the substitute's payload check accepts this and nothing else. */
    private object StandInPhoto

    private val binding = object : Binding<BackgroundTransferState, TransferUnderTest> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Fake
        override val reaches = setOf(
            BackgroundTransferState.IDLE,
            BackgroundTransferState.AT_CAP,
            BackgroundTransferState.PRESENTED_SUCCEEDED,
            BackgroundTransferState.PRESENTED_REFUSED_ONCE,
            BackgroundTransferState.PRESENTED_RETRY_SPENT,
        )

        override fun create(state: BackgroundTransferState, clauseId: String): Entered<TransferUnderTest> {
            val jobs = SimulatorJobSets()
            val ledger = iosLedgerStore(scratch(clauseId))
            val queue = SimulatorUploadJobQueue(Logger.withTag("contract"), ledger, jobs, usablePayload = { it === StandInPhoto })
            val os = PlayedOs(queue, jobs)
            if (state == BackgroundTransferState.AT_CAP) {
                runEntry {
                    jobs.beginCycle(emptyList(), jobLimit = CAP)
                    repeat(CAP) { n ->
                        val key = BackgroundTransferContract.key(clauseId, n = n + 1)
                        val url = BASE + BackgroundTransferContract.path(clauseId, FixtureAnswer.Hold, n = n + 1)
                        val resource = resource(key, StandInPhoto)
                        check(os.createJob(UploadRequest(url, emptyMap(), resource), resource) == CreateResult.CREATED)
                    }
                }
            }
            if (state in BackgroundTransferContract.PRESENTED) runEntry { presentPrepared(state, clauseId, jobs, os, ledger) }
            return Entered.Ready(
                TransferUnderTest(
                    transfer = os,
                    base = BASE,
                    usable = { key -> resource(key, StandInPhoto) },
                    unusable = { key -> resource(key, "not a photo") },
                    ledger = ledger,
                    objects = os.objects,
                ),
            )
        }
    }

    /**
     * Enters a presented state as the OS leaves it: the clause's transfer already settled, its row `REQUESTED` with
     * the destination it was created with — what the upload extension's binding prepares across calls on a device.
     */
    private suspend fun presentPrepared(
        state: BackgroundTransferState,
        clauseId: String,
        jobs: SimulatorJobSets,
        os: PlayedOs,
        ledger: app.snapsync.ports.LedgerStore,
    ) {
        val key = BackgroundTransferContract.key(clauseId)
        val url = BASE + BackgroundTransferContract.preparedRoute(clauseId, state)
        val resource = resource(key, StandInPhoto)
        ledger.recordUnlessSettled(resource.toLedgerRow(LedgerState.REQUESTED, destinationPath = destinationPathOf(url)))
        val request = UploadRequest(url, mapOf("Content-Type" to "image/jpeg"), resource)
        check(os.createJob(request, resource) == CreateResult.CREATED)
        if (state == BackgroundTransferState.PRESENTED_RETRY_SPENT) {
            val offered = os.fetchRetryJobs().first { it.key == key }
            os.retryJob(offered, request)
        }
        check(jobs.createdThisCycle().isNotEmpty())
    }

    @Test
    fun `the simulator upload-job substitute satisfies the BackgroundTransfer contract`() =
        verify(BackgroundTransferContract, binding)

    /**
     * The OS, played in memory: [queue] is the substitute under contract, and every job it creates or re-points is
     * settled at once, as its fixture route answers, into [jobs] — the sets the substitute reads.
     */
    private class PlayedOs(private val queue: BackgroundTransfer, private val jobs: SimulatorJobSets) : BackgroundTransfer by queue {
        private val landed = mutableMapOf<String, Landed>()
        val objects = FixtureObjects { path -> landed[path] }

        override suspend fun createJob(request: UploadRequest, resource: Resource): CreateResult =
            queue.createJob(request, resource).also { if (it == CreateResult.CREATED) settle(request, resource.data, retried = false) }

        override suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest) {
            val before = jobs.createdThisCycle().size
            queue.retryJob(job, request)
            if (jobs.createdThisCycle().size > before) settle(request, job.data, retried = true)
        }

        private suspend fun settle(request: UploadRequest, resource: Any?, retried: Boolean) {
            val path = destinationPathOf(request.url).removePrefix(BASE_PATH)
            val contentType = request.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
            when (val answer = TransferFixture.answerOf(path)) {
                FixtureAnswer.Hold, null -> Unit
                is FixtureAnswer.Respond -> if (answer.status in HTTP_SUCCESS) {
                    landed[path] = Landed(contentType)
                    jobs.present(FinishedUploadJob(request.url, SimulatorJobAction.ACKNOWLEDGE, PhotoKitJobState.SUCCEEDED, null, null, contentType))
                } else {
                    if (!retried) {
                        jobs.present(FinishedUploadJob(request.url, SimulatorJobAction.RETRY, PhotoKitJobState.FAILED, null, resource, contentType))
                    }
                    jobs.present(FinishedUploadJob(request.url, SimulatorJobAction.ACKNOWLEDGE, PhotoKitJobState.FAILED, null, resource, contentType))
                }
            }
        }
    }

    private companion object {
        const val BASE_PATH = "/api/v2"
        const val BASE = "http://127.0.0.1:18099$BASE_PATH"
        const val CAP = 3
        val HTTP_SUCCESS = 200..299

        fun resource(key: String, data: Any) = Resource(key, assetIdFromUploadKey(key), "image/jpeg", emptyMap(), data)

        fun scratch(clauseId: String): String {
            val dir = NSTemporaryDirectory() + "contracts/sim-queue/$clauseId"
            NSFileManager.defaultManager.removeItemAtPath(dir, error = null)
            NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
            return dir
        }
    }
}
