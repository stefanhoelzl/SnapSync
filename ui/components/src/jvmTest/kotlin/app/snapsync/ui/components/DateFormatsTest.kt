package app.snapsync.ui.components

import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The JVM's [DateFormats] (`docs/architecture.md`, "Localization"): a skeleton reads as each locale writes it,
 * so the design system names fields, never an order, a month name or an hour cycle.
 */
class DateFormatsTest {
    private val at = LocalDateTime(2026, 10, 5, 14, 30)

    @Test
    fun `a skeleton follows the locale's order, names and hour cycle`() {
        assertEquals("5 Oct 2026", DateFormats("en-GB").format(at, "yMMMd"))
        assertEquals("Oct 5, 2026", DateFormats("en-US").format(at, "yMMMd"))
        assertEquals("14:30", DateFormats("en-GB").format(at, "jm"))
        assertEquals("2:30 PM", DateFormats("en-US").format(at, "jm"))
        assertEquals("Mo., 5. Okt.", DateFormats("de-DE").format(at, "MMMEd"))
        assertEquals("Monday", DateFormats("en-GB").format(at, "EEEE"))
        assertEquals("Oktober", DateFormats("de-DE").format(at, "MMMM"))
    }

    @Test
    fun `a bare language keeps the device's region, and the device's own language keeps the device locale`() {
        assertEquals(
            DateLocale.Explicit("en", "DE"),
            resolveDateLocale("en", deviceLanguage = "de", deviceRegion = "DE"),
        )
        assertEquals(DateLocale.Device, resolveDateLocale("en", deviceLanguage = "en", deviceRegion = "US"))
        assertEquals(
            DateLocale.Explicit("en", "US"),
            resolveDateLocale("en-US", deviceLanguage = "de", deviceRegion = "DE"),
        )
        assertEquals(DateLocale.Device, resolveDateLocale(null, deviceLanguage = "de", deviceRegion = "DE"))
        assertEquals(DateLocale.Explicit("en", null), resolveDateLocale("en", deviceLanguage = "de", deviceRegion = ""))
    }
}
