@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.snapsync.ios.upload

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FixtureAnswer
import app.snapsync.contracts.FixtureObjects
import app.snapsync.contracts.Host
import app.snapsync.contracts.Landed
import app.snapsync.contracts.TransferFixture
import app.snapsync.contracts.UploadContract
import app.snapsync.contracts.UploadState
import app.snapsync.contracts.UploadUnderTest
import app.snapsync.contracts.runEntry
import app.snapsync.contracts.verify
import app.snapsync.model.ChangeOutcome
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadJobSet
import app.snapsync.model.UploadJobState
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadTarget
import app.snapsync.model.destinationPathOf
import app.snapsync.ports.Upload
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
    private object StandInPhoto

    private val binding = object : Binding<UploadState, UploadUnderTest> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Fake
        override val reaches = setOf(
            UploadState.IDLE,
            UploadState.AT_CAP,
            UploadState.PRESENTED_SUCCEEDED,
            UploadState.PRESENTED_REFUSED_ONCE,
            UploadState.PRESENTED_RETRY_SPENT,
        )

        override fun create(state: UploadState, clauseId: String): Entered<UploadUnderTest> {
            val jobs = SimulatorJobSets()
            val queue = SimulatorUploadJobQueue(Logger.withTag("contract"), jobs, payloadType = StandInPhoto::class)
            val os = PlayedOs(queue, jobs)
            if (state == UploadState.AT_CAP) {
                runEntry {
                    jobs.beginCycle(emptyList(), jobLimit = CAP)
                    repeat(CAP) { n ->
                        val url = BASE + UploadContract.path(clauseId, FixtureAnswer.Hold, n = n + 1)
                        val created = os.create(UploadSource.Resource(StandInPhoto), UploadTarget(url, emptyMap()), UploadContract.key(clauseId, n + 1))
                        check(created == UploadCreateOutcome.CREATED)
                    }
                }
            }
            if (state in UploadContract.PRESENTED) runEntry { presentPrepared(state, clauseId, jobs, os) }
            return Entered.Ready(
                UploadUnderTest(
                    upload = os,
                    base = BASE,
                    usable = { UploadSource.Resource(StandInPhoto) },
                    unusable = { UploadSource.Resource("not a photo") },
                    ended = { emptyList<UploadJob>() },
                    objects = os.objects,
                ),
            )
        }
    }

    /** Enters a presented state as the OS leaves it: the clause's transfer already settled. */
    private suspend fun presentPrepared(state: UploadState, clauseId: String, jobs: SimulatorJobSets, os: PlayedOs) {
        val target = UploadTarget(BASE + UploadContract.preparedRoute(clauseId, state), mapOf("Content-Type" to UploadContract.CONTENT_TYPE))
        check(os.create(UploadSource.Resource(StandInPhoto), target, UploadContract.key(clauseId)) == UploadCreateOutcome.CREATED)
        if (state == UploadState.PRESENTED_RETRY_SPENT) {
            val offered = os.jobs(UploadJobSet.RETRY_OFFERED).first { it.destinationPath == destinationPathOf(target.url) }
            os.retry(offered, target)
        }
        check(jobs.createdThisCycle().isNotEmpty())
    }

    @Test
    fun `the simulator upload-job substitute satisfies the Upload contract`() = verify(UploadContract, binding)

    /**
     * The OS, played in memory: [queue] is the substitute under contract, and every job it creates or re-points is
     * settled at once, as its fixture route answers, into [jobs] — the sets the substitute reads.
     */
    private class PlayedOs(private val queue: Upload, private val jobs: SimulatorJobSets) : Upload by queue {
        private val landed = mutableMapOf<String, Landed>()
        val objects = FixtureObjects { path -> landed[path] }

        override suspend fun create(source: UploadSource, target: UploadTarget, tag: String): UploadCreateOutcome =
            queue.create(source, target, tag).also {
                if (it == UploadCreateOutcome.CREATED) settle(target, (source as? UploadSource.Resource)?.handle, retried = false)
            }

        override suspend fun retry(job: UploadJob, target: UploadTarget): ChangeOutcome {
            val before = jobs.createdThisCycle().size
            return queue.retry(job, target).also {
                if (jobs.createdThisCycle().size > before) settle(target, (job.source as? UploadSource.Resource)?.handle, retried = true)
            }
        }

        private suspend fun settle(target: UploadTarget, resource: Any?, retried: Boolean) {
            val path = destinationPathOf(target.url).removePrefix(BASE_PATH)
            val contentType = target.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
            when (val answer = TransferFixture.answerOf(path)) {
                FixtureAnswer.Hold, null -> Unit
                is FixtureAnswer.Respond -> if (answer.status in HTTP_SUCCESS) {
                    landed[path] = Landed(contentType)
                    jobs.present(FinishedUploadJob(target.url, SimulatorJobAction.ACKNOWLEDGE, UploadJobState.SUCCEEDED, null, null, contentType))
                } else {
                    if (!retried) {
                        jobs.present(FinishedUploadJob(target.url, SimulatorJobAction.RETRY, UploadJobState.FAILED, null, resource, contentType))
                    }
                    jobs.present(FinishedUploadJob(target.url, SimulatorJobAction.ACKNOWLEDGE, UploadJobState.FAILED, null, resource, contentType))
                }
            }
        }
    }

    private companion object {
        const val BASE_PATH = "/api/v2"
        const val BASE = "http://127.0.0.1:18099$BASE_PATH"
        const val CAP = 3
        val HTTP_SUCCESS = 200..299
    }
}
