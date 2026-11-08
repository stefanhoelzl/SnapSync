package app.snapsync.data.upload

import app.snapsync.domain.upload.Ownerless
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log
import app.snapsync.ports.ChangeOutcome
import app.snapsync.ports.OsUploadJob
import app.snapsync.ports.OsUploadQueue

/**
 * **A presented job answered** — what the queue expects of every job it presents (error 50008 otherwise). A refused
 * acknowledgement is logged: the queue offers the job again.
 */
class JobAcknowledgement(private val queue: OsUploadQueue) {
    private val log = Log("JobAcknowledgement")

    suspend fun acknowledge(job: OsUploadJob) = when (val answer = queue.acknowledge(job)) {
        ChangeOutcome.Applied -> Unit
        is ChangeOutcome.Refused -> log.w(
            "acknowledge refused — the queue offers the job again",
            "code" of answer.code,
            "detail" of answer.detail,
        )
    }

    /** Acknowledge [job], which no row owns, saying [why]: a withdrawn row's quietly, an unmappable one loudly. */
    suspend fun ownerless(job: OsUploadJob, why: Ownerless) {
        logged(job, why)
        acknowledge(job)
    }

    private suspend fun logged(job: OsUploadJob, why: Ownerless) = when (why) {
        Ownerless.WITHDRAWN -> log.i("a job of a row the walk withdrew — acknowledged", "job" of job.destinationUrl)
        Ownerless.UNMAPPABLE -> log.e(
            "a job carried no destination this build can map — its outcome is discarded",
            "job" of job.destinationUrl,
        )
    }
}
