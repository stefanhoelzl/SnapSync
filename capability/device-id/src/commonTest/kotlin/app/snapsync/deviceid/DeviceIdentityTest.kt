package app.snapsync.deviceid

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceIdentityTest {

    /** A tiny in-memory store standing in for the Keychain. */
    private class Store(var value: String? = null) {
        var writes = 0
        fun read(): String? = value
        fun write(v: String) {
            value = v
            writes++
        }
    }

    @Test
    fun mints_once_and_persists_on_first_resolution() {
        val store = Store()
        var generated = 0
        val id = resolveDeviceId(store::read, store::write, { generated++; "minted-id" })
        assertEquals("minted-id", id)
        assertEquals("minted-id", store.value) // persisted
        assertEquals(1, generated)
        assertEquals(1, store.writes)
    }

    @Test
    fun later_resolutions_read_back_and_never_generate_again() {
        val store = Store()
        var generated = 0
        val first = resolveDeviceId(store::read, store::write, { generated++; "id-$generated" })
        val second = resolveDeviceId(store::read, store::write, { generated++; "id-$generated" })
        assertEquals(first, second) // stable
        assertEquals(1, generated) // generated only once
        assertEquals(1, store.writes) // written only once
    }

    @Test
    fun a_pre_seeded_store_is_returned_verbatim() {
        val store = Store(value = "existing-uuid") // e.g. survived a reinstall in the Keychain
        var generated = 0
        val id = resolveDeviceId(store::read, store::write, { generated++; "fresh" })
        assertEquals("existing-uuid", id)
        assertEquals(0, generated) // never minted
        assertEquals(0, store.writes) // never written
    }

    @Test
    fun fixed_identity_returns_its_id() {
        assertEquals("fixed", FixedDeviceIdentity("fixed").deviceId())
    }
}
