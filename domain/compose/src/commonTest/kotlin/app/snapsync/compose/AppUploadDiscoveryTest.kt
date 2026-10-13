@file:OptIn(AppProcessOnly::class)

package app.snapsync.compose

import app.snapsync.inbound.AppProcessOnly
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.WriteOutcome
import app.snapsync.model.noContribution
import app.snapsync.ports.AssetFilter
import app.snapsync.ports.GalleryAlbum
import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.LibraryAlbumKind
import app.snapsync.ports.LibraryChangeToken
import app.snapsync.ports.LibraryChangeTokenRead
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAlbumId
import app.snapsync.ports.PlatformAssetId
import app.snapsync.services.gallery.Discovery
import app.snapsync.services.gallery.UploadDiscovery
import app.snapsync.services.gallery.WalkMemoUse
import co.touchlab.kermit.Logger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The app process's discovery binding (decision record `changes/own-work-per-wake`, D9) serves an unchanged
 * library from the walk memo: the external-change device check (task 7.4) is recorded in the `photo-sharing` spec.
 * Switching [APP_WALK_MEMO_USE] back to SHADOW fails this test on purpose — that revert should be deliberate.
 */
class AppUploadDiscoveryTest {

    @Test
    fun `the app binding serves an unchanged library without a second walk`() = runTest {
        assertEquals(WalkMemoUse.SERVE, APP_WALK_MEMO_USE)
        var walks = 0
        val walk = object : UploadDiscovery {
            override suspend fun discover(policy: SelectionPolicy): Discovery {
                walks++
                return Discovery(emptyList(), fullEnumeration = true)
            }

            override suspend fun resourcesFor(keys: Set<String>): List<Resource> = emptyList()
        }
        val unchanged = object : LibraryChangeToken {
            override fun sameLibraryAs(other: LibraryChangeToken) = true
        }
        val tokens = object : LibraryChangeTokenRead {
            override suspend fun changeToken(): LibraryChangeToken = unchanged
        }
        val discovery = appUploadDiscovery(
            walk,
            tokens,
            FullGrant,
            Logger.withTag("test"),
        )

        discovery.discover(noContribution())
        discovery.discover(noContribution())

        assertEquals(1, walks, "an unchanged library is answered from the memo")
    }

    /** A library under a full grant whose reads the binding never makes: it asks only the grant. */
    private object FullGrant : GalleryReader {
        override fun access() = LibraryGrant.GRANTED
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
        ): GalleryRead<Set<PlatformAssetId>> =
            error("not used")
        override val albumKind = LibraryAlbumKind.COLLECTION
        override suspend fun createAlbum(title: String): PlatformAlbumId? = error("not used")
        override suspend fun addToAlbum(
            album: PlatformAlbumId,
            assets: Set<PlatformAssetId>,
        ): WriteOutcome = error("not used")
        override suspend fun export(resource: GalleryResourceHandle, to: String): WriteOutcome = error("not used")
    }
}
