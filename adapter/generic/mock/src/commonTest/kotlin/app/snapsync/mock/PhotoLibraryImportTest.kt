@file:OptIn(CompositionOnly::class)

package app.snapsync.mock

import app.snapsync.contracts.inboundOf
import app.snapsync.domain.asset.AssetId
import app.snapsync.inbound.CompositionOnly
import app.snapsync.model.AssetRef
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.GalleryImportRequest
import app.snapsync.ports.ImportFile
import app.snapsync.ports.ImportOutcome
import app.snapsync.ports.ResourceKind
import app.snapsync.services.gallery.correlation
import app.snapsync.testids.testDeviceId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The photo-library mock's import properties — the ones every download test silently depends on.
 *
 * This file exists because of a specific failure. The predecessor branch's flagship duplicate-download
 * test passed **while the duplicate was being created**: its fake reused one created-asset identifier
 * across attempts, so "the marker survived" and "the marker was destroyed and a second asset made under
 * the same identifier" were indistinguishable to every assertion. Creating that second asset *is* the harm
 * the capability exists to prevent, and no test could see it.
 *
 * A property that load-bearing may not rest on a comment. It is asserted here.
 */
class PhotoLibraryImportTest {

    private val ref = AssetRef(testDeviceId("DEV-F"), AssetId("FQ"))

    private fun file() = ImportFile("/stage/fq", "image/heic", ResourceKind.PHOTO, "IMG.HEIC")

    private suspend fun Gallery.importOnce(): ImportOutcome =
        import(
            GalleryImportRequest(
                ref.correlation(),
                listOf(file()),
                Instant.parse("2026-06-30T10:00:00Z"),
                album = null,
            ),
        )

    /**
     * The marker writes are the registered handlers, so every gallery here is listened to first — an import before
     * `inbound` has nowhere to record its marker, and the mock refuses it rather than look like a working one while
     * every pass creates another asset (`docs/testing.md`).
     */
    private fun importer(library: PhotoLibraryMock = PhotoLibraryMock()) = library.port().apply {
        inbound(GalleryHandlers(onImportPlaceholder = inboundOf {}, onImportSettled = inboundOf {}))
    }

    /** Every created asset gets its own identifier, exactly as PhotoKit mints a fresh one per request. */
    @Test
    fun a_repeat_import_mints_a_different_created_identifier() = runTest {
        val importer = importer()

        val first = importer.importOnce() as ImportOutcome.Imported
        val second = importer.importOnce() as ImportOutcome.Imported

        assertNotEquals(
            first.created,
            second.created,
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
        val library = PhotoLibraryMock()
        val importer = importer(library)
        library.operator.imports.failNextImport = true

        assertTrue(importer.importOnce() is ImportOutcome.Failed)
        val afterFailure = importer.importOnce() as ImportOutcome.Imported

        val fresh = importer().importOnce() as ImportOutcome.Imported
        assertNotEquals(
            fresh.created,
            afterFailure.created,
            "an import that follows a failure must not mint the identifier a first import would",
        )
    }

    /** Each created asset lands in the gallery under its own identifier, so a duplicate is countable. */
    @Test
    fun each_created_asset_is_separately_visible_in_the_gallery() = runTest {
        val library = PhotoLibraryMock()
        val importer = importer(library)

        importer.importOnce()
        importer.importOnce()

        assertTrue(
            library.operator.current().map { it.id }.toSet().size == 2,
            "two created assets, two distinct identifiers in the library",
        )
    }
}
