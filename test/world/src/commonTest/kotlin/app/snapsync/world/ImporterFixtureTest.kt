package app.snapsync.world

import app.snapsync.ports.AssetRef
import app.snapsync.ports.ImportResult
import app.snapsync.ports.StagedResource
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The world importer's own fixture properties — the ones every download test silently depends on.
 *
 * This file exists because of a specific failure. The predecessor branch's flagship duplicate-download
 * test passed **while the duplicate was being created**: its fake reused one created-asset identifier
 * across attempts, so "the marker survived" and "the marker was destroyed and a second asset made under
 * the same identifier" were indistinguishable to every assertion. Creating that second asset *is* the harm
 * the capability exists to prevent, and no test could see it.
 *
 * A property that load-bearing may not rest on a comment. It is asserted here.
 */
class ImporterFixtureTest {

    private val ref = AssetRef("DEV-F", "FQ")

    private fun resource() = StagedResource(
        resourceKey = "FQ-primary.heic",
        role = "primary",
        contentType = "image/heic",
        originalFilename = "IMG.HEIC",
        stagedPath = "/stage/fq",
    )

    private suspend fun FakePhotoLibraryImporter.importOnce(): ImportResult =
        import(ref, listOf(resource()), "2026-06-30T10:00:00Z")

    /** Every created asset gets its own identifier, exactly as PhotoKit mints a fresh one per request. */
    @Test
    fun a_repeat_import_mints_a_different_created_identifier() = runTest {
        val importer = FakePhotoLibraryImporter(WorldGallery())

        val first = importer.importOnce() as ImportResult.Imported
        val second = importer.importOnce() as ImportResult.Imported

        assertNotEquals(
            first.createdLocalId, second.createdLocalId,
            "a second asset must be distinguishable from the first, or no test can observe a duplicate",
        )
    }

    /**
     * The exact hole that shipped: a FAILED attempt must still consume an identifier. When it did not, the
     * re-import that followed a wrongly-cleared marker minted the bare form — byte-identical to a marker a
     * test had planted by hand — and the flagship assertion passed over a live duplicate.
     */
    @Test
    fun a_failed_attempt_still_consumes_an_identifier() = runTest {
        val importer = FakePhotoLibraryImporter(WorldGallery())
        importer.failNextImport = true

        assertTrue(importer.importOnce() is ImportResult.Failed)
        val afterFailure = importer.importOnce() as ImportResult.Imported

        val fresh = FakePhotoLibraryImporter(WorldGallery()).importOnce() as ImportResult.Imported
        assertNotEquals(
            fresh.createdLocalId, afterFailure.createdLocalId,
            "an import that follows a failure must not mint the identifier a first import would",
        )
    }

    /** Each created asset lands in the gallery under its own identifier, so a duplicate is countable. */
    @Test
    fun each_created_asset_is_separately_visible_in_the_gallery() = runTest {
        val gallery = WorldGallery()
        val importer = FakePhotoLibraryImporter(gallery)

        importer.importOnce()
        importer.importOnce()

        assertTrue(
            gallery.current().map { it.assetId }.toSet().size == 2,
            "two created assets, two distinct identifiers in the library",
        )
    }
}
