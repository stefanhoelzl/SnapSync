package app.snapsync.format

import app.snapsync.domain.upload.OsSupport
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the dump names as the uploaders this OS carries — runtime identity: the operator's reports read it. */
class UploadersCarriedTextTest {

    @Test
    fun `the dump names the extension beside the app only where the OS carries it`() {
        assertEquals("app+extension", UploadersCarriedText.of(OsSupport.CARRIED))
        assertEquals("app", UploadersCarriedText.of(OsSupport.NOT_CARRIED))
    }
}
