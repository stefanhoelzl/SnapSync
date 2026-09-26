package app.snapsync.services.album

import app.snapsync.model.PrefRead
import app.snapsync.model.SecureSlots
import app.snapsync.model.WriteOutcome
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.Preferences
import app.snapsync.ports.SecureStore
import co.touchlab.kermit.Logger
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The preferences key holding the serialized `eventId → albumLocalId` map — runtime identity. */
const val ALBUM_MAP_KEY: String = "app.snapsync.album.map"

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

    /**
     * The stored album `localIdentifier` for [eventId], or `null` if none was ever created.
     *
     * Absence: null covers "never created" and "map unreadable" alike — both send the coordinator
     * down the ensure-then-remember path, which is why this map is described as a self-healing
     * cache. A wrong null costs one redundant lookup, never a lost photo.
     */
    fun get(eventId: String): String? = readMap()[eventId]

    /** Remember [albumLocalId] as [eventId]'s album (overwrites any prior mapping). */
    fun put(eventId: String, albumLocalId: String) {
        val updated = readMap().toMutableMap().apply { this[eventId] = albumLocalId }
        write(json.encodeToString(serializer, updated))
    }

    private fun readMap(): Map<String, String> {
        // The shared preferences win whenever they hold anything, so the legacy Keychain item is touched at most
        // once per install: one extra read before the migration, and never again after it.
        val stored = when (val read = preferences.get(ALBUM_MAP_KEY)) {
            is PrefRead.Value -> return decode(read.value)
            PrefRead.Absent -> null
            // Unreadable right now: no map this pass, and no migration over a map that may exist.
            is PrefRead.Unavailable -> return emptyMap<String, String>()
                .also { log.w { "event-album map unreadable (${read.detail}) — no album placement this pass" } }
        }
        val legacyRead = runCatchingCancellable { secureStore.read(SecureSlots.ALBUM_MAP_LEGACY) }.getOrNull() ?: return emptyMap()
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
                emptyMap()
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
