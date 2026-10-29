package app.snapsync.gallery

import app.snapsync.mock.InMemoryResource
import app.snapsync.mock.LibraryAssets
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.RESOURCE_META_MIME
import app.snapsync.model.RESOURCE_META_ORIGINAL_FILENAME
import app.snapsync.model.assetIdFromUploadKey
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.ResourceKind
import app.snapsync.services.gallery.galleryHandle
import app.snapsync.services.gallery.resourcesFrom
import app.snapsync.testids.testAssetId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The gallery port's assets as the upload cycle's resources ([resourcesFrom]), over the in-memory library: role keys,
 * metadata, and the platform's handle.
 */
class GalleryResourceMappingTest {

    private fun resource(kind: ResourceKind, mime: String, name: String) = LibraryAssets.Resource(kind, mime, name)

    @Test
    fun maps_each_original_to_a_role_key_with_its_metadata() {
        val asset = LibraryAssets.photo(
            "ABC/L0/001",
            "2026-07-01T00:00:00Z",
            resources = listOf(
                resource(ResourceKind.PHOTO, "image/heic", "IMG_0001.HEIC"),
                resource(ResourceKind.PAIRED_VIDEO, "video/quicktime", "IMG_0001.MOV"),
            ),
        )

        val resources = resourcesFrom(listOf(asset))

        assertEquals(2, resources.size)
        val primary = resources.first { it.filename.endsWith("-primary.heic") }
        assertEquals(testAssetId("ABC_L0_001"), primary.assetId, "the app's id of the platform's")
        assertEquals("ABC_L0_001-primary.heic", primary.filename)
        // The resolved MIME, not a platform type identifier: `contentType` is what the upload provider sends as the
        // `Content-Type` header.
        assertEquals("image/heic", primary.contentType)
        assertEquals(primary.metadata[RESOURCE_META_MIME], primary.contentType, "one content type, not two")
        assertEquals("2026-07-01T00:00:00Z", primary.metadata[RESOURCE_META_CREATION_DATE])
        assertEquals("IMG_0001.HEIC", primary.metadata[RESOURCE_META_ORIGINAL_FILENAME])
        val live = resources.first { it.filename.endsWith("-live.mov") }
        assertEquals("ABC_L0_001-live.mov", live.filename)
        assertEquals("video/quicktime", live.metadata[RESOURCE_META_MIME])
    }

    @Test
    fun the_platform_handle_rides_into_resource_data_uninterpreted() {
        val resources = resourcesFrom(listOf(LibraryAssets.photo("A")))
        assertEquals(InMemoryResource(PlatformAssetId("A")), resources.single().data.galleryHandle())
    }

    @Test
    fun mapped_filename_round_trips_to_the_asset_id() {
        // The discovery -> key -> parse identity echo-suppression and reconstruct rely on.
        val resources =
            resourcesFrom(
                listOf(LibraryAssets.photo("ABC/L0/001", resources = listOf(LibraryAssets.primaryResource("x.JPG")))),
            )
        assertEquals(testAssetId("ABC_L0_001"), assetIdFromUploadKey(resources.single().filename))
    }
}
