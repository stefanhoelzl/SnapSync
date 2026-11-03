package app.snapsync.services.album

import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.store.Stored
import app.snapsync.domain.event.EventId
import app.snapsync.model.runCatchingCancellable
import app.snapsync.services.databases.use
import co.touchlab.kermit.Logger

/**
 * The event-album map: one `eventAlbum` row per event in the shared database, so the app
 * (which writes on album creation) and the upload extension (which reads on placement) both see it — readable while
 * locked once unlocked since boot. Reads hit the database each call (no cached state), so a cross-process reader is
 * always current. Each event is its own row, so a write can never drop another event's album.
 */
class AlbumMapService(
    private val db: SharedDatabase,
    private val log: Logger = Logger.withTag("AlbumMap"),
) {

    /**
     * What the map holds for [eventId], telling a map that cannot be read apart from one that holds no album: an
     * unreadable map is no reason to make a new album, which would be a second one beside the album the map lost
     * sight of.
     */
    fun lookup(eventId: EventId): AlbumLookup = runCatchingCancellable {
        db.use { it.eventAlbumQueries.selectAlbum(Stored.Valid(eventId)).executeAsOneOrNull() }
    }.fold(
        onSuccess = { album -> album?.let(AlbumLookup::Stored) ?: AlbumLookup.None },
        onFailure = { AlbumLookup.Unreadable.also { _ -> log.w(it) { "event-album map unreadable" } } },
    )

    /**
     * The stored album `localIdentifier` for [eventId], or `null` if none was ever created or the map is unreadable —
     * for the readers that only add to an album that exists, where either way nothing is placed this pass.
     */
    fun get(eventId: EventId): String? = (lookup(eventId) as? AlbumLookup.Stored)?.album

    /** Remember [albumLocalId] as [eventId]'s album (overwrites any prior mapping). A new album has held nothing yet. */
    fun put(eventId: EventId, albumLocalId: String) {
        runCatchingCancellable { db.use { it.eventAlbumQueries.rememberAlbum(Stored.Valid(eventId), albumLocalId) } }
            .onFailure { log.w(it) { "album=$albumLocalId for event=$eventId not remembered" } }
    }

    /**
     * Whether [eventId]'s album has held a photo — what tells a folder album the member emptied from one not filled
     * yet, since an empty folder is no album either way (decision record `android-event-album` D4). An
     * unreadable store reads as not filled: the album is then used, never wrongly read as deleted.
     */
    fun filled(eventId: EventId): Boolean = runCatchingCancellable {
        db.use { it.eventAlbumQueries.selectFilled(Stored.Valid(eventId)).executeAsOneOrNull() } == true
    }.getOrDefault(false)

    /** Mark [eventId]'s album as having held a photo. */
    fun markFilled(eventId: EventId) {
        runCatchingCancellable { db.use { it.eventAlbumQueries.markFilled(Stored.Valid(eventId)) } }
            .onFailure { log.w(it) { "event=$eventId not marked filled" } }
    }
}

/** What the event-album map holds for one event. */
sealed interface AlbumLookup {
    /** The album made for the event, by its `localIdentifier`. */
    data class Stored(val album: String) : AlbumLookup

    /** No album was ever made for the event. */
    data object None : AlbumLookup

    /** The map could not be read: whether an album exists is unknown. */
    data object Unreadable : AlbumLookup
}
