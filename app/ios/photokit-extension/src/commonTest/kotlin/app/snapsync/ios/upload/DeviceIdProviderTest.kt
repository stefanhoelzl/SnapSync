package app.snapsync.ios.upload

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceIdProviderTest {

    private class FakeStore(var stored: String? = null) : DeviceIdStore {
        var saves = 0
        override fun load(): String? = stored
        override fun save(id: String) {
            stored = id
            saves++
        }
    }

    @Test
    fun mints_and_persists_when_absent() {
        val store = FakeStore(stored = null)
        val provider = DeviceIdProvider(store) { "minted-uuid" }
        assertEquals("minted-uuid", provider.deviceId())
        assertEquals("minted-uuid", store.stored)
        assertEquals(1, store.saves)
    }

    @Test
    fun reuses_stored_value_without_minting() {
        val store = FakeStore(stored = "existing-uuid")
        var minted = false
        val provider = DeviceIdProvider(store) { minted = true; "new-uuid" }
        assertEquals("existing-uuid", provider.deviceId())
        assertEquals(0, store.saves)
        assertEquals(false, minted)
    }

    @Test
    fun second_call_after_mint_returns_the_same_id() {
        val store = FakeStore(stored = null)
        var n = 0
        val provider = DeviceIdProvider(store) { "uuid-${n++}" }
        val first = provider.deviceId()
        val second = provider.deviceId()
        assertEquals(first, second)
        assertEquals(1, store.saves)
    }
}
