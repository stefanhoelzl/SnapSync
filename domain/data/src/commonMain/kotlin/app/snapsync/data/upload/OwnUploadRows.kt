package app.snapsync.data.upload

import app.snapsync.data.store.SharedDatabase
import app.snapsync.domain.event.EventId
import app.snapsync.domain.upload.DestinationPath
import app.snapsync.domain.upload.LedgerState
import app.snapsync.domain.upload.UploadRow
import app.snapsync.ports.DbResult

/**
 * **This device's own upload rows, as read** — the upload ledger (`Ledger.sq`'s `ledgerRow`): which resources of the
 * member's photos need a job, have one, or are settled. Every read names the event it is for and answers the rows it
 * read, or that it could not read. What a walk writes is [WalkRecord]'s; how a row moves, [RowTransitions]'.
 */
class OwnUploadRows(private val db: SharedDatabase) {
    private val mapper = StoredRows()

    /** Every row of [event], in key order. */
    fun rows(event: EventId): RowsRead =
        rowsRead(db.execute { it.ledgerQueries.selectRows(event.value, mapper::stored).executeAsList() })

    /** The rows of [event] that need an upload job, in key order — unbounded: the caller admits, then bounds. */
    fun needingJob(event: EventId): RowsRead = rowsRead(
        db.execute {
            it.ledgerQueries.selectNeedingJob(event.value, LedgerState.needingJob, mapper::stored).executeAsList()
        },
    )

    /** The row of [event] whose upload was addressed to [destination] — how an OS-held job finds its row again. */
    fun byDestination(event: EventId, destination: DestinationPath): RowRead = rowRead(
        db.execute {
            it.ledgerQueries.selectByDestinationPath(event.value, destination.path, mapper::stored).executeAsOneOrNull()
        },
    )

    /** How many of [event]'s rows have a job in flight. */
    fun inFlight(event: EventId): CountRead = counted(
        db.execute { it.ledgerQueries.countInState(event.value, LedgerState.REQUESTED).executeAsOne() },
    )

    private fun counted(read: DbResult<Long>): CountRead = when (read) {
        is DbResult.Ok -> CountRead.Counted(read.value.toInt())
        is DbResult.Failed -> CountRead.Unavailable(read.detail)
    }

    private fun rowRead(read: DbResult<StoredRows.Mapped?>): RowRead = when (read) {
        is DbResult.Ok -> found(read.value?.row)
        is DbResult.Failed -> RowRead.Unavailable(read.detail)
    }

    private fun found(row: UploadRow?): RowRead = when (row) {
        null -> RowRead.Absent
        is UploadRow -> RowRead.Found(row)
    }

    private fun rowsRead(read: DbResult<List<StoredRows.Mapped>>): RowsRead = when (read) {
        is DbResult.Ok -> RowsRead.Read(mapper.rows(read.value))
        is DbResult.Failed -> RowsRead.Unavailable(read.detail)
    }
}

/** The rows a read found, or that it could not read. */
sealed interface RowsRead {
    class Read(val rows: List<UploadRow>) : RowsRead

    /** The ledger could not be read now (a locked device). [detail] is diagnostics only. */
    data class Unavailable(val detail: String) : RowsRead
}

/** One row a read found, that there is none, or that it could not read. */
sealed interface RowRead {
    class Found(val row: UploadRow) : RowRead

    data object Absent : RowRead

    data class Unavailable(val detail: String) : RowRead
}

/** A count a read made, or that it could not read. */
sealed interface CountRead {
    data class Counted(val count: Int) : CountRead

    data class Unavailable(val detail: String) : CountRead
}
