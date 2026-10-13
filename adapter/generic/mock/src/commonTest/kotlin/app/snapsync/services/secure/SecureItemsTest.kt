package app.snapsync.services.secure

import app.snapsync.model.SecureStoreUnavailable
import app.snapsync.ports.SecureSlot
import app.snapsync.ports.SecureStoreRead
import app.snapsync.ports.StoredProtection
import app.snapsync.ports.WriteOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val LOCKED = "OSStatus -25308" // errSecInteractionNotAllowed, as the iOS adapter formats it

private val ITEM = SecureSlot(service = "app.snapsync.test", account = "item", shared = true)

private fun found(value: String, protection: StoredProtection = StoredProtection.BACKGROUND_READABLE) =
    SecureStoreRead.Found(value, protection)

/**
 * The services' remaining secure-item reads (`readExisting`, `persist` — the event key's) over a recording store: "I
 * could not look" is never "there is nothing there", a read never writes, and a refused write fails where it is made.
 */
class SecureItemsTest {

    @Test
    fun `readExisting maps absent to null without writing`() {
        val store = RecordingSecureStore()
        assertNull(readExisting(store, ITEM))
        assertTrue(store.untouched())
    }

    @Test
    fun `readExisting distinguishes unreadable from absent`() {
        val store = RecordingSecureStore(ITEM to SecureStoreRead.Unavailable(LOCKED))
        val failure = assertFailsWith<SecureStoreUnavailable> { readExisting(store, ITEM) }
        assertEquals(LOCKED, failure.detail)
    }

    @Test
    fun `readExisting returns an item under any protection verbatim and never rewrites it`() {
        val store = RecordingSecureStore(ITEM to found("payload", StoredProtection.RESTRICTED))
        assertEquals("payload", readExisting(store, ITEM))
        assertTrue(store.untouched(), "a read never writes")
    }

    @Test
    fun `persist writes an accepted value`() {
        val store = RecordingSecureStore()
        persist(store, ITEM, "value")
        assertEquals(found("value"), store.read(ITEM))
    }

    @Test
    fun `persist maps a failed write to SecureStoreUnavailable`() {
        val store = RecordingSecureStore().apply {
            refuseWrites = true
            writeRefusal = WriteOutcome.Failed(LOCKED)
        }
        val failure = assertFailsWith<SecureStoreUnavailable> { persist(store, ITEM, "value") }
        assertTrue(LOCKED in failure.detail, failure.detail)
    }
}
