package app.snapsync.keychain

import app.snapsync.model.SecureSlot
import app.snapsync.model.SecureSlots
import app.snapsync.model.SecureStoreRead
import app.snapsync.model.StoredProtection
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.SecureStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The simulator target's slot routing (capability `photo-sharing`): the device id in the App-Group file store,
 * its legacy slot answered as absent — no older build ever wrote a device id on a simulator, and a store that
 * failed there would block minting forever — and every other slot in the Keychain.
 */
class SimulatorSecureStoreTest {

    /** A store that remembers which slots reached it; holds what is written, like the real ones. */
    private class Recording : SecureStore {
        val touched = mutableListOf<SecureSlot>()
        val items = mutableMapOf<SecureSlot, String>()

        override fun read(slot: SecureSlot): SecureStoreRead {
            touched += slot
            return items[slot]?.let { SecureStoreRead.Found(it, StoredProtection.BACKGROUND_READABLE) }
                ?: SecureStoreRead.Absent
        }

        override fun write(slot: SecureSlot, value: String): WriteOutcome {
            touched += slot
            items[slot] = value
            return WriteOutcome.Ok
        }

        override fun migrateProtection(slot: SecureSlot): WriteOutcome = WriteOutcome.Ok.also { touched += slot }

        override fun delete(slot: SecureSlot): WriteOutcome {
            touched += slot
            items.remove(slot)
            return WriteOutcome.Ok
        }
    }

    private val keychain = Recording()
    private val files = Recording()
    private val store = SimulatorSecureStore(keychain = keychain, files = files)

    @Test
    fun `the device id goes to the file store`() {
        assertEquals(WriteOutcome.Ok, store.write(SecureSlots.DEVICE_ID, "an-id"))

        assertEquals(SecureStoreRead.Found("an-id", StoredProtection.BACKGROUND_READABLE), store.read(SecureSlots.DEVICE_ID))
        assertEquals(WriteOutcome.Ok, store.delete(SecureSlots.DEVICE_ID))
        assertEquals(listOf(SecureSlots.DEVICE_ID), files.touched.distinct())
        assertTrue(keychain.touched.isEmpty(), "the device id never reaches the simulator's Keychain")
    }

    @Test
    fun `the legacy device-id slot is absent and cannot be written`() {
        assertEquals(SecureStoreRead.Absent, store.read(SecureSlots.DEVICE_ID_LEGACY))
        assertIs<WriteOutcome.Failed>(store.write(SecureSlots.DEVICE_ID_LEGACY, "an-id"))

        assertTrue(keychain.touched.isEmpty(), "no store is asked about the legacy slot")
        assertTrue(files.touched.isEmpty())
    }

    @Test
    fun `every other slot goes to the keychain`() {
        store.write(SecureSlots.ATTEST_TOKEN, "bearer-abc")

        assertEquals(
            SecureStoreRead.Found("bearer-abc", StoredProtection.BACKGROUND_READABLE),
            store.read(SecureSlots.ATTEST_TOKEN),
        )
        store.read(SecureSlots.ALBUM_MAP_LEGACY)
        assertEquals(listOf(SecureSlots.ATTEST_TOKEN, SecureSlots.ALBUM_MAP_LEGACY), keychain.touched.distinct())
        assertTrue(files.touched.isEmpty())
    }
}
