package app.snapsync.services.gallery

import app.snapsync.model.AlbumId
import app.snapsync.model.AlbumKind
import app.snapsync.model.AlbumRecord
import app.snapsync.model.AssetFacts
import app.snapsync.model.AssetId
import app.snapsync.model.GalleryRead
import app.snapsync.model.RawAsset
import app.snapsync.model.Resource
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.AssetFilter
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.LibraryGrant
import kotlin.time.Instant

/** A library a test reads only the grant of: [grant] is asked on every read, and any other call fails. */
class GrantOnlyReader(private val grant: () -> LibraryGrant) : GalleryReader {
    override fun access(): LibraryGrant = grant()
    override suspend fun assets(filter: AssetFilter): GalleryRead<List<AssetFacts>> = error("not used")
    override suspend fun assetsById(ids: Set<AssetId>): GalleryRead<List<AssetFacts>> = error("not used")
    override suspend fun resources(ids: Set<AssetId>): GalleryRead<List<RawAsset>> = error("not used")
    override suspend fun albums(): GalleryRead<List<AlbumRecord>> = error("not used")
    override suspend fun albumsById(ids: Set<AlbumId>): GalleryRead<List<AlbumRecord>> = error("not used")
    override suspend fun albumMembers(
        album: AlbumId,
        createdFrom: Instant?,
    ): GalleryRead<Set<AssetId>> = error("not used")
    override val albumKind: AlbumKind = AlbumKind.COLLECTION
    override suspend fun createAlbum(title: String): AlbumId? = error("not used")
    override suspend fun addToAlbum(album: AlbumId, assets: Set<AssetId>): WriteOutcome = error("not used")
    override suspend fun export(resource: Resource, to: String): WriteOutcome = error("not used")
}
