package app.snapsync.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The locale a [DateFormatting] is asked for compares by what it carries, as an adapter and a test read it. */
class DateLocaleVocabularyTest {
    @Test
    fun `an explicit locale is its language and region, and never the device's own`() {
        assertEquals(DateLocale.Explicit("en", "GB"), DateLocale.Explicit("en", "GB"))
        assertNotEquals(DateLocale.Explicit("en", "GB"), DateLocale.Explicit("en", null))
        assertNotEquals<DateLocale>(DateLocale.Device, DateLocale.Explicit("en", "GB"))
        assertEquals("Device", DateLocale.Device.toString())
    }
}
