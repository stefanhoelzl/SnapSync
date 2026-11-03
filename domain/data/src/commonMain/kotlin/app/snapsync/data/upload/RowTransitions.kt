package app.snapsync.data.upload

import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.store.db.LedgerQueries
import app.snapsync.domain.event.EventId
import app.snapsync.domain.upload.CreatedJob
import app.snapsync.domain.upload.DestinationPath
import app.snapsync.domain.upload.LedgerState
import app.snapsync.domain.upload.ReportedFailure
import app.snapsync.domain.upload.ReportedLanding
import app.snapsync.domain.upload.UploadKey
import app.snapsync.domain.upload.UploadRow
import app.snapsync.format.UploadKeyText
import app.snapsync.ports.DbResult

/**
 * **How an upload row moves** — each transition one compare-and-set statement and its `changes()` read in one
 * transaction, applying only from the state it names, and answering, when it moved nothing, what the row is now. Both
 * processes write these rows, so nothing here reads and then writes.
 *
 * Every transition requires the platform's word for what it records: a row becomes requested only once the platform
 * accepted its job ([CreatedJob]) — never before, so no process claims a row and then dies holding it (decision record
 * `changes/archive/2026-09-22-both-uploaders-active`, D1–D2) — and an end is recorded only as the platform reported it
 * ([ReportedFailure], [ReportedLanding]). Non-suspending: a platform callback records its end before it returns.
 */
class RowTransitions(private val db: SharedDatabase) {
    private val mapper = StoredRows()

    /** The platform accepted [job], addressed to [destination]: its row needing a job is now requested. */
    fun requested(event: EventId, job: CreatedJob, destination: DestinationPath): RowTransition =
        transition(event, job.key) { queries, key ->
            queries.markRequested(LedgerState.REQUESTED, destination.path, event.value, key, LedgerState.DISCOVERED)
        }

    /** The platform reported [failure]: its requested row needs a job again. */
    fun returned(event: EventId, failure: ReportedFailure): RowTransition =
        transition(
            event,
            failure.key,
        ) { queries, key -> queries.markTerminal(LedgerState.DISCOVERED, event.value, key) }

    /** The platform reported [landing]: its requested row is settled. */
    fun landed(event: EventId, landing: ReportedLanding): RowTransition =
        transition(event, landing.key) { queries, key -> queries.markTerminal(LedgerState.COMPLETED, event.value, key) }

    /** [write] for [key] and its `changes()` read in one transaction — and, when it moved nothing, the row as it is. */
    private fun transition(event: EventId, key: UploadKey, write: (LedgerQueries, String) -> Unit): RowTransition {
        val text = UploadKeyText.of(key)
        val moved = db.execute { database ->
            database.ledgerQueries.transactionWithResult {
                write(database.ledgerQueries, text)
                moved(database.ledgerQueries.changedRows().executeAsOne(), event, text, database.ledgerQueries)
            }
        }
        return when (moved) {
            is DbResult.Ok -> moved.value
            is DbResult.Failed -> RowTransition.Unavailable(moved.detail)
        }
    }

    private fun moved(changed: Long, event: EventId, key: String, queries: LedgerQueries): RowTransition =
        when (changed > 0L) {
            true -> RowTransition.Transitioned
            false -> superseded(queries.get(event.value, key, mapper::stored).executeAsOneOrNull()?.row)
        }

    private fun superseded(now: UploadRow?): RowTransition = now?.let(RowTransition::Superseded) ?: RowTransition.Gone
}

/** What a compare-and-set transition came to. */
sealed interface RowTransition {
    /** The row was in the state the transition starts from, and is now in the state it ends in. */
    data object Transitioned : RowTransition

    /** The row had moved on — another process, or a landing that came first: [now] is what it holds. */
    class Superseded(val now: UploadRow) : RowTransition

    /** There is no such row: an authoritative walk withdrew it, or the event's rows went with a join's purge. */
    data object Gone : RowTransition

    /** The ledger could not be written now. [detail] is diagnostics only. */
    data class Unavailable(val detail: String) : RowTransition
}
