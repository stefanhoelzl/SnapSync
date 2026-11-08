package app.snapsync.format

import app.snapsync.domain.mobiledata.MobileDataChoice
import app.snapsync.domain.mobiledata.StoredChoice
import kotlin.test.Test
import kotlin.test.assertEquals

/** The mobile-data choice's stored text — runtime identity: every device that ever chose holds `on` or `off`. */
class MobileDataTextTest {

    @Test
    fun `the choice is stored as on or off and read back`() {
        assertEquals("on", MobileDataText.spell(MobileDataChoice.ALLOWED))
        assertEquals("off", MobileDataText.spell(MobileDataChoice.WIFI_ONLY))
        assertEquals(StoredChoice.ON, MobileDataText.parse("on"))
        assertEquals(StoredChoice.OFF, MobileDataText.parse("off"))
    }

    @Test
    fun `a value no build writes is neither`() {
        val unknown = listOf("", "ON", "true", "wifi")
        for (text in unknown) assertEquals(StoredChoice.OTHER, MobileDataText.parse(text), text)
    }
}
