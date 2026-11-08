package app.snapsync.data.receiving

import app.snapsync.data.receiving.LibraryImport.Companion.reportOf
import app.snapsync.domain.album.EventAlbumId
import app.snapsync.domain.album.ImportAlbumVerdict
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.receiving.ImportReport
import app.snapsync.domain.receiving.MintsStagedDownload
import app.snapsync.domain.receiving.ReceivedPhotoName
import app.snapsync.domain.receiving.SenderFilename
import app.snapsync.domain.receiving.StagedDownload
import app.snapsync.domain.receiving.StagedPath
import app.snapsync.domain.receiving.StagedResource
import app.snapsync.format.TimestampText
import app.snapsync.ports.FileResult
import app.snapsync.ports.GalleryImport
import app.snapsync.ports.GalleryImportRequest
import app.snapsync.ports.ImportOutcome
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.ResourceKind
import app.snapsync.testids.testAssetId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A received photo's import (capability `receiving-photos`): each staged resource located on this device, named with
 * its ref's SnapSync mark — the name the library stores, by which a later install recognises it — and given the kind
 * its role and type say; the ref travels as the correlation both of the library's deliveries hand back, and the
 * PhotoKit blocks' marker writes are guarded on the row they were decided for.
 */
class LibraryImportTest {
    private val files = ScriptedFiles()
    private val library = RecordingImport()
    private val import = LibraryImport(library, StagingArea(files))
    private val a = ref("A")

    @OptIn(MintsStagedDownload::class)
    private fun download(vararg resources: StagedResource, captured: CaptureDate = captured()) =
        StagedDownload(a, captured, resources.toList())

    private fun staged(key: String, role: String = "primary", type: String = "image/heic", name: String = "IMG_1.HEIC") =
        StagedResource(
            key,
            role,
            type,
            checkNotNull(SenderFilename.parse(name)),
            StagedPath("download-staging/e/${a.sourceDeviceId.value}/$key"),
        )

    @Test
    fun `the import names each file with the mark and gives each the kind its role and type say`() = runTest {
        library.outcome = ImportOutcome.Imported(PlatformAssetId("LOCAL-A"))

        val report = import.import(
            download(
                staged("a-primary.heic"),
                staged("a-live.mov", role = "live", type = "video/quicktime", name = "IMG_1.MOV"),
                staged("a-unknown.bin", role = "thumbnail"),
                staged("a-doc.pdf", type = "application/pdf"),
            ),
            album = ImportAlbumVerdict.Into(EventAlbumId("album-1")),
        )

        assertEquals(testAssetId("LOCAL-A"), assertIs<ImportReport.Imported>(report).created)
        val request = library.requests.single()
        assertEquals(ImportCorrelation.of(a), request.correlation)
        assertEquals(TimestampText.parse(DURING).at, request.created)
        assertEquals("album-1", request.album?.value)
        assertEquals(listOf(ResourceKind.PHOTO, ResourceKind.PAIRED_VIDEO), request.files.map { it.kind })
        assertEquals(
            listOf(ReceivedPhotoName.token(a), ReceivedPhotoName.token(a)),
            request.files.map { ReceivedPhotoName.tokenOf(it.name) },
        )
        assertEquals("mem:/shared/download-staging/e/${a.sourceDeviceId.value}/a-primary.heic", request.files[0].path)
    }

    @Test
    fun `a video is a video, an undated photo carries no date, and no album is none`() = runTest {
        library.outcome = ImportOutcome.Failed("busy", consumedResources = false, placeholder = null)

        val report = import.import(
            download(staged("a-primary.mov", type = "video/mp4"), captured = CaptureDate.Undated),
            ImportAlbumVerdict.CameraFolder,
        )

        val failed = assertIs<ImportReport.Failed>(report)
        assertEquals(false, failed.consumedResources)
        assertNull(failed.placeholder)
        val request = library.requests.single()
        assertEquals(listOf(ResourceKind.VIDEO), request.files.map { it.kind })
        assertNull(request.created)
        assertNull(request.album)
    }

