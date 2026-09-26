package app.snapsync.services.staging

import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.FileTail
import app.snapsync.ports.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Staging a finished download (capability `receiving-photos`): the platform's temporary file is taken over by `Files`'
 * adopt, into the shared area, under the relative path — and a take-over that fails keeps nothing, so the resource is
 * downloaded again rather than recorded as staged.
 */
class StagingServiceStageTest {

    /** A `Files` whose adopt answers [adopted], recording where it was asked to put what. */
    private class Adopting(private val adopted: FileResult<Unit>) : Files {
        val adoptions = mutableListOf<Triple<String, FileArea, String>>()
        override fun read(area: FileArea, path: String): FileResult<ByteArray> = FileResult.NotFound
        override fun readTail(area: FileArea, path: String, maxBytes: Int): FileResult<FileTail> = FileResult.NotFound
        override fun write(area: FileArea, path: String, bytes: ByteArray): FileResult<Unit> = FileResult.Ok(Unit)
        override fun delete(area: FileArea, path: String): FileResult<Unit> = FileResult.NotFound
        override fun exists(area: FileArea, path: String): FileResult<Boolean> = FileResult.Ok(false)
        override fun locate(area: FileArea, path: String): FileResult<String> = FileResult.Ok(path)
        override fun move(area: FileArea, from: String, to: String): FileResult<Unit> = FileResult.NotFound
        override fun adopt(osPath: String, area: FileArea, to: String): FileResult<Unit> {
            adoptions += Triple(osPath, area, to)
            return adopted
        }
    }

    @Test
    fun `a finished body is taken over into the shared area under its relative path`() {
        val files = Adopting(FileResult.Ok(Unit))
        assertTrue(StagingService(files).stage("/tmp/CFNetworkDownload_x.tmp", "$DOWNLOAD_STAGING_DIR/D/k"))
        assertEquals(listOf(Triple("/tmp/CFNetworkDownload_x.tmp", FileArea.SHARED, "$DOWNLOAD_STAGING_DIR/D/k")), files.adoptions)
    }

    @Test
    fun `a take-over that fails keeps nothing`() {
        assertFalse(StagingService(Adopting(FileResult.Denied("locked"))).stage("/tmp/x", "$DOWNLOAD_STAGING_DIR/D/k"))
        assertFalse(StagingService(Adopting(FileResult.NotFound)).stage("/tmp/x", "$DOWNLOAD_STAGING_DIR/D/k"))
    }
}
