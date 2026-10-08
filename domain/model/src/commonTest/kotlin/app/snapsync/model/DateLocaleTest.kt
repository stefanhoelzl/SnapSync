package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** [resolveDateLocale]: the one rule every platform's `DateFormatting` applies to the tag it is handed. */
class DateLocaleTest {

    @Test
    fun `a bare language keeps the device region and the device own language keeps the device locale`() {
        assertEquals(
            DateLocale.Explicit("en", "DE"),
            resolveDateLocale("en", deviceLanguage = "de", deviceRegion = "DE"),
        )
        assertEquals(DateLocale.Device, resolveDateLocale("en", deviceLanguage = "en", deviceRegion = "US"))
        assertEquals(DateLocale.Device, resolveDateLocale("EN", deviceLanguage = "en", deviceRegion = "US"))
        assertEquals(DateLocale.Explicit("en", null), resolveDateLocale("en", deviceLanguage = "de", deviceRegion = ""))
        assertEquals(
            DateLocale.Explicit("en", null),
            resolveDateLocale("en", deviceLanguage = "de", deviceRegion = null),
        )
    }

    @Test
    fun `a tag with a region is taken as it is`() {
        assertEquals(
            DateLocale.Explicit("en", "US"),
            resolveDateLocale("en-US", deviceLanguage = "de", deviceRegion = "DE"),
        )
        assertEquals(
            DateLocale.Explicit("de", "AT"),
            resolveDateLocale("de_AT", deviceLanguage = "de", deviceRegion = "DE"),
        )
    }

    @Test
    fun `no tag or a blank one is the device own locale`() {
        assertEquals(DateLocale.Device, resolveDateLocale(null, deviceLanguage = "de", deviceRegion = "DE"))
        assertEquals(DateLocale.Device, resolveDateLocale(" ", deviceLanguage = "de", deviceRegion = "DE"))
    }
}
