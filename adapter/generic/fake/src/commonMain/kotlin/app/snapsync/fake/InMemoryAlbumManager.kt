package app.snapsync.fake

import app.snapsync.model.RawAsset
import app.snapsync.model.normalizeAssetId
import app.snapsync.ports.AlbumManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The honest in-memory [AlbumManager] (capability `event-album`), held to `AlbumManagerContract` exactly as
 * `IosAlbumManager` is.
 *
 * Albums hold assets of [library], the caller's own cell, and answer as PhotoKit does:
 * - an asset added to an album is a member of it;
 * - an asset the library does not hold is skipped;
 * - adding to an album that does not exist is a no-op;
 * - the denylist lookup matches titles case-insensitively and trimmed, and returns only members captured at or
 *   after `since`.
 *
 * [userAlbums] is the albums other apps made: title → the normalized asset ids inside them. It is the caller's
 * own cell, which is how the world forges "this photo arrived via WhatsApp" without PhotoKit (capability
 * `photo-selection-policy`). Albums this fake creates get deterministic ids (`album-<n>`).
 *
 * State arrives by constructor, per the fake-honesty rule. Levers and inspection belong in `:test:world`
 * wrappers.
 */
internal class InMemoryAlbumManager(
    private val library: StateFlow<List<RawAsset>>,
    private val userAlbums: MutableStateFlow<Map<String, Set<String>>> = MutableStateFlow(emptyMap()),
) : AlbumManager {

    private class Album(val title: String, val members: MutableSet<String> = mutableSetOf())

    private var counter = 0
    private val created = mutableMapOf<String, Album>()

    override suspend fun ensureCreated(name: String): String {
        val id = "album-${counter++}"
        created[id] = Album(name)
        return id
    }

    override suspend fun exists(albumLocalId: String): Boolean = albumLocalId in created

    override suspend fun add(albumLocalId: String, rawLocalIds: List<String>) {
        val album = created[albumLocalId] ?: return
        val held = library.value.mapTo(mutableSetOf()) { it.facts.assetId }
        album.members += rawLocalIds.map(::normalizeAssetId).filter { it in held }
    }

    override suspend fun assetIdsInAlbums(titles: Set<String>, since: String): Set<String> {
        if (titles.isEmpty()) return emptySet()
        val capturedSince = library.value.filter { it.creationDate >= since }.mapTo(mutableSetOf()) { it.facts.assetId }
        val albums = created.values.map { it.title to it.members } + userAlbums.value.toList()
        return albums
            .filter { (title, _) -> titles.any { it.equals(title.trim(), ignoreCase = true) } }
            .flatMapTo(mutableSetOf()) { (_, members) -> members.filter { it in capturedSince } }
    }
}
