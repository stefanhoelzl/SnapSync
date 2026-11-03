package app.snapsync.domain.mobiledata

import kotlin.test.Test
import kotlin.test.assertEquals

/** The device's mobile-data choice: only nothing stored or `on` lets photos use mobile data — a doubt holds them. */
class MobileDataChoiceTest {

    @Test
    fun `only nothing stored or on allows mobile data`() {
        assertEquals(MobileDataChoice.ALLOWED, MobileDataChoice.of(StoredChoice.ABSENT))
        assertEquals(MobileDataChoice.ALLOWED, MobileDataChoice.of(StoredChoice.ON))
        for (stored in listOf(StoredChoice.OFF, StoredChoice.OTHER, StoredChoice.UNREADABLE)) {
            assertEquals(MobileDataChoice.WIFI_ONLY, MobileDataChoice.of(stored), "$stored")
        }
    }

    @Test
    fun `the choice is the networks a photo transfer may use`() {
        assertEquals(PhotoNetwork.ANY, MobileDataChoice.ALLOWED.photoNetwork())
        assertEquals(PhotoNetwork.UNRESTRICTED_ONLY, MobileDataChoice.WIFI_ONLY.photoNetwork())
    }

    @Test
    fun `an outcome carries the choice in effect`() {
        assertEquals(MobileDataChoice.WIFI_ONLY, MobileDataOutcome.Saved(MobileDataChoice.WIFI_ONLY).inEffect)
        assertEquals(MobileDataChoice.ALLOWED, MobileDataOutcome.NotSaved(MobileDataChoice.ALLOWED).inEffect)
    }
}
