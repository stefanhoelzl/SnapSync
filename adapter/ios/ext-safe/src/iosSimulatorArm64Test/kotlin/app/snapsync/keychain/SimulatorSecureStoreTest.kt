package app.snapsync.keychain

import app.snapsync.model.SecureSlot
import app.snapsync.model.SecureSlots
import app.snapsync.model.SecureStoreRead
import app.snapsync.model.StoredProtection
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.SecureStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The simulator target's slot routing (capability `photo-sharing`): every shared slot (the device id, the event
 * key) in the App-Group file store, and every other slot in the Keychain.
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
    fun `every other slot goes to the keychain`() {
        store.write(SecureSlots.ATTEST_TOKEN, "bearer-abc")

        assertEquals(
            SecureStoreRead.Found("bearer-abc", StoredProtection.BACKGROUND_READABLE),
            store.read(SecureSlots.ATTEST_TOKEN),
        )
        store.read(SecureSlots.ATTEST_KEY_ID)
        assertEquals(listOf(SecureSlots.ATTEST_TOKEN, SecureSlots.ATTEST_KEY_ID), keychain.touched.distinct())
        assertTrue(files.touched.isEmpty())
    }
}
