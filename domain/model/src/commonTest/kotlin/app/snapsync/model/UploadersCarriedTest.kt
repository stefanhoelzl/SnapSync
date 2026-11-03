package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** What the dump names as the uploaders this OS carries. */
class UploadersCarriedTest {

    @Test
    fun `the dump names the extension beside the app only where the OS carries it`() {
        assertEquals("app+extension", uploadersCarried(osSupportsOsDrivenUpload = true))
        assertEquals("app", uploadersCarried(osSupportsOsDrivenUpload = false))
    }
}
