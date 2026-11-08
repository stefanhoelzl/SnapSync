package app.snapsync.application.upload

import app.snapsync.application.EntryPointCall
import app.snapsync.data.upload.OwnUploadRows
import app.snapsync.data.upload.RowsRead
import app.snapsync.domain.event.EventId
import app.snapsync.domain.sharing.SelectionPolicy
import app.snapsync.domain.upload.TopUpPlan
import app.snapsync.domain.upload.UploadRow
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log

private val log = Log("PlanTopUp")

/** **Plan a top-up**: the rows of [EventId] needing a job whose asset [SelectionPolicy] admits now ([TopUpPlan]). */
class PlanTopUp(private val rows: OwnUploadRows) {
    @EntryPointCall
    suspend operator fun invoke(event: EventId, policy: SelectionPolicy): TopUpPlan = when (
        val read = rows.needingJob(
            event,
        )
    ) {
        is RowsRead.Read -> planned(read.rows.filterIsInstance<UploadRow.Discovered>(), policy)
        is RowsRead.Unavailable -> TopUpPlan.of(emptyList(), emptyList()).also {
            log.w("the upload rows could not be read — nothing planned", "detail" of read.detail)
        }
    }

    private suspend fun planned(needing: List<UploadRow.Discovered>, policy: SelectionPolicy): TopUpPlan {
        val plan = TopUpPlan.of(needing, policy.admitRecorded(needing.map { it.recorded }))
        log.i("top-up planned", "needing" of needing.size, "admitted" of plan.rows.size)
        return plan
    }
}
