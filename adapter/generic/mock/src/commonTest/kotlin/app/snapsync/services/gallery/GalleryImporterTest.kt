package app.snapsync.services.gallery

import app.snapsync.mock.inMemoryFiles
import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.model.ImportRequest
import app.snapsync.model.ImportResult
import app.snapsync.model.ReceivedPhotoName
import app.snapsync.model.ResourceRole
import app.snapsync.model.StagedResource
import app.snapsync.ports.GalleryImport
import app.snapsync.ports.GalleryImportRequest
import app.snapsync.ports.ImportOutcome
import app.snapsync.ports.PlatformAlbumId
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.ResourceKind
import app.snapsync.services.staging.StagingService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The one translation of the app's import request into the library's ([GalleryImporter]): located paths, the ref's mark
 * on every name, a kind per resource, the ref as the correlation, and what comes back read under the app's ids.
 */
class GalleryImporterTest {

    private val ref = AssetRef("DEVICE-A", AssetId("ASSET_1"))

    /** A library that records the one request it is given and answers [answer]. */
    private class Recording(private val answer: ImportOutcome) : GalleryImport {
        val requests = mutableListOf<GalleryImportRequest>()
        override suspend fun import(request: GalleryImportRequest): ImportOutcome = answer.also { requests += request }
    }

    private fun staged(key: String, role: ResourceRole, type: String, name: String) =
        StagedResource(key, role.wire, type, name, "download-staging/$key")

    private val importer = { library: Recording -> GalleryImporter(library, StagingService(inMemoryFiles())) }

    @Test
    fun `the library is handed located files under their marked names and kinds with the ref as the correlation`() =
        runTest {
            val library = Recording(ImportOutcome.Imported(PlatformAssetId("LOCAL/L0/001")))
            val result = importer(library).import(
                ImportRequest(
                    ref,
                    listOf(
                        staged("k-primary.heic", ResourceRole.PRIMARY, "image/heic", "IMG_1.HEIC"),
                        staged("k-live.mov", ResourceRole.LIVE, "video/quicktime", "IMG_1.MOV"),
                    ),
                    "2026-06-30T10:00:00Z",
                    album = "album-1",
                ),
            )

            assertEquals(ImportResult.Imported(AssetId("LOCAL_L0_001")), result, "the outcome under the app's ids")
            val request = library.requests.single()
            assertEquals(ref, assetRefOf(request.correlation), "the correlation names the ref")
            assertEquals(Instant.parse("2026-06-30T10:00:00Z"), request.created)
            assertEquals(PlatformAlbumId("album-1"), request.album)
            assertEquals(listOf(ResourceKind.PHOTO, ResourceKind.PAIRED_VIDEO), request.files.map { it.kind })
            assertEquals(
                listOf(
                    ReceivedPhotoName.mark("IMG_1.HEIC", "k-primary.heic", ref),
                    ReceivedPhotoName.mark("IMG_1.MOV", "k-live.mov", ref),
                ),
                request.files.map { it.name },
            )
            assertEquals(
                listOf("mem:/shared/download-staging/k-primary.heic", "mem:/shared/download-staging/k-live.mov"),
                request.files.map { it.path },
            )
        }

    @Test
    fun `a video is a video and an undated or album-less request is handed on as such`() = runTest {
        val library = Recording(ImportOutcome.Failed("refused", placeholder = PlatformAssetId("P")))
        val result = importer(library).import(
            ImportRequest(ref, listOf(staged("k.mov", ResourceRole.PRIMARY, "video/mp4", "V.MP4")), "", album = null),
        )
        assertEquals(ImportResult.Failed("refused", placeholder = AssetId("P")), result)
        val request = library.requests.single()
        assertEquals(listOf(ResourceKind.VIDEO), request.files.map { it.kind })
        assertNull(request.created, "no date the library could stamp")
        assertNull(request.album)
    }

    @Test
    fun `a request with nothing the library takes is refused for good without asking it`() = runTest {
        val library = Recording(ImportOutcome.Imported(PlatformAssetId("never")))
        val result = importer(library).import(
            ImportRequest(
                ref,
                listOf(
                    staged("k.m4a", ResourceRole.PRIMARY, "audio/mp4", "A.M4A"),
                    // A role this build does not know.
                    StagedResource("k.jpg", "thumbnail", "image/jpeg", "B.JPG", "download-staging/k.jpg"),
                ),
                "2026-06-30T10:00:00Z",
                album = null,
            ),
        )
        assertTrue(
            result is ImportResult.Failed && result.consumedResources,
            "nothing could ever import it: it settles",
        )
        assertTrue(library.requests.isEmpty())
    }
}
