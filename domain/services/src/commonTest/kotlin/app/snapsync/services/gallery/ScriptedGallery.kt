package app.snapsync.services.gallery

import app.snapsync.ports.AssetFilter
import app.snapsync.ports.GalleryAlbum
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.LibraryAlbumKind
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAlbumId
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.WriteOutcome
import kotlin.time.Instant

/**
 * A scripted stand-in for the thin [GalleryReader] that records every call, because what the gallery services must
 * NOT ask the gallery is as much the point as what they answer.
 */
internal class ScriptedGallery(
    var access: LibraryGrant = LibraryGrant.GRANTED,
    var readable: Boolean = true,
    var assets: List<GalleryAssetResources> = emptyList(),
    var albums: List<GalleryAlbum> = emptyList(),
    var created: String? = "album-new",
    var addOutcome: WriteOutcome = WriteOutcome.Ok,
) : GalleryReader {
    val calls = mutableListOf<String>()
    val adds = mutableListOf<Pair<String, Set<PlatformAssetId>>>()

    private fun <T> read(name: String, value: () -> T): GalleryRead<T> {
        calls += name
        return if (readable) GalleryRead.Read(value()) else GalleryRead.NotReadable
    }

    override fun access(): LibraryGrant = access
    override suspend fun assets(filter: AssetFilter) = read("assets") { assets.map { it.asset } }
    override suspend fun assetsById(ids: Set<PlatformAssetId>) =
        read("assetsById") { assets.map { it.asset }.filter { it.id in ids } }
    override suspend fun resources(ids: Set<PlatformAssetId>) =
        read("resources(${ids.map { it.value }})") { assets.filter { it.asset.id in ids } }
    override suspend fun albums() = read("albums") { albums }
    override suspend fun albumsById(
        ids: Set<PlatformAlbumId>,
    ) = read("albumsById") { albums.filter { it.id in ids } }
    override suspend fun albumMembers(album: PlatformAlbumId, createdFrom: Instant?) =
        read("albumMembers(${album.value})") { emptySet<PlatformAssetId>() }
    override val albumKind: LibraryAlbumKind = LibraryAlbumKind.COLLECTION
    override suspend fun createAlbum(title: String): PlatformAlbumId? =
        created?.let(::PlatformAlbumId).also { calls += "createAlbum($title)" }
    override suspend fun addToAlbum(album: PlatformAlbumId, assets: Set<PlatformAssetId>): WriteOutcome {
        adds += album.value to assets
        return addOutcome
    }

    override suspend fun export(resource: GalleryResourceHandle, to: String): WriteOutcome =
        WriteOutcome.Failed("no export in this script")
}
