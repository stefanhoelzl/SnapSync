package app.snapsync.presentation

import app.snapsync.model.DateFormats
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** [ScreenDates] answers the platform's formatting in the language it is asked, unchanged. */
class ScreenDatesTest {

    @Test
    fun `it asks the platform's formatting in the language the screen names`() {
        val asked = mutableListOf<String?>()
        val dates = ScreenDates { tag ->
            asked += tag
            DateFormats { value, skeleton -> "$tag $skeleton $value" }
        }
        val at = LocalDateTime(2026, 10, 5, 14, 30)
        assertEquals("de yMMMd 2026-10-05T14:30", dates.formats("de").format(at, "yMMMd"))
        assertEquals(listOf<String?>("de"), asked)
    }
}
