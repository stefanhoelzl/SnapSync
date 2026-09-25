package app.snapsync.services.album

import app.snapsync.model.PrefRead
import app.snapsync.model.WriteOutcome
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.AlbumMapStore
import app.snapsync.ports.Preferences
import app.snapsync.ports.SecureStore
import co.touchlab.kermit.Logger
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The preferences key holding the serialized `eventId → albumLocalId` map — runtime identity. */
const val ALBUM_MAP_KEY: String = "app.snapsync.album.map"

/**
 * The event-album map (capability `event-album`): [AlbumMapStore] as JSON under one key of the shared
 * [Preferences], so the app (which writes on album creation) and the upload extension (which reads on placement)
 * both see it — readable while locked, which the Keychain item it replaced was not.
 *
 * A legacy Keychain map ([legacy]) is migrated **once**, then deleted ([albumMapSource]) — so no stale item
 * outlives an uninstall, and no window exists in which the extension would skip album placement because the map
 * had vanished. Reads hit the store each call (no cached state), so a cross-process reader is always current.
 */
class AlbumMapService(
    private val preferences: Preferences,
    private val legacy: SecureStore,
    private val log: Logger = Logger.withTag("AlbumMap"),
) : AlbumMapStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    override fun get(eventId: String): String? = readMap()[eventId]

    override fun put(eventId: String, albumLocalId: String) {
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
        val legacyRead = runCatchingCancellable { legacy.read() }.getOrNull() ?: return emptyMap()
        return when (val source = albumMapSource(stored = stored, legacy = legacyRead)) {
            is AlbumMapSource.Current -> decode(source.raw) // fresh install: nothing anywhere
            is AlbumMapSource.Migrate -> {
                log.i { "migrating the event-album map out of the Keychain into the App Group" }
                if (write(source.raw)) legacy.delete() // one-shot: never re-migrated, and no stale item left behind
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
