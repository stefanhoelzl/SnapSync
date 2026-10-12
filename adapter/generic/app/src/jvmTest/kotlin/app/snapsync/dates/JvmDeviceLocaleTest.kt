package app.snapsync.dates

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** The device's locale as the JVM states it: a locale with no country has no region, never an empty one. */
class JvmDeviceLocaleTest {

    @Test
    fun `a device locale with no country states no region`() {
        val before = Locale.getDefault(Locale.Category.FORMAT)
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.of("de"))
            val device = JvmDateFormatting().deviceLocale()
            assertEquals("de" to null, device.language to device.region)
            Locale.setDefault(Locale.Category.FORMAT, Locale.of("de", "AT"))
            assertEquals("AT", JvmDateFormatting().deviceLocale().region)
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, before)
        }
    }
}
