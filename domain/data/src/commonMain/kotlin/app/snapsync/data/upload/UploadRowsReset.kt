package app.snapsync.data.upload

import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.store.StoreWrite
import app.snapsync.data.store.Stored
import app.snapsync.domain.event.EventId
import app.snapsync.domain.upload.LedgerState
import app.snapsync.domain.upload.StoredBytes
import app.snapsync.format.ResourceRoleText
import app.snapsync.format.UploadKeyText
import app.snapsync.ports.DbResult

/**
 * **The join's reset of the upload rows** — the reset family, which names its event because the join it belongs to runs
 * before the joined membership is saved, and applies no precedence. A row of any other event is already inert (every
 * read is scoped to the joined one), so purging it is housekeeping: a purge that fails costs space, never an upload.
 */
class UploadRowsReset(private val db: SharedDatabase) {

    /**
     * Make [stored] — what the backend already holds for this device in [event] — [event]'s rows, settled, and purge
     * every other event's, in one transaction: all of it lands, or the store is left as it was. Each starts bare: a
     * stored-file listing names no capture date, and the next walk fills it.
     */
    fun seed(event: EventId, stored: List<StoredBytes>): StoreWrite = written(
        db.execute { database ->
            database.ledgerQueries.transaction {
                database.ledgerQueries.purgeExcept(event.value)
                database.ledgerQueries.deleteEvent(event.value)
                stored.forEach {
                    database.ledgerQueries.insert(
                        event.value, UploadKeyText.of(it.key), Stored.Valid(it.key.asset), LedgerState.COMPLETED,
                        "", ResourceRoleText.of(it.key.role), "", "", null,
                    )
                }
            }
        },
    )

    /**
     * The join's kept reset: purge every row of any event but [event], and keep what this device last knew of [event]
     * — except that a row in flight returns to needing a job, its destination cleared, in the same transaction.
     *
     * Safe because a join into a new membership follows a leave or switch that stopped every upload of [event]
     * (`StopUploads`), so nothing is sending such a row, and nothing else would ever send it again. Should an OS job
     * have survived, the cost is the same photo uploaded twice, which no member sees as a second copy. A `COMPLETED`
     * row stays as it is.
     */
    fun purgeExcept(event: EventId): StoreWrite = written(
        db.execute { database ->
            database.ledgerQueries.transaction {
                database.ledgerQueries.purgeExcept(event.value)
                database.ledgerQueries.requeueRequested(event.value)
            }
        },
    )

    private fun written(written: DbResult<*>): StoreWrite = when (written) {
        is DbResult.Ok -> StoreWrite.Written
        is DbResult.Failed -> StoreWrite.Failed(written.detail)
    }
}
