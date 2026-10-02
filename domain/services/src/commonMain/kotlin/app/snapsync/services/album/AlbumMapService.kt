package app.snapsync.services.album

import app.snapsync.model.PrefRead
import app.snapsync.model.SecureSlots
import app.snapsync.model.WriteOutcome
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.Preferences
import app.snapsync.ports.SecureStore
import co.touchlab.kermit.Logger
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The preferences key holding the serialized `eventId → albumLocalId` map — runtime identity. */
const val ALBUM_MAP_KEY: String = "app.snapsync.album.map"

/** The preferences key holding the events whose folder album has held a photo — runtime identity. */
const val ALBUM_FILLED_KEY: String = "app.snapsync.album.filled"

/**
 * The event-album map (capability `event-album`): [AlbumMapService] as JSON under one key of the shared
 * [Preferences], so the app (which writes on album creation) and the upload extension (which reads on placement)
 * both see it — readable while locked, which the Keychain item it replaced was not.
 *
 * A legacy Keychain map ([SecureSlots.ALBUM_MAP_LEGACY]) is migrated **once**, then deleted ([albumMapSource]) — so no stale item
 * outlives an uninstall, and no window exists in which the extension would skip album placement because the map
 * had vanished. Reads hit the store each call (no cached state), so a cross-process reader is always current.
 */
class AlbumMapService(
    private val preferences: Preferences,
    /** Where the legacy map may still sit ([SecureSlots.ALBUM_MAP_LEGACY]). */
    private val secureStore: SecureStore,
    private val log: Logger = Logger.withTag("AlbumMap"),
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val filledSerializer = SetSerializer(String.serializer())

    /**
     * The stored album `localIdentifier` for [eventId], or `null` if none was ever created.
     *
     * Absence: null covers "never created" and "map unreadable" alike — both send the coordinator
     * down the ensure-then-remember path, which is why this map is described as a self-healing
     * cache. A wrong null costs one redundant lookup, never a lost photo.
     */
    fun get(eventId: String): String? = readMap()?.get(eventId)

    /**
     * Remember [albumLocalId] as [eventId]'s album (overwrites any prior mapping). A new album has held nothing yet, so
     * the event's [filled] mark is cleared. An unreadable map is not written over — that would drop every other
     * event's album, or orphan a legacy map not migrated yet — so the album is then not remembered this pass.
     */
    fun put(eventId: String, albumLocalId: String) {
        val current = readMap() ?: return log.w { "album=$albumLocalId for event=$eventId not remembered: the map is unreadable" }
        write(json.encodeToString(serializer, current + (eventId to albumLocalId)))
        val filled = readFilled() ?: return
        if (eventId in filled) writeFilled(filled - eventId)
    }

    /**
     * Whether [eventId]'s album has held a photo — what tells a folder album the member emptied from one not filled
     * yet, since an empty folder is no album either way (capability `event-album`; `android-event-album` D4). An
     * unreadable store reads as not filled: the album is then used, never wrongly read as deleted.
     */
    fun filled(eventId: String): Boolean = eventId in readFilled().orEmpty()

    /** Mark [eventId]'s album as having held a photo — never over marks it could not read, which it would drop. */
    fun markFilled(eventId: String) {
        val current = readFilled() ?: return log.w { "event-album filled marks unreadable — event=$eventId not marked" }
        if (eventId !in current) writeFilled(current + eventId)
    }

    /** The filled marks: empty when there are none (or they do not decode), `null` when they cannot be read now. */
    private fun readFilled(): Set<String>? = when (val read = preferences.get(ALBUM_FILLED_KEY)) {
        is PrefRead.Value -> runCatchingCancellable { json.decodeFromString(filledSerializer, read.value) }.getOrDefault(emptySet())
        PrefRead.Absent -> emptySet()
        is PrefRead.Unavailable -> null
    }

    private fun writeFilled(events: Set<String>) {
        val written = preferences.set(ALBUM_FILLED_KEY, json.encodeToString(filledSerializer, events))
        if (written != WriteOutcome.Ok) log.w { "could not persist the event-album filled marks ($written)" }
    }

    /** The map, migrating a legacy one once; `null` when it (or a legacy map not migrated yet) cannot be read now. */
    private fun readMap(): Map<String, String>? {
        // The shared preferences win whenever they hold anything, so the legacy Keychain item is touched at most
        // once per install: one extra read before the migration, and never again after it.
        val stored = when (val read = preferences.get(ALBUM_MAP_KEY)) {
            is PrefRead.Value -> return decode(read.value)
            PrefRead.Absent -> null
            // Unreadable right now: no map this pass, and no migration over a map that may exist.
            is PrefRead.Unavailable -> return null
                .also { log.w { "event-album map unreadable (${read.detail}) — no album placement this pass" } }
        }
        val legacyRead = runCatchingCancellable { secureStore.read(SecureSlots.ALBUM_MAP_LEGACY) }.getOrNull() ?: return null
        return when (val source = albumMapSource(stored = stored, legacy = legacyRead)) {
            is AlbumMapSource.Current -> decode(source.raw) // fresh install: nothing anywhere
            is AlbumMapSource.Migrate -> {
                log.i { "migrating the event-album map out of the Keychain into the App Group" }
                // One-shot: never re-migrated, and no stale item left behind — but only once the move is stored.
                if (write(source.raw)) secureStore.delete(SecureSlots.ALBUM_MAP_LEGACY)
                decode(source.raw)
            }
            // Unreadable right now (protected data unavailable): do NOT delete it, and do NOT conclude the map is
            // empty — an empty map would silently drop album placement for this import.
            AlbumMapSource.Retry -> {
                log.w { "legacy album map unreadable — deferring migration; no album placement this pass" }
                null
            }
        }
    }

    private fun decode(raw: String?): Map<String, String> {
        if (raw == null) return emptyMap()
        return runCatchingCancellable { json.decodeFromString(serializer, raw) }.getOrDefault(emptyMap())
    }

    /** Whether the map was stored. A refused write is logged: the next [put] or migration tries again. */
    private fun write(raw: String): Boolean = when (val written = preferences.set(ALBUM_MAP_KEY, raw)) {
        WriteOutcome.Ok -> true
        else -> false.also { log.w { "could not persist the event-album map ($written)" } }
    }
}
