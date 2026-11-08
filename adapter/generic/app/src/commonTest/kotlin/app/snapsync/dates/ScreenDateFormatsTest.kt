package app.snapsync.dates

import app.snapsync.ports.DateFormats
import app.snapsync.ports.DateFormatting
import app.snapsync.ports.DateLocale
import app.snapsync.ports.DeviceLocale
import kotlin.test.Test
import kotlin.test.assertEquals

/** The locale a screen formats in, resolved once from its language over the device's own. */
class ScreenDateFormatsTest {

    private class Platform(private val device: DeviceLocale) : DateFormatting {
        val asked = mutableListOf<DateLocale>()
        override fun deviceLocale() = device
        override fun formats(locale: DateLocale): DateFormats {
            asked += locale
            return object : DateFormats {
                override fun format(value: kotlinx.datetime.LocalDateTime, skeleton: String) = skeleton
            }
        }
    }

    @Test
    fun `a screen in another language formats it in the device's region - and in the device's own language the device's locale`() {
        val platform = Platform(DeviceLocale("de", "DE"))
        DateLocales.forScreen(platform, "en")
        DateLocales.forScreen(platform, "de")
        DateLocales.forScreen(platform, null)
        DateLocales.forScreen(platform, "en-US")
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
