package app.snapsync.services.album

import app.snapsync.model.runCatchingCancellable
import app.snapsync.services.databases.SharedDatabase
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
     * The stored album `localIdentifier` for [eventId], or `null` if none was ever created.
     *
     * Absence: null covers "never created" and "map unreadable" alike — both send the coordinator down the
     * ensure-then-remember path, which is why this map is described as a self-healing cache. A wrong null costs one
     * redundant lookup, never a lost photo.
     */
    fun get(eventId: String): String? = runCatchingCancellable {
        db.use { it.eventAlbumQueries.selectAlbum(eventId).executeAsOneOrNull() }
    }.getOrElse { null.also { _ -> log.w(it) { "event-album map unreadable — no album placement this pass" } } }

    /** Remember [albumLocalId] as [eventId]'s album (overwrites any prior mapping). A new album has held nothing yet. */
    fun put(eventId: String, albumLocalId: String) {
        runCatchingCancellable { db.use { it.eventAlbumQueries.rememberAlbum(eventId, albumLocalId) } }
            .onFailure { log.w(it) { "album=$albumLocalId for event=$eventId not remembered" } }
    }

    /**
     * Whether [eventId]'s album has held a photo — what tells a folder album the member emptied from one not filled
     * yet, since an empty folder is no album either way (decision record `android-event-album` D4). An
     * unreadable store reads as not filled: the album is then used, never wrongly read as deleted.
     */
    fun filled(eventId: String): Boolean = runCatchingCancellable {
        db.use { it.eventAlbumQueries.selectFilled(eventId).executeAsOneOrNull() } == true
    }.getOrDefault(false)

    /** Mark [eventId]'s album as having held a photo. */
    fun markFilled(eventId: String) {
        runCatchingCancellable { db.use { it.eventAlbumQueries.markFilled(eventId) } }
            .onFailure { log.w(it) { "event=$eventId not marked filled" } }
    }
}
