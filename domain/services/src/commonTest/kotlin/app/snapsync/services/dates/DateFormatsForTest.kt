package app.snapsync.services.dates

import app.snapsync.model.DateFormats
import app.snapsync.model.DateLocale
import app.snapsync.ports.DateFormatting
import app.snapsync.ports.DeviceLocale
import kotlin.test.Test
import kotlin.test.assertEquals

/** The locale a screen formats in, resolved once from its language over the device's own. */
class DateFormatsForTest {

    private class Platform(private val device: DeviceLocale) : DateFormatting {
        val asked = mutableListOf<DateLocale>()
        override fun deviceLocale() = device
        override fun formats(locale: DateLocale): DateFormats {
            asked += locale
            return DateFormats { _, skeleton -> skeleton }
        }
    }

    @Test
    fun `a screen in another language formats it in the device's region - and in the device's own language the device's locale`() {
        val platform = Platform(DeviceLocale("de", "DE"))
        platform.formatsFor("en")
        platform.formatsFor("de")
        platform.formatsFor(null)
        platform.formatsFor("en-US")
        assertEquals(
            listOf(
                DateLocale.Explicit("en", "DE"),
                DateLocale.Device,
                DateLocale.Device,
                DateLocale.Explicit("en", "US"),
            ),
            platform.asked,
        )
    }
}
