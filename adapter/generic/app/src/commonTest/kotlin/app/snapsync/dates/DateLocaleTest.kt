package app.snapsync.dates

import app.snapsync.ports.DateLocale
import kotlin.test.Test
import kotlin.test.assertEquals

/** [DateLocales.resolve]: the one rule every platform's `DateFormatting` applies to the tag it is handed. */
class DateLocaleTest {

    @Test
    fun `a bare language keeps the device region and the device own language keeps the device locale`() {
        assertEquals(
            DateLocale.Explicit("en", "DE"),
            DateLocales.resolve("en", deviceLanguage = "de", deviceRegion = "DE"),
        )
        assertEquals(DateLocale.Device, DateLocales.resolve("en", deviceLanguage = "en", deviceRegion = "US"))
        assertEquals(DateLocale.Device, DateLocales.resolve("EN", deviceLanguage = "en", deviceRegion = "US"))
        assertEquals(
            DateLocale.Explicit("en", null),
            DateLocales.resolve("en", deviceLanguage = "de", deviceRegion = ""),
        )
        assertEquals(
            DateLocale.Explicit("en", null),
            DateLocales.resolve("en", deviceLanguage = "de", deviceRegion = null),
        )
    }

    @Test
    fun `a tag with a region is taken as it is`() {
        assertEquals(
            DateLocale.Explicit("en", "US"),
            DateLocales.resolve("en-US", deviceLanguage = "de", deviceRegion = "DE"),
        )
        assertEquals(
            DateLocale.Explicit("de", "AT"),
            DateLocales.resolve("de_AT", deviceLanguage = "de", deviceRegion = "DE"),
        )
    }

    @Test
    fun `no tag or a blank one is the device own locale`() {
        assertEquals(DateLocale.Device, DateLocales.resolve(null, deviceLanguage = "de", deviceRegion = "DE"))
        assertEquals(DateLocale.Device, DateLocales.resolve(" ", deviceLanguage = "de", deviceRegion = "DE"))
    }
}
