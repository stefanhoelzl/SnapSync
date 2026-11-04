package app.snapsync.data.album

import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.store.StoreWrite
import app.snapsync.data.store.Stored
import app.snapsync.data.store.db.SelectEntry
import app.snapsync.domain.album.AlbumEntry
import app.snapsync.domain.album.EventAlbumId
import app.snapsync.domain.event.EventId
import app.snapsync.ports.DbResult

/**
 * Each event's album, one `eventAlbum` row per event in the shared database: the app writes it when it makes the album,
 * both processes read it to place photos. Every call reads the database — no state held here — so a reader in the other
 * process is always current, and a row survives the membership's leave so a rejoin finds its album.
 */
class AlbumMap(private val db: SharedDatabase) {

    /** What the map holds for [event]: its album and mark from the one row, no album, or that it could not be read. */
    fun entry(event: EventId): AlbumEntry =
        when (val read = db.execute { it.eventAlbumQueries.selectEntry(Stored.Valid(event)).executeAsOneOrNull() }) {
            is DbResult.Ok -> entryOf(read.value)
            is DbResult.Failed -> AlbumEntry.Unreadable
        }

    private fun entryOf(row: SelectEntry?): AlbumEntry =
        row?.let { AlbumEntry.Mapped(it.albumLocalId, it.filled) } ?: AlbumEntry.None

    /** [album] is [event]'s album from now on, replacing any earlier one; a new album has held nothing yet. */
    fun remember(event: EventId, album: EventAlbumId): StoreWrite =
        written(db.execute { it.eventAlbumQueries.rememberAlbum(Stored.Valid(event), album) })

    /** [event]'s album has held a photo. */
    fun markFilled(event: EventId): StoreWrite =
        written(db.execute { it.eventAlbumQueries.markFilled(Stored.Valid(event)) })

    private fun written(written: DbResult<*>): StoreWrite = when (written) {
        is DbResult.Ok -> StoreWrite.Written
        is DbResult.Failed -> StoreWrite.Failed(written.detail)
    }
}
