package app.snapsync.data.legacy

import app.snapsync.data.store.LegacySource
import app.snapsync.data.store.Stored
import app.snapsync.data.store.db.LedgerDatabase
import app.snapsync.domain.album.AlbumFill
import app.snapsync.domain.album.EventAlbumId
import app.snapsync.domain.event.EventId
import app.snapsync.inbound.Log
import app.snapsync.ports.PrefRead
import app.snapsync.ports.Preferences
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The event-album map's copy: which album each event's photos went into, and whether it was filled. An entry naming no
 * event is dropped: its album is found again by name at the next placement.
 */
internal class LegacyAlbumCopy(private val preferences: Preferences, private val homes: LegacyHomes) {
    private val log = Log("LegacyRecords")
    private val json = Json { ignoreUnknownKeys = true }

    fun copy(db: LedgerDatabase) =
        copy(db, preferences.get(LegacyHomes.ALBUM_MAP_KEY), preferences.get(LegacyHomes.ALBUM_FILLED_KEY))

    private fun copy(db: LedgerDatabase, map: PrefRead, filled: PrefRead) =
        when (listOf(map, filled).any { it is PrefRead.Unavailable }) {
            true -> log.unlabelled.w("legacy event-album map unreadable — retried at the next open")
            false -> copy(db, albumsOf(map), filledOf(filled))
        }

    private fun copy(db: LedgerDatabase, albums: Map<String, String>, filled: Set<String>) {
        db.transaction {
            albums.forEach { (key, album) -> import(db, key, album, key in filled) }
            db.legacyImportQueries.markImported(LegacySource.ALBUM.key)
        }
        homes.forgetAlbums()
    }

    private fun import(db: LedgerDatabase, key: String, album: String, filled: Boolean) {
        EventId.parse(key)
            ?.let { db.eventAlbumQueries.importAlbum(Stored.Valid(it), EventAlbumId(album), fillOf(filled)) }
    }

    private fun fillOf(filled: Boolean) = when (filled) {
        true -> AlbumFill.Filled
        false -> AlbumFill.NotYetFilled
    }

    private fun albumsOf(map: PrefRead): Map<String, String> = (map as? PrefRead.Value)
        ?.let { decoded(it.value, MapSerializer(String.serializer(), String.serializer())) }
        .orEmpty()

    private fun filledOf(filled: PrefRead): Set<String> =
        (filled as? PrefRead.Value)?.let { decoded(it.value, SetSerializer(String.serializer())) }.orEmpty()

    private fun <T> decoded(text: String, strategy: KSerializer<T>): T? = try {
        json.decodeFromString(strategy, text)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
