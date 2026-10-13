@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.snapsync.ios.upload

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FixtureAnswer
import app.snapsync.contracts.FixtureObjects
import app.snapsync.contracts.Host
import app.snapsync.contracts.Landed
import app.snapsync.contracts.OsUploadQueueContract
import app.snapsync.contracts.OsUploadQueueState
import app.snapsync.contracts.QueueUnderTest
import app.snapsync.contracts.TransferFixture
import app.snapsync.contracts.UPLOAD_CONTENT_TYPE
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.runEntry
import app.snapsync.contracts.verify
import app.snapsync.model.destinationPathOf
import app.snapsync.ports.ChangeOutcome
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.OsUploadJob
import app.snapsync.ports.OsUploadQueue
import app.snapsync.ports.TransferNetwork
import app.snapsync.ports.UploadJobState
import app.snapsync.ports.UploadTarget
import co.touchlab.kermit.Logger
import kotlin.test.Test

/**
 * The simulator target's upload-job substitute held to the clauses the upload extension's recording is
 * (`docs/architecture.md`): the queue every simulator scenario creates jobs through is licensed by the contract its real
 * implementation passes inside the extension on a device.
 *
 * The substitute expects someone to play the OS; here [PlayedOs] does, in memory and as the device was measured to
 * (SE2, iOS 26.6): a job ends the moment it is created, as its fixture route answers — a success in the acknowledge
 * set; a first refusal in BOTH sets, with no error and its resource kept; a refusal after the free retry in the
 * acknowledge set only; a `hold` route never. Each clause gets fresh sets.
 */
class SimulatorUploadJobQueueContractTest {

    /** A resource on a host with no photo library: the substitute's payload check accepts this and nothing else. */
    private object StandInPhoto : GalleryResourceHandle

    /** A resource of any other type, which the substitute refuses as the real queue refuses a non-PhotoKit one. */
    private object NotAPhoto : GalleryResourceHandle

    private val binding = object : Binding<OsUploadQueueState, QueueUnderTest> {
        override val host = Host.IOS_SIM_KEXE

        // The code the simulator app runs, so its run is a Live one (`docs/testing.md`, "Every adapter is covered").
        override val kind = BindingKind.Live
        override val adapter = SimulatorUploadJobQueue::class
        override val reaches = setOf(
            OsUploadQueueState.IDLE,
            OsUploadQueueState.AT_CAP,
            OsUploadQueueState.PRESENTED_SUCCEEDED,
            OsUploadQueueState.PRESENTED_REFUSED_ONCE,
            OsUploadQueueState.PRESENTED_RETRY_SPENT,
            OsUploadQueueState.ANY,
        )

        override fun create(state: OsUploadQueueState, clauseId: String, log: CallLog): Entered<QueueUnderTest> {
            // A state this host does not present: another platform's fact, or one no binding here enters.
            if (state !in reaches) return Entered.Unreachable("$state is not a state this host presents")
            val jobs = SimulatorJobSets()
            val queue = SimulatorUploadJobQueue(Logger.withTag("contract"), jobs, payloadType = StandInPhoto::class)
            // The played OS settles what the substitute creates; it wraps the substitute's proxy, so what the clause
            // sees through it is recorded as the substitute's own answers.
            val os = PlayedOs(queue.recorded(log), jobs)
            if (state == OsUploadQueueState.AT_CAP) {
                runEntry {
                    jobs.beginCycle(emptyList(), jobLimit = CAP)
                    repeat(CAP) { n ->
                        val url = BASE + OsUploadQueueContract.path(clauseId, FixtureAnswer.Hold, n = n + 1)
                        check(
                            os.create(StandInPhoto, UploadTarget(url, emptyMap(), TransferNetwork.ANY)) == CreateOutcome.CREATED,
                        )
                    }
                }
            }
            if (state in OsUploadQueueContract.PRESENTED) runEntry { presentPrepared(state, clauseId, jobs, os) }
            return Entered.Ready(
                QueueUnderTest(
                    queue = os,
                    base = BASE,
                    usable = { StandInPhoto },
                    unusable = { NotAPhoto },
                    objects = os.objects,
                ),
            )
        }
    }

    /** Enters a presented state as the OS leaves it: the clause's transfer already settled. */
    private suspend fun presentPrepared(
        state: OsUploadQueueState,
        clauseId: String,
        jobs: SimulatorJobSets,
        os: PlayedOs,
    ) {
        val target = UploadTarget(
            BASE + OsUploadQueueContract.preparedRoute(clauseId, state),
            mapOf("Content-Type" to UPLOAD_CONTENT_TYPE),
            TransferNetwork.ANY,
        )
        check(os.create(StandInPhoto, target) == CreateOutcome.CREATED)
        if (state == OsUploadQueueState.PRESENTED_RETRY_SPENT) {
            val offered = os.retryOffered().first { it.destinationUrl?.let(::destinationPathOf) == destinationPathOf(target.url) }
            os.retry(offered, target)
        }
        check(jobs.createdThisCycle().isNotEmpty())
    }

    @Test
    fun `the simulator upload-job substitute satisfies the OsUploadQueue contract`() =
        verify(OsUploadQueueContract, binding)

    /**
     * The OS, played in memory: [queue] is the substitute under contract, and every job it creates or re-points is
     * settled at once, as its fixture route answers, into [jobs] — the sets the substitute reads.
     */
    private class PlayedOs(
        private val queue: OsUploadQueue,
        private val jobs: SimulatorJobSets,
    ) : OsUploadQueue by queue {
        private val landed = mutableMapOf<String, Landed>()
        val objects = FixtureObjects { path -> landed[path] }

        override suspend fun create(resource: GalleryResourceHandle, target: UploadTarget): CreateOutcome =
            queue.create(resource, target).also {
                if (it == CreateOutcome.CREATED) settle(target, resource, retried = false)
            }

        override suspend fun retry(job: OsUploadJob, target: UploadTarget): ChangeOutcome {
            val before = jobs.createdThisCycle().size
            return queue.retry(job, target).also {
                if (jobs.createdThisCycle().size > before) settle(target, job.resource, retried = true)
            }
        }

        private suspend fun settle(target: UploadTarget, resource: GalleryResourceHandle?, retried: Boolean) {
            val path = destinationPathOf(target.url).removePrefix(BASE_PATH)
            val contentType = target.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
            when (val answer = TransferFixture.answerOf(path)) {
                // No upload clause redirects.
                is FixtureAnswer.Redirect, FixtureAnswer.Hold, null -> Unit
                is FixtureAnswer.Respond -> if (answer.status in HTTP_SUCCESS) {
                    landed[path] = Landed(contentType)
                    jobs.present(
                        FinishedUploadJob(
                            target.url,
                            SimulatorJobAction.ACKNOWLEDGE,
                            UploadJobState.SUCCEEDED,
                            null,
                            null,
                            contentType,
                        ),
                    )
                } else {
                    if (!retried) {
                        jobs.present(
                            FinishedUploadJob(
                                target.url,
                                SimulatorJobAction.RETRY,
                                UploadJobState.FAILED,
                                null,
                                resource,
                                contentType,
                            ),
                        )
                    }
                    jobs.present(
                        FinishedUploadJob(
                            target.url,
                            SimulatorJobAction.ACKNOWLEDGE,
                            UploadJobState.FAILED,
                            null,
                            resource,
                            contentType,
                        ),
                    )
                }
            }
        }
    }

    private companion object {
        const val BASE_PATH = "/api/v2"
        const val BASE = CONTRACT_UPLOAD_BASE
        const val CAP = 3
        val HTTP_SUCCESS = 200..299
    }
}
