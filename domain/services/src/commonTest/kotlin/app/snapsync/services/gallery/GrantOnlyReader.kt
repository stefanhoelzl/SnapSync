package app.snapsync.services.gallery

import app.snapsync.model.WriteOutcome
import app.snapsync.ports.AssetFilter
import app.snapsync.ports.GalleryAlbum
import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.LibraryAlbumKind
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAlbumId
import app.snapsync.ports.PlatformAssetId
import kotlin.time.Instant

/** A library a test reads only the grant of: [grant] is asked on every read, and any other call fails. */
class GrantOnlyReader(private val grant: () -> LibraryGrant) : GalleryReader {
    override fun access(): LibraryGrant = grant()
    override suspend fun assets(filter: AssetFilter): GalleryRead<List<GalleryAsset>> = error("not used")
    override suspend fun assetsById(ids: Set<PlatformAssetId>): GalleryRead<List<GalleryAsset>> = error("not used")
    override suspend fun resources(ids: Set<PlatformAssetId>): GalleryRead<List<GalleryAssetResources>> = error(
        "not used",
    )
    override suspend fun albums(): GalleryRead<List<GalleryAlbum>> = error("not used")
    override suspend fun albumsById(ids: Set<PlatformAlbumId>): GalleryRead<List<GalleryAlbum>> = error("not used")
    override suspend fun albumMembers(
        album: PlatformAlbumId,
        createdFrom: Instant?,
    ): GalleryRead<Set<PlatformAssetId>> = error("not used")
    override val albumKind: LibraryAlbumKind = LibraryAlbumKind.COLLECTION
    override suspend fun createAlbum(title: String): PlatformAlbumId? = error("not used")
    override suspend fun addToAlbum(
        album: PlatformAlbumId,
        assets: Set<PlatformAssetId>,
    ): WriteOutcome = error("not used")
    override suspend fun export(resource: GalleryResourceHandle, to: String): WriteOutcome = error("not used")
}
