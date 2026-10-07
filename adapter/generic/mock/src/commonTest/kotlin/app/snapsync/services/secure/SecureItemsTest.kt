package app.snapsync.services.secure

import app.snapsync.model.SecureSlot
import app.snapsync.model.SecureStoreRead
import app.snapsync.model.SecureStoreResolution
import app.snapsync.model.SecureStoreUnavailable
import app.snapsync.model.StoredProtection
import app.snapsync.model.WriteOutcome
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
 * The mint-once-then-read core (`resolveOrMint`, `readExisting`, `persist`) over a recording
 * store: the normative order — found → mint → persist — and the rule every step serves, that
 * "I could not look" is never "there is nothing there" and a value is never handed out unsaved.
 */
class SecureItemsTest {

    @Test
    fun `a stored value is returned verbatim and nothing is minted`() {
        val store = RecordingSecureStore(ITEM to found("stored-id"))

        val resolved = resolveOrMint(store, ITEM) { "minted-id" }

        assertEquals("stored-id", resolved)
        assertTrue(store.untouched(), "a present value must never be rewritten")
    }

    @Test
    fun `an absent item mints exactly once and persists it`() {
        val store = RecordingSecureStore()

        val resolved = resolveOrMint(store, ITEM) { "minted-id" }

        assertEquals("minted-id", resolved)
        assertEquals(listOf("minted-id"), store.writesTo(ITEM))
    }

    // The build-297 crash, as a unit test. A locked device answers `Unavailable`, NOT `Absent`.
    // Before the fix this path minted a fresh UUID and tried to persist it — which threw (aborting the
    // process) and, had it succeeded, would have silently given the device a NEW identity, orphaning
    // its byte partition and ledger.
    @Test
    fun `an unavailable store never mints and never writes`() {
        val store = RecordingSecureStore(ITEM to SecureStoreRead.Unavailable(LOCKED))
        var generated = false

        val failure = assertFailsWith<SecureStoreUnavailable> {
            resolveOrMint(store, ITEM) {
                generated = true
                "minted-id"
            }
        }

        assertEquals(LOCKED, failure.detail, "the adapter's diagnostic must survive to the device log")
        assertTrue(!generated, "an unreadable store must NEVER mint a new identity")
        assertTrue(store.untouched(), "an unreadable store must never be written to")
    }

    @Test
    fun `an item under another protection is returned verbatim and reported but never rewritten`() {
        val store = RecordingSecureStore(ITEM to found("stored-id", StoredProtection.RESTRICTED))
        var outcome: SecureStoreResolution? = null

        val resolved = resolveOrMint(store, ITEM, onResolution = { outcome = it }) { "minted-id" }

        assertEquals("stored-id", resolved)
        assertEquals(
            SecureStoreResolution.Found(StoredProtection.RESTRICTED),
            outcome,
            "the protection is reported as read",
        )
        assertTrue(store.untouched(), "a read never writes")
    }

    @Test
    fun `an item whose protection is unreported is reported as such`() {
        val store = RecordingSecureStore(ITEM to found("stored-id", StoredProtection.UNREPORTED))
        var outcome: SecureStoreResolution? = null

        assertEquals("stored-id", resolveOrMint(store, ITEM, onResolution = { outcome = it }) { "minted-id" })
        assertEquals(
            SecureStoreResolution.Found(StoredProtection.UNREPORTED),
            outcome,
            "'the store did not say' is not 'the store said it is fine'",
        )
        assertTrue(store.untouched())
    }

    @Test
    fun `readExisting maps absent to null without minting`() {
        val store = RecordingSecureStore()

        assertNull(readExisting(store, ITEM))
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `readExisting distinguishes unreadable from absent`() {
        val store = RecordingSecureStore(ITEM to SecureStoreRead.Unavailable(LOCKED))

        val failure = assertFailsWith<SecureStoreUnavailable> { readExisting(store, ITEM) }

        assertEquals(LOCKED, failure.detail)
    }

    @Test
    fun `readExisting returns a correctly protected item verbatim and never rewrites it`() {
        val store = RecordingSecureStore(ITEM to found("payload"))
        var outcome: SecureStoreResolution? = null

        assertEquals("payload", readExisting(store, ITEM, onResolution = { outcome = it }))
        assertTrue(store.untouched(), "a read never writes")
        assertEquals(SecureStoreResolution.Found(StoredProtection.BACKGROUND_READABLE), outcome)
    }

    @Test
    fun `a mint reports itself`() {
        val store = RecordingSecureStore()
        var outcome: SecureStoreResolution? = null

        val resolved = resolveOrMint(store, ITEM, onResolution = { outcome = it }) { "minted-id" }

        assertEquals("minted-id", resolved)
        assertEquals(1, store.readsOf(ITEM), "one read, then the mint")
        assertEquals(listOf("minted-id"), store.writesTo(ITEM))
        assertEquals(SecureStoreResolution.Minted, outcome)
    }

    // ── A refused persisting write: the value is never handed out unsaved ────────────────────────
    // An id used and not stored would be a different id on the next launch.

    @Test
    fun `a refused write of a minted value throws and hands nothing out`() {
        val store = RecordingSecureStore().apply { refuseWrites = true }
        var outcome: SecureStoreResolution? = null

        val failure = assertFailsWith<SecureStoreUnavailable> {
            resolveOrMint(store, ITEM, onResolution = { outcome = it }) { "minted-id" }
        }

        assertTrue(LOCKED in failure.detail, "the refusal's diagnostic must survive: ${failure.detail}")
        assertNull(outcome, "a resolution that did not persist reports no outcome")
        assertEquals(SecureStoreRead.Absent, store.read(ITEM), "nothing was stored")
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

    @Test
    fun `persist maps an unsupported write to SecureStoreUnavailable`() {
        val store = RecordingSecureStore().apply {
            refuseWrites = true
            writeRefusal = WriteOutcome.Unsupported
        }

        assertFailsWith<SecureStoreUnavailable> { persist(store, ITEM, "value") }
    }

    // ── The extension's shape: read the addressed item and nothing else ──────────────────────────
    // It never mints, because it cannot tell "this device has no identity" from "the app's identity is
    // not reachable from here".

    @Test
    fun `the read-only shape never mints on absence`() {
        val store = RecordingSecureStore()

        assertNull(readExisting(store, ITEM))
        assertTrue(store.untouched(), "the extension must never write an identity")
    }

    @Test
    fun `the read-only shape reports how it resolved`() {
        val store = RecordingSecureStore(ITEM to found("stored-id", StoredProtection.RESTRICTED))
        var outcome: SecureStoreResolution? = null

        assertEquals("stored-id", readExisting(store, ITEM, onResolution = { outcome = it }))
        assertEquals(SecureStoreResolution.Found(StoredProtection.RESTRICTED), outcome)
        assertTrue(store.untouched())
    }
}
