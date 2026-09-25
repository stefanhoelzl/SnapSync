package app.snapsync.world

import app.snapsync.fake.inMemoryAlbumManager
import app.snapsync.ports.AlbumManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import app.snapsync.model.RawAsset

/**
 * The world's rigging around the honest `:adapter:generic:fake` [inMemoryAlbumManager] (capability
 * `event-album`): it records created albums and every `add`, so integration tests can assert exactly which
 * asset identifiers landed in which album without PhotoKit.
 *
 * Every answer is the honest fake's, the one `AlbumManagerContract` holds to `IosAlbumManager`. What lives
 * here is only what a fake may not carry: the inspection lists, the [placeIn] and [delete] levers, and the
 * [holdAdds] gate.
 */
class FakeAlbumManager(library: StateFlow<List<RawAsset>>) : AlbumManager {

    /**
     * Pre-existing albums the *user's other apps* made — title → the normalized assetIds inside them. The
     * honest fake reads this cell; [placeIn] is how the harness and the integration tests forge "this photo
     * arrived via WhatsApp" without PhotoKit (capability `photo-sharing`).
     */
    private val userAlbums = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val honest: AlbumManager = inMemoryAlbumManager(library, userAlbums)

    val created = mutableListOf<Pair<String, String>>()      // (albumId, name)
    val added = mutableListOf<Pair<String, List<String>>>()   // (albumId, rawLocalIds)
    private val deleted = mutableSetOf<String>()

    /** Put [assetId] into an album titled [title] — e.g. `placeIn("WhatsApp", "A1")`. */
    fun placeIn(title: String, assetId: String) {
        userAlbums.value = userAlbums.value + (title to (userAlbums.value[title].orEmpty() + assetId))
    }

    /** Simulate the user deleting an album (so `exists` returns false and a re-join recreates). */
    fun delete(albumId: String) { deleted.add(albumId) }

    /** Every raw localId added to [albumId] across all `add` calls, in order. */
    fun assetsIn(albumId: String): List<String> = added.filter { it.first == albumId }.flatMap { it.second }

    override suspend fun ensureCreated(name: String): String? =
        honest.ensureCreated(name)?.also { created.add(it to name) }

    override suspend fun exists(albumLocalId: String): Boolean =
        albumLocalId !in deleted && honest.exists(albumLocalId)

    private var addsHeld: CompletableDeferred<Unit>? = null

    /**
     * Operator lever: every `add` waits until [releaseAdds]. During a join or a reconfigure Save only the event
     * album's gather adds, so this is how a test shows the act that started a gather never waits on it
     * (capability `event-album`).
     */
    fun holdAdds() { addsHeld = CompletableDeferred() }

    fun releaseAdds() {
        addsHeld?.complete(Unit)
        addsHeld = null
    }

    override suspend fun add(albumLocalId: String, rawLocalIds: List<String>) {
        addsHeld?.await()
        added.add(albumLocalId to rawLocalIds)
        honest.add(albumLocalId, rawLocalIds)
    }

    override suspend fun assetIdsInAlbums(titles: Set<String>, since: String): Set<String> =
        honest.assetIdsInAlbums(titles, since)
}
