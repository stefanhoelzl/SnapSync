package app.snapsync.application.upload

import app.snapsync.application.EntryPointCall
import app.snapsync.data.upload.OfferedRetry
import app.snapsync.data.upload.OsQueueDrain
import app.snapsync.data.upload.OsQueueTier
import app.snapsync.data.upload.OwnUploadRows
import app.snapsync.data.upload.ReturnedJob
import app.snapsync.data.upload.RowRead
import app.snapsync.data.upload.SendFrom
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.sharing.SelectionPolicy
import app.snapsync.domain.upload.UploadPass
import app.snapsync.domain.upload.UploadRow
import app.snapsync.inbound.Log
import app.snapsync.inbound.of

private val log = Log("SettlePresentedJobs")

/** How much of what the OS's queue presents a pass may answer: its gate decides. */
sealed interface Presented {
    /**
     * The pass may create: every retry the queue offers and every failure it holds bytes for is judged by the
     * membership's [policy] now — a membership that shares nothing refuses them all.
     */
    class Judged(val policy: SelectionPolicy) : Presented

    /** This process may not create: record and acknowledge what the queue presents, and create nothing. */
    data object Withheld : Presented
}

/**
 * **Settle with the OS's upload queue** (iOS ≥ 26.1) before anything else: what it presents is owed an answer whatever
 * else the pass does (error 50008 otherwise). A retry the queue offers is re-pointed, and a failure whose resource it
 * still holds is re-created at once — but only while the photo is still shared (change
 * `refused-retries-are-not-recreated`): a photo the member stopped sharing while its upload failed must not leave the
 * phone again, so its offered retry is declined and its failure not re-created, and its row waits needing a job for
 * the top-up, which creates it if the photo is shared again. The rows the queue cannot re-create wait for the top-up.
 */
class SettlePresentedJobs(
    private val drain: OsQueueDrain,
    private val tier: OsQueueTier,
    private val rows: OwnUploadRows,
) {
    @EntryPointCall
    suspend operator fun invoke(event: EventId, presented: Presented): UploadPass = when (presented) {
        is Presented.Judged -> judged(event, presented.policy)
        Presented.Withheld -> UploadPass.CAUGHT_UP.also { drain.drain(event) }
    }

    private suspend fun judged(event: EventId, policy: SelectionPolicy): UploadPass {
        val offered = drain.offered(event)
        val sharedOffered = shared(policy, offered.map { it.row })
        offered.forEach { retried(event, it, it.row.key.asset in sharedOffered) }
        val recreatable = drain.drain(event).mapNotNull { recreatable(event, it) }
        val sharedFailed = shared(policy, recreatable.map { it.first })
        return recreated(event, recreatable.filter { (row) -> row.key.asset in sharedFailed })
    }

    /** The photos among [rows] the membership shares now: none when it shares nothing, else what its policy admits. */
    private fun shared(policy: SelectionPolicy, rows: List<UploadRow>): Set<AssetId> = when (policy.contributes) {
        true -> policy.admitRecorded(rows.map { it.recorded }).mapTo(mutableSetOf()) { it.assetId }
        false -> emptySet()
    }

    private suspend fun retried(event: EventId, offered: OfferedRetry, shared: Boolean) = when (shared) {
        true -> log.i("offered retry re-pointed", "key" of offered.row.key, "answer" of tier.retry(event, offered))
        false -> {
            drain.decline(event, offered)
            log.i("offered retry declined — the photo is no longer shared", "key" of offered.row.key)
        }
    }

    private suspend fun recreated(event: EventId, returned: List<Pair<UploadRow.Discovered, SendFrom>>): UploadPass {
        for ((row, from) in returned) {
            tier.create(event, row, from).endedPass()?.let { return it }
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
