package app.snapsync.data.sharing

import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.store.StoreWrite
import app.snapsync.data.store.Stored
import app.snapsync.data.store.db.LedgerDatabase
import app.snapsync.data.store.db.SelectUploaded
import app.snapsync.domain.event.EventId
import app.snapsync.domain.sharing.ManifestVersion
import app.snapsync.domain.sharing.PublishedDeclaration
import app.snapsync.domain.sharing.ShareDeclaration
import app.snapsync.ports.DbResult

/**
 * **The record of this device's last published manifest** (`UploadedManifest.sq`): the
 * one row of the shared database the app and the upload extension both read, so they agree on what was last
 * published. A publish compares a fresh manifest against it and skips one the backend already holds; the end of a
 * wake reads whether a settled share was sent; receiving nets the event's declared total by how many photos this
 * device declared to it.
 *
 * A record never moves to an older version of its event (the statement's own `WHERE`): two publishes crossing in the
 * network end with the newer recorded, as the backend keeps the newer.
 */
class ManifestRecord(private val db: SharedDatabase) {

    /** What the record holds for [event]. */
    fun read(event: EventId): RecordRead =
        when (val read = db.execute { it.uploadedManifestQueries.selectUploaded().executeAsList() }) {
            is DbResult.Ok -> held(read.value, event)
            is DbResult.Failed -> RecordRead.Unavailable(read.detail)
        }

    /** Record [body] as what [event]'s backend took: its [version], what it [declared] and how many [assets]. */
    fun record(event: EventId, version: ManifestVersion, declared: ShareDeclaration, assets: Int, body: ManifestBody) =
        written(db.execute { it.recorded(event, version, declared, assets, body) })

    private fun LedgerDatabase.recorded(
        event: EventId,
        version: ManifestVersion,
        declared: ShareDeclaration,
        assets: Int,
        body: ManifestBody,
    ) = uploadedManifestQueries.recordUploaded(
        Stored.Valid(event),
        version.value,
        body.text,
        sent(declared),
        assets.toLong(),
    )

    // A row of another event — or one whose id column spells none — says nothing of this one.
    private fun held(rows: List<SelectUploaded>, event: EventId): RecordRead = rows
        .filter { it.eventId == Stored.Valid(event) }
        .map { RecordRead.Held(ManifestVersion(it.version), it.manifest, it.declaration, it.assets.toInt()) }
        .firstOrNull() ?: RecordRead.None

    private fun sent(declared: ShareDeclaration): PublishedDeclaration = when (declared) {
        is ShareDeclaration.Settled -> PublishedDeclaration.SETTLED
        ShareDeclaration.Sharing -> PublishedDeclaration.SHARING
    }

    private fun written(result: DbResult<*>): StoreWrite = when (result) {
        is DbResult.Ok -> StoreWrite.Written
        is DbResult.Failed -> StoreWrite.Failed(result.detail)
    }
}

/** [ManifestRecord.read]'s answer for one event. */
sealed interface RecordRead {
    /** How many photos this device declared to the event: none is 0, a record that cannot be read is no count. */
    val declared: Int?

    /** The last manifest published to the event: its [version], [body], [declaration] and how many [assets]. */
    class Held(
        val version: ManifestVersion,
        val body: String,
        val declaration: PublishedDeclaration,
        val assets: Int,
    ) : RecordRead {
        override val declared: Int get() = assets
    }

    /** Nothing published to the event is recorded. */
    data object None : RecordRead {
        override val declared: Int = 0
    }

    /** The record could not be read — never mistaken for none. [detail] is diagnostics only. */
    data class Unavailable(val detail: String) : RecordRead {
        override val declared: Int? = null
    }
}
