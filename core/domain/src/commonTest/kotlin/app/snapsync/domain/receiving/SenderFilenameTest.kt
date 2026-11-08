package app.snapsync.domain.receiving

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SenderFilenameTest {
    @Test
    fun an_empty_name_is_no_sender_filename() {
        assertNull(SenderFilename.parse(""))
    }

    @Test
    fun a_name_is_kept_verbatim() {
        assertEquals("IMG_4471.HEIC", SenderFilename.parse("IMG_4471.HEIC")?.value)
        assertEquals(" ", SenderFilename.parse(" ")?.value)
    }
}
