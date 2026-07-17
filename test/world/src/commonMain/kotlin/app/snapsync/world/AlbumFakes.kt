package app.snapsync.world

import app.snapsync.ports.AlbumManager
import app.snapsync.ports.AlbumMapStore

/**
 * A recording [AlbumManager] for the world (capability `event-album`): tracks created albums and every
 * `add`, so integration tests can assert exactly which asset identifiers landed in which album — without
 * PhotoKit. Album ids are deterministic (`album-<n>`) so tests can vary by index rather than time.
 */
class FakeAlbumManager : AlbumManager {
    private var counter = 0
    val created = mutableListOf<Pair<String, String>>()      // (albumId, name)
    val added = mutableListOf<Pair<String, List<String>>>()   // (albumId, rawLocalIds)
    private val live = mutableSetOf<String>()

    /**
     * Pre-existing albums the *user's other apps* made — title → the normalized assetIds inside them. This
     * is the lever that lets the harness and the integration tests forge "this photo arrived via WhatsApp"
     * without PhotoKit (capability `photo-selection-policy`).
     */
    val membership = mutableMapOf<String, MutableSet<String>>()

    /** Put [assetId] into an album titled [title] — e.g. `placeIn("WhatsApp", "A1")`. */
    fun placeIn(title: String, assetId: String) {
        membership.getOrPut(title) { mutableSetOf() }.add(assetId)
    }

    /** Simulate the user deleting an album (so `exists` returns false and a re-join recreates). */
    fun delete(albumId: String) { live.remove(albumId) }

    /** Every raw localId added to [albumId] across all `add` calls, in order. */
    fun assetsIn(albumId: String): List<String> = added.filter { it.first == albumId }.flatMap { it.second }

    override suspend fun ensureCreated(name: String): String {
        val id = "album-${counter++}"
        created.add(id to name)
        live.add(id)
        return id
    }

    override suspend fun exists(albumLocalId: String): Boolean = albumLocalId in live

    override suspend fun add(albumLocalId: String, rawLocalIds: List<String>) {
        added.add(albumLocalId to rawLocalIds)
    }

    /** Mirrors the real seam: case-insensitive exact title match over the forged [membership]. */
    override suspend fun assetIdsInAlbums(titles: Set<String>, since: String): Set<String> =
        membership.entries
            .filter { (title, _) -> titles.any { it.equals(title.trim(), ignoreCase = true) } }
            .flatMapTo(mutableSetOf()) { it.value }
}

/** An in-memory [AlbumMapStore] for the world — the leave-surviving `eventId → albumLocalId` map. */
class InMemoryAlbumMapStore : AlbumMapStore {
    val map = mutableMapOf<String, String>()
    override fun get(eventId: String): String? = map[eventId]
    override fun put(eventId: String, albumLocalId: String) { map[eventId] = albumLocalId }
}
