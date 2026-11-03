package app.snapsync.data.upload

import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.store.StoreWrite
import app.snapsync.data.store.Stored
import app.snapsync.data.store.db.LedgerQueries
import app.snapsync.domain.event.EventId
import app.snapsync.domain.upload.LedgerState
import app.snapsync.domain.upload.RowDetail
import app.snapsync.domain.upload.UploadKey
import app.snapsync.format.ResourceRoleText
import app.snapsync.format.TimestampText
import app.snapsync.format.UploadKeyText
import app.snapsync.ports.DbResult

/**
 * **What a walk writes into the upload rows**: the resources it found that have no row yet, the detail of rows still
 * bare, and the withdrawal of rows its presence diff shows gone. Each is one transaction over the event it names, and
 * none reads before it writes — both processes walk (decision record
 * `changes/archive/2026-09-22-both-uploaders-active`): [discover] inserts only where no row is, [backfill] fills only a
 * bare row, [withdraw] deletes by key.
 */
class WalkRecord(private val db: SharedDatabase) {

    /**
     * Record what a walk [found] for [event] as needing a job — only for keys with no row yet, all in one transaction,
     * so a Live Photo's two resources land together or not at all.
     */
    fun discover(event: EventId, found: List<FoundResource>): StoreWrite = written(
        db.execute { database ->
            database.ledgerQueries.transaction { found.forEach { database.ledgerQueries.discovered(event, it) } }
        },
    )

    /** Fill the photo detail of [found]'s rows that are still bare; a row already filled is never rewritten. */
    fun backfill(event: EventId, found: List<FoundResource>): StoreWrite = written(
        db.execute { database ->
            database.ledgerQueries.transaction { found.forEach { database.ledgerQueries.filled(event, it) } }
        },
    )

    /** Delete exactly [keys]' rows of [event], whatever their state — a presence diff's evidence is per row. */
    fun withdraw(event: EventId, keys: List<UploadKey>): StoreWrite = written(
        db.execute { database ->
            database.ledgerQueries.transaction {
                keys.map(UploadKeyText::of).chunked(KEY_CHUNK).forEach {
                    database.ledgerQueries.deleteKeys(event.value, it)
                }
            }
        },
    )

    private fun written(written: DbResult<*>): StoreWrite = when (written) {
        is DbResult.Ok -> StoreWrite.Written
        is DbResult.Failed -> StoreWrite.Failed(written.detail)
    }

    /** [found] recorded for [event] where its key has no row: the insert-if-absent statement. */
    private fun LedgerQueries.discovered(event: EventId, found: FoundResource) = recordDiscovered(
        event.value,
        UploadKeyText.of(found.key),
        Stored.Valid(found.key.asset),
        LedgerState.DISCOVERED,
        TimestampText.captureText(found.detail.captured),
        ResourceRoleText.of(found.key.role),
        found.detail.contentType,
        found.detail.originalFilename,
    )

    /** [found]'s detail written over its row of [event] while that row is still bare. */
    private fun LedgerQueries.filled(event: EventId, found: FoundResource) = backfillManifestDetail(
        creationDate = TimestampText.captureText(found.detail.captured),
        role = ResourceRoleText.of(found.key.role),
        contentType = found.detail.contentType,
        originalFilename = found.detail.originalFilename,
        eventId = event.value,
        key = UploadKeyText.of(found.key),
    )

    private companion object {
        /** Keys per delete statement — well under every driver's bind-variable limit. */
        const val KEY_CHUNK = 500
    }
}

/** What a walk found of one resource: its key and the detail the device manifest says of it. */
class FoundResource(val key: UploadKey, val detail: RowDetail)
