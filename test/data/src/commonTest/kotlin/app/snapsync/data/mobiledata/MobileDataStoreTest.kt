package app.snapsync.data.mobiledata

import app.snapsync.data.store.StoreWrite
import app.snapsync.domain.mobiledata.MobileDataChoice
import app.snapsync.domain.mobiledata.PhotoNetwork
import app.snapsync.domain.mobiledata.StoredChoice
import app.snapsync.mock.inMemoryPreferences
import app.snapsync.ports.PrefRead
import app.snapsync.ports.Preferences
import app.snapsync.ports.TransferNetwork
import app.snapsync.ports.WriteOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The device's mobile-data choice in the shared preferences (decision record
 * `changes/archive/2026-10-07-mobile-data-per-device`): read on every call, so another process's change is seen at once.
 */
class MobileDataStoreTest {

    private val values = mutableMapOf<String, String>()

    private fun store(preferences: Preferences = inMemoryPreferences(values)) = MobileDataStore(preferences)

    @Test
    fun `untouched, photos use any network`() {
        assertEquals(MobileDataChoice.ALLOWED, store().read())
        assertEquals(StoredChoice.ABSENT, store().stored())
        assertEquals(TransferNetwork.ANY, store().transferNetwork())
    }

    @Test
    fun `a written choice is what every instance reads`() {
        assertEquals(StoreWrite.Written, store().write(MobileDataChoice.WIFI_ONLY))
        assertEquals(MobileDataChoice.WIFI_ONLY, store().read())
        assertEquals(PhotoNetwork.UNRESTRICTED_ONLY, store().photoNetwork())
        assertEquals(TransferNetwork.UNRESTRICTED_ONLY, store().transferNetwork())
        assertEquals("off", values.values.single())
    }

    @Test
    fun `a choice the phone cannot read, or one no build writes, holds photos to Wi-Fi`() {
        val locked = object : Preferences by inMemoryPreferences(values) {
            override fun get(key: String): PrefRead = PrefRead.Unavailable("locked")
        }
        assertEquals(StoredChoice.UNREADABLE, store(locked).stored())
        assertEquals(MobileDataChoice.WIFI_ONLY, store(locked).read())

        store().write(MobileDataChoice.ALLOWED)
        values.keys.forEach { values[it] = "maybe" }
        assertEquals(StoredChoice.OTHER, store().stored())
        assertEquals(MobileDataChoice.WIFI_ONLY, store().read())
    }

    @Test
    fun `a write the store refuses answers so`() {
        val refusing = object : Preferences by inMemoryPreferences(values) {
            override fun set(key: String, value: String): WriteOutcome = WriteOutcome.Failed("locked")
        }
        assertEquals(StoreWrite.Failed("locked"), store(refusing).write(MobileDataChoice.WIFI_ONLY))
        assertEquals(MobileDataChoice.ALLOWED, store().read())
    }
}
