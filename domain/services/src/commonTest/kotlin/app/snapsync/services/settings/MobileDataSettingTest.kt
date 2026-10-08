package app.snapsync.services.settings

import app.snapsync.model.PrefRead
import app.snapsync.model.TransferNetwork
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The device's mobile-data choice (capability `mobile-data`; decision record `changes/archive/2026-10-07-mobile-data-per-device`, D1). */
class MobileDataSettingTest {

    private class Prefs : Preferences {
        val values = mutableMapOf<String, String>()
        var unreadable = false
        var refuseWrites = false
        var refuseRemoves = false
        override fun get(key: String): PrefRead = when {
            unreadable -> PrefRead.Unavailable("suite not opened")
            else -> values[key]?.let(PrefRead::Value) ?: PrefRead.Absent
        }
        override fun set(key: String, value: String): WriteOutcome =
            if (refuseWrites) WriteOutcome.Failed("refused") else WriteOutcome.Ok.also { values[key] = value }
        override fun remove(key: String): WriteOutcome =
            if (refuseRemoves) WriteOutcome.Failed("refused") else WriteOutcome.Ok.also { values.remove(key) }
    }

    private val prefs = Prefs()

    @Test
    fun `nothing stored lets photos use any network`() {
        val setting = MobileDataSetting(prefs)
        assertTrue(setting.allowed.value)
        assertEquals(TransferNetwork.ANY, setting.transferNetwork())
        assertEquals("on", setting.describe())
    }

    @Test
    fun `turned off photos wait for an unrestricted network`() {
        val setting = MobileDataSetting(prefs)
        assertTrue(setting.set(false))
        assertFalse(setting.allowed.value)
        assertEquals(TransferNetwork.UNRESTRICTED_ONLY, setting.transferNetwork())
        assertEquals("off", setting.describe())
    }

    @Test
    fun `turned back on photos use any network again and the choice is kept`() {
        val setting = MobileDataSetting(prefs)
        setting.set(false)
        assertTrue(setting.set(true))
        assertTrue(setting.allowed.value)
        assertEquals(TransferNetwork.ANY, setting.transferNetwork())
        assertEquals("on", MobileDataSetting(prefs).describe(), "stored, not merely the default")
        assertEquals("on", prefs.values[MOBILE_DATA_KEY])
    }

    @Test
    fun `a choice made by another process is read on the next transfer`() {
        val setting = MobileDataSetting(prefs)
        MobileDataSetting(prefs).set(false)
        assertEquals(TransferNetwork.UNRESTRICTED_ONLY, setting.transferNetwork())
    }

    @Test
    fun `a stored choice seeds the app's view`() {
        prefs.values[MOBILE_DATA_KEY] = "off"
        assertFalse(MobileDataSetting(prefs).allowed.value)
    }

    @Test
    fun `an unreadable choice holds photos to Wi-Fi and shows the default`() {
        prefs.unreadable = true
        val setting = MobileDataSetting(prefs)
        assertTrue(setting.allowed.value)
        assertEquals(TransferNetwork.UNRESTRICTED_ONLY, setting.transferNetwork())
        assertEquals("unreadable", setting.describe())
    }

    @Test
    fun `a malformed value holds photos to Wi-Fi`() {
        prefs.values[MOBILE_DATA_KEY] = "maybe"
        assertEquals(TransferNetwork.UNRESTRICTED_ONLY, MobileDataSetting(prefs).transferNetwork())
    }

    @Test
    fun `a refused write changes nothing`() {
        val setting = MobileDataSetting(prefs)
        prefs.refuseWrites = true
        assertFalse(setting.set(false))
        assertTrue(setting.allowed.value)
        assertEquals(TransferNetwork.ANY, setting.transferNetwork())
    }

    @Test
    fun `a clear that cannot be written changes nothing`() {
        val setting = MobileDataSetting(prefs)
        setting.set(false)
        prefs.refuseRemoves = true
        setting.clear()
        assertFalse(setting.allowed.value)
        assertEquals(TransferNetwork.UNRESTRICTED_ONLY, setting.transferNetwork())
    }

    @Test
    fun `clearing returns to the default`() {
        val setting = MobileDataSetting(prefs)
        setting.set(false)
        setting.clear()
        assertTrue(setting.allowed.value)
        assertEquals(TransferNetwork.ANY, setting.transferNetwork())
    }
}
