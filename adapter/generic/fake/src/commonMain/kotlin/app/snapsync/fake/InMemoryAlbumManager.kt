package app.snapsync.fake

import app.snapsync.ports.AlbumManager
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The honest in-memory [AlbumManager] (capability `event-album`), held to `AlbumManagerContract` exactly as
 * `IosAlbumManager` is.
 *
 * [userAlbums] is the albums other apps made: title → the normalized asset ids inside them. It is the
 * caller's own cell, which is how the world forges "this photo arrived via WhatsApp" without PhotoKit
 * (capability `photo-selection-policy`). Albums this fake creates get deterministic ids (`album-<n>`), so a
 * test can refer to them by index rather than by time.
 *
 * State arrives by constructor, per the fake-honesty rule. Levers and inspection belong in `:test:world`
 * wrappers.
 */
internal class InMemoryAlbumManager(
    private val userAlbums: MutableStateFlow<Map<String, Set<String>>> = MutableStateFlow(emptyMap()),
) : AlbumManager {

    private var counter = 0
    private val live = mutableSetOf<String>()

    override suspend fun ensureCreated(name: String): String {
        val id = "album-${counter++}"
        live.add(id)
        return id
    }

    override suspend fun exists(albumLocalId: String): Boolean = albumLocalId in live

    override suspend fun add(albumLocalId: String, rawLocalIds: List<String>) = Unit

    /** Mirrors the real seam: case-insensitive exact title match over the [userAlbums]. */
    override suspend fun assetIdsInAlbums(titles: Set<String>, since: String): Set<String> =
        userAlbums.value.entries
            .filter { (title, _) -> titles.any { it.equals(title.trim(), ignoreCase = true) } }
            .flatMapTo(mutableSetOf()) { it.value }
}