    @Test
    fun `a photo with nothing the library takes is refused for good, and an unreachable area submits nothing`() =
        runTest {
            val camera = ImportAlbumVerdict.CameraFolder
            val thumbnail = download(staged("a-x.bin", role = "thumbnail"))
            val none = assertIs<ImportReport.Failed>(import.import(thumbnail, camera))
            assertEquals(true, none.consumedResources, "no later pass could import it")

            files.locate = FileResult.AreaUnavailable
            val unreachable = assertIs<ImportReport.Failed>(
                import.import(download(staged("a-primary.heic"), staged("a-live.mov", role = "live")), camera),
            )
            assertEquals(false, unreachable.consumedResources, "a later pass imports it again")
            assertEquals(emptyList(), library.requests)
        }

    @Test
    fun `the library's report reads as the domain's, its placeholder with it`() {
        assertEquals(
            testAssetId("P"),
            assertIs<ImportReport.Failed>(reportOf(ImportOutcome.Failed("x", true, PlatformAssetId("P")))).placeholder,
        )
        assertEquals(
            testAssetId("C"),
            assertIs<ImportReport.Imported>(reportOf(ImportOutcome.Imported(PlatformAssetId("C")))).created,
        )
    }

    @Test
    fun `a correlation names the ref it was made for, and one no import carried names none`() {
        assertEquals(listOf(a), ImportCorrelation.refs(ImportCorrelation.of(a)))
        assertEquals(emptyList(), ImportCorrelation.refs("not-a-device/A"))
        assertEquals(emptyList(), ImportCorrelation.refs("${ANNA.value}/no..id"))
    }

    @Test
    fun `the change block's placeholder is written onto the row, and one with no row is reported`() = runTest {
        val device = Store()
        val b = photo(ref("B"))
        device.planned(page(b))

        assertSame(
            MarkerWrite.Applied,
            device.markers.placeholder(ImportCorrelation.of(b.ref), PlatformAssetId("LOCAL-B")),
        )
        assertEquals("LOCAL-B", device.marker(b.ref))
        assertSame(
            MarkerWrite.NoRow,
            device.markers.placeholder(ImportCorrelation.of(ref("PRUNED")), PlatformAssetId("L")),
        )
        assertIs<MarkerWrite.Unknown>(device.markers.placeholder("nonsense", PlatformAssetId("L")))
        device.databases.lock()
        assertIs<MarkerWrite.Failed>(
            device.markers.placeholder(ImportCorrelation.of(b.ref), PlatformAssetId("LOCAL-B")),
        )
    }

    @Test
    fun `the completion confirms the marker it holds, or clears the placeholder a failed change left`() = runTest {
        val device = Store()
        val b = photo(ref("B"))
        val c = photo(ref("C"))
        device.planned(page(b, c))
        val bc = ImportCorrelation.of(b.ref)
        device.markers.placeholder(bc, PlatformAssetId("LOCAL-B"))
        device.markers.placeholder(ImportCorrelation.of(c.ref), PlatformAssetId("LOCAL-C"))

        assertSame(MarkerWrite.NoRow, device.markers.settled(bc, ImportOutcome.Imported(PlatformAssetId("LOCAL-OLD"))))
        assertSame(MarkerWrite.Applied, device.markers.settled(bc, ImportOutcome.Imported(PlatformAssetId("LOCAL-B"))))
        assertEquals("IMPORTED", device.state(b.ref))

        val cc = ImportCorrelation.of(c.ref)
        assertSame(MarkerWrite.Applied, device.markers.settled(cc, ImportOutcome.Failed("refused before its block")))
        assertEquals("LOCAL-C", device.marker(c.ref), "a change refused before its block left no placeholder")
        assertSame(
            MarkerWrite.Applied,
            device.markers.settled(cc, ImportOutcome.Failed("failed", placeholder = PlatformAssetId("LOCAL-C"))),
        )
        assertNull(device.marker(c.ref))
        assertIs<MarkerWrite.Unknown>(device.markers.settled("nonsense", ImportOutcome.Imported(PlatformAssetId("X"))))
    }
}

/** The library's import, scripted: every request recorded and answered [outcome]. */
internal class RecordingImport(
    var outcome: ImportOutcome = ImportOutcome.Imported(
        PlatformAssetId("LOCAL"),
    ),
) : GalleryImport {
    val requests = mutableListOf<GalleryImportRequest>()

    override suspend fun import(request: GalleryImportRequest): ImportOutcome {
        requests += request
        return outcome
    }
}
