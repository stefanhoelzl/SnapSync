package app.snapsync.application.upload

import app.snapsync.application.EntryPointCall
import app.snapsync.data.upload.CreateAnswer
import app.snapsync.data.upload.OsQueueDrain
import app.snapsync.data.upload.OsQueueTier
import app.snapsync.data.upload.OwnUploadRows
import app.snapsync.data.upload.ReturnedJob
import app.snapsync.data.upload.RowRead
import app.snapsync.data.upload.SendFrom
import app.snapsync.domain.event.EventId
import app.snapsync.domain.upload.UploadPass
import app.snapsync.domain.upload.UploadRow
import app.snapsync.inbound.Log
import app.snapsync.inbound.of

private val log = Log("SettlePresentedJobs")

/** How much of what the OS's queue presents a pass may answer: its gate decides. */
enum class Presented {
    /** Re-point the offered retries, record the terminal jobs, re-create the failures the queue holds bytes for. */
    RUN,

    /** The membership contributes nothing: record the terminal jobs, and re-create the failures the queue holds. */
    DECLINED,

    /** This process may not create: record and acknowledge what the queue presents, and create nothing. */
    WITHHELD,
}

/**
 * **Settle with the OS's upload queue** (iOS ≥ 26.1) before anything else: what it presents is owed an answer whatever
 * else the pass does (error 50008 otherwise). A failure whose resource the queue still holds is re-created at once —
 * as the queue presents it, without the policy's word; the rows the queue cannot re-create wait for the top-up.
 */
class SettlePresentedJobs(
    private val drain: OsQueueDrain,
    private val tier: OsQueueTier,
    private val rows: OwnUploadRows,
) {
    @EntryPointCall
    suspend operator fun invoke(event: EventId, presented: Presented): UploadPass {
        if (presented == Presented.RUN) repointed(event)
        val returned = drain.drain(event)
        return when (presented) {
            Presented.RUN, Presented.DECLINED -> recreated(event, returned)
            Presented.WITHHELD -> UploadPass.CAUGHT_UP
        }
    }

    private suspend fun repointed(event: EventId) = drain.offered(event).forEach { offered ->
        log.i("offered retry re-pointed", "key" of offered.row.key, "answer" of tier.retry(event, offered))
    }

    private suspend fun recreated(event: EventId, returned: List<ReturnedJob>): UploadPass {
        for ((row, from) in returned.mapNotNull { recreatable(event, it) }) {
            if (tier.create(event, row, from) == CreateAnswer.Full) return UploadPass.WORK_LEFT
        }
        return UploadPass.CAUGHT_UP
    }

    /** [job]'s row and the resource the queue holds for it — none once retry-spent: the top-up re-creates that. */
    private fun recreatable(event: EventId, job: ReturnedJob): Pair<UploadRow.Discovered, SendFrom>? =
        job.from?.let { from -> discovered(event, job)?.let { it to from } }

    /** [job]'s row as it is now — another process, or a landing that came first, may have moved it on. */
    private fun discovered(event: EventId, job: ReturnedJob): UploadRow.Discovered? =
        when (val read = rows.row(event, job.key)) {
            is RowRead.Found -> read.row as? UploadRow.Discovered
            RowRead.Absent, is RowRead.Unavailable -> null
        }
}
