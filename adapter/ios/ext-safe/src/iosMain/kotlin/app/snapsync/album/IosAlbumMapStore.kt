package app.snapsync.album

import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.feature.album.AlbumMapSource
import app.snapsync.feature.album.albumMapSource
import app.snapsync.keychain.IosKeychain
import app.snapsync.ports.AlbumMapStore
import app.snapsync.ports.Keychain
import co.touchlab.kermit.Logger
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

/** The App-Group key holding the serialized `eventId → albumLocalId` map. */
private const val ALBUM_MAP_KEY = "app.snapsync.album.map"

/**
 * The iOS [AlbumMapStore] (capability `event-album`): the `eventId → albumLocalId` map, persisted as
 * JSON in the **App-Group `NSUserDefaults` suite** — the same shared suite the discovery cursor uses —
 * so both the app (which writes on album creation) and the upload extension (which reads on placement)
 * see it.
 *
 * It used to live in the Keychain, and that was the bug: the extension reads this map while the OS has
 * woken it on an *idle* — i.e. **locked** — device, where a `WhenUnlocked` Keychain item cannot be read
 * at all. App-Group container data inherits `NSFileProtectionCompleteUntilFirstUserAuthentication`, so
 * it is background-readable **by construction**, with no accessibility class to get wrong. The
 * `event-album` spec asks only for "a shared store that survives leave", never for the Keychain — and a
 * leave still does not touch this map, because `LeaveEvent.leave()` clears the config item, not this
 * key.
 *
 * A legacy Keychain map is migrated **once**, then deleted (see [albumMapSource]) — so no stale item is
 * left behind to outlive an uninstall, and no window exists in which the extension would skip album
 * placement because the map had vanished.
 *
 * Reads hit the store directly each call (no cached state), so a cross-process reader is always current.
 */
class IosAlbumMapStore(
    suiteName: String = LEDGER_APP_GROUP,
    private val legacyKeychain: Keychain =
        IosKeychain(service = "app.snapsync.album", account = "albummap"),
    private val log: Logger = Logger.withTag("AlbumMap"),
) : AlbumMapStore {

    private val defaults = NSUserDefaults(suiteName = suiteName)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    override fun get(eventId: String): String? = readMap()[eventId]

    override fun put(eventId: String, albumLocalId: String) {
        val updated = readMap().toMutableMap().apply { this[eventId] = albumLocalId }
        write(json.encodeToString(serializer, updated))
    }

    private fun readMap(): Map<String, String> {
        // The App Group wins whenever it holds anything, so the legacy Keychain item is touched at most
        // once per install: one extra read before the migration, and never again after it.
        val stored = defaults.stringForKey(ALBUM_MAP_KEY)
        if (stored != null) return decode(stored)

        val legacy = runCatching { legacyKeychain.read() }.getOrNull() ?: return emptyMap()
        return when (val source = albumMapSource(stored = null, legacy = legacy)) {
            is AlbumMapSource.Current -> decode(source.raw) // fresh install: nothing anywhere
            is AlbumMapSource.Migrate -> {
                log.i { "migrating the event-album map out of the Keychain into the App Group" }
                write(source.raw)
                legacyKeychain.delete() // one-shot: never re-migrated, and no stale item left behind
                decode(source.raw)
            }
            // Unreadable right now (protected data unavailable): do NOT delete it, and do NOT conclude
            // the map is empty — an empty map would silently drop album placement for this import.
            AlbumMapSource.Retry -> {
                log.w { "legacy album map unreadable — deferring migration; no album placement this pass" }
                emptyMap()
            }
        }
    }

    private fun decode(raw: String?): Map<String, String> {
        if (raw == null) return emptyMap()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyMap())
    }

    private fun write(raw: String) {
        runCatching { defaults.setObject(raw, forKey = ALBUM_MAP_KEY) }
            .onFailure { log.w(it) { "could not persist the event-album map" } }
    }
}
