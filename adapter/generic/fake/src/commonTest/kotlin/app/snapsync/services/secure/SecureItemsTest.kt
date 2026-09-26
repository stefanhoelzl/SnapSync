package app.snapsync.services.secure

import app.snapsync.model.SecureSlot
import app.snapsync.model.SecureStoreRead
import app.snapsync.model.SecureStoreResolution
import app.snapsync.model.StoredProtection
import app.snapsync.model.WriteOutcome
import app.snapsync.model.SecureStoreUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val LOCKED = "OSStatus -25308" // errSecInteractionNotAllowed, as the iOS adapter formats it

private val ITEM = SecureSlot(service = "app.snapsync.test", account = "item", shared = true)
private val LEGACY = ITEM.copy(shared = false)

private fun found(value: String, protection: StoredProtection = StoredProtection.BACKGROUND_READABLE) =
    SecureStoreRead.Found(value, protection)

/**
 * The mint-once-then-read core (`resolveOrMint`, `readExisting`, `persist`, `needsMigration`) over a recording
 * store: the normative order — found → legacy → mint → persist — and the rule every step serves, that
 * "I could not look" is never "there is nothing there" and a value is never handed out unsaved.
 */
class SecureItemsTest {

    @Test
    fun `a stored value is returned verbatim and nothing is minted`() {
        val store = RecordingSecureStore(ITEM to found("stored-id"))

        val resolved = resolveOrMint(store, ITEM) { "minted-id" }

        assertEquals("stored-id", resolved)
        assertTrue(store.writes.isEmpty(), "a present value must never be rewritten")
        assertTrue(store.migrations.isEmpty(), "an already-correct item must not be rewritten")
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
            resolveOrMint(store, ITEM) { generated = true; "minted-id" }
        }

        assertEquals(LOCKED, failure.detail, "the adapter's diagnostic must survive to the device log")
        assertTrue(!generated, "an unreadable store must NEVER mint a new identity")
        assertTrue(store.untouched(), "an unreadable store must never be written to")
    }

    @Test
    fun `a legacy item is migrated in place and its value is preserved`() {
        val store = RecordingSecureStore(ITEM to found("stored-id", StoredProtection.RESTRICTED))

        val resolved = resolveOrMint(store, ITEM) { "minted-id" }

        assertEquals("stored-id", resolved, "migration must never change the value")
        assertEquals(listOf(ITEM), store.migrations)
        assertTrue(store.writes.isEmpty(), "migration upgrades the protection; it does not rewrite the value")
    }

    @Test
    fun `an item whose protection is unreported is migrated rather than trusted`() {
        val store = RecordingSecureStore(ITEM to found("stored-id", StoredProtection.UNREPORTED))

        assertEquals("stored-id", resolveOrMint(store, ITEM) { "minted-id" })
        assertEquals(1, store.migrations.size, "'the store did not say' is not 'the store said it is fine'")
        assertTrue(store.writes.isEmpty())
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
        assertTrue(store.migrations.isEmpty(), "an already-correct item must not be rewritten")
        assertTrue(store.writes.isEmpty())
        assertEquals(SecureStoreResolution.Found(StoredProtection.BACKGROUND_READABLE, migrated = false), outcome)
    }

    @Test
    fun `readExisting migrates a legacy item it can read`() {
        val store = RecordingSecureStore(ITEM to found("payload", StoredProtection.RESTRICTED))

        assertEquals("payload", readExisting(store, ITEM))
        assertEquals(listOf(ITEM), store.migrations)
    }

    @Test
    fun `needsMigration only when the protection is not the required one`() {
        assertTrue(needsMigration(StoredProtection.RESTRICTED))
        assertTrue(needsMigration(StoredProtection.UNREPORTED), "an unreported protection is not the required one")
        assertTrue(!needsMigration(StoredProtection.BACKGROUND_READABLE))
    }

    // ── The adoption branch ──────────────────────────────────────────────────────────────────────
    // An id an older build wrote into a different access group must be ADOPTED, never re-minted: a
    // second identity orphans the device's byte partition and makes its own uploads read as another
    // member's. This is the split observed on device on 2026-07-20.

    @Test
    fun `an id found only by the legacy read is adopted verbatim and never minted`() {
        val store = RecordingSecureStore(LEGACY to found("legacy-id"))
        var generated = false
        var outcome: SecureStoreResolution? = null

        val resolved = resolveOrMint(store, ITEM, onResolution = { outcome = it }, legacy = LEGACY) {
            generated = true
            "minted-id"
        }

        assertEquals("legacy-id", resolved, "the legacy value must be adopted byte for byte")
        assertTrue(!generated, "adoption must never mint")
        assertEquals(listOf("legacy-id"), store.writesTo(ITEM), "the adopted value is persisted to the addressed item")
        assertEquals(SecureStoreResolution.Adopted, outcome)
    }

    @Test
    fun `adoption never deletes the legacy item`() {
        val store = RecordingSecureStore(LEGACY to found("legacy-id"))

        resolveOrMint(store, ITEM, legacy = LEGACY) { "minted-id" }

        assertTrue(LEGACY !in store.deletes, "the out-of-group item survives, so a rollback still finds it")
        assertTrue(store.writesTo(LEGACY).isEmpty())
    }

    @Test
    fun `a present value never consults the legacy read`() {
        val store = RecordingSecureStore(ITEM to found("stored-id"))

        val resolved = resolveOrMint(store, ITEM, legacy = LEGACY) { "minted-id" }

        assertEquals("stored-id", resolved)
        assertEquals(0, store.readsOf(LEGACY), "the addressed item answers; nothing else is searched")
    }

    @Test
    fun `absent everywhere mints exactly once`() {
        val store = RecordingSecureStore()
        var outcome: SecureStoreResolution? = null

        val resolved = resolveOrMint(store, ITEM, onResolution = { outcome = it }, legacy = LEGACY) { "minted-id" }

        assertEquals("minted-id", resolved)
        assertEquals(1, store.readsOf(LEGACY), "the legacy slot is consulted BEFORE minting")
        assertEquals(listOf("minted-id"), store.writesTo(ITEM))
        assertEquals(SecureStoreResolution.Minted, outcome)
    }

    // ── Unavailability outranks absence AND adoption, on either read ─────────────────────────────

    @Test
    fun `an unavailable addressed read never reaches the legacy read`() {
        val store = RecordingSecureStore(ITEM to SecureStoreRead.Unavailable(LOCKED))
        var generated = false

        assertFailsWith<SecureStoreUnavailable> {
            resolveOrMint(store, ITEM, legacy = LEGACY) { generated = true; "minted-id" }
        }

        assertEquals(0, store.readsOf(LEGACY), "'I could not look' must short-circuit before any fallback")
        assertTrue(!generated)
        assertTrue(store.writes.isEmpty())
    }

    // The subtle one: the addressed item is genuinely absent, but the LEGACY read is unreadable — so
    // we cannot know whether this device already has an identity. Minting here would fork it.
    @Test
    fun `an unavailable legacy read defers instead of minting`() {
        val store = RecordingSecureStore(LEGACY to SecureStoreRead.Unavailable(LOCKED))
        var generated = false

        val failure = assertFailsWith<SecureStoreUnavailable> {
            resolveOrMint(store, ITEM, legacy = LEGACY) { generated = true; "minted-id" }
        }

        assertEquals(LOCKED, failure.detail)
        assertTrue(!generated, "an unreadable legacy read must never license a mint")
        assertTrue(store.writes.isEmpty())
    }

    // ── A refused persisting write: the value is never handed out unsaved ────────────────────────
    // An id used and not stored would be a different id on the next launch.

    @Test
    fun `a refused write of a minted value throws and hands nothing out`() {
        val store = RecordingSecureStore().apply { refuseWrites = true }
        var outcome: SecureStoreResolution? = null

        val failure = assertFailsWith<SecureStoreUnavailable> {
            resolveOrMint(store, ITEM, onResolution = { outcome = it }, legacy = LEGACY) { "minted-id" }
        }

        assertTrue(LOCKED in failure.detail, "the refusal's diagnostic must survive: ${failure.detail}")
        assertNull(outcome, "a resolution that did not persist reports no outcome")
        assertEquals(SecureStoreRead.Absent, store.read(ITEM), "nothing was stored")
    }

    @Test
    fun `a refused write of an adopted value throws and hands nothing out`() {
        val store = RecordingSecureStore(LEGACY to found("legacy-id")).apply { refuseWrites = true }
        var outcome: SecureStoreResolution? = null
        var generated = false

        assertFailsWith<SecureStoreUnavailable> {
            resolveOrMint(store, ITEM, onResolution = { outcome = it }, legacy = LEGACY) {
                generated = true
                "minted-id"
            }
        }

        assertNull(outcome)
        assertTrue(!generated, "a refused adoption must not fall through to a mint")
        assertEquals(found("legacy-id"), store.read(LEGACY), "the legacy item survives for the next attempt")
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
    // It supplies no legacy read and no mint, because it cannot tell "this device has no identity"
    // from "the app's identity is not reachable from here".

    @Test
    fun `the read-only shape neither adopts nor mints on absence`() {
        val store = RecordingSecureStore(LEGACY to found("legacy-id"))

        assertNull(readExisting(store, ITEM))
        assertTrue(store.untouched(), "the extension must never write an identity")
        assertEquals(0, store.readsOf(LEGACY))
    }

    @Test
    fun `the read-only shape reports how it resolved`() {
        val store = RecordingSecureStore(ITEM to found("stored-id", StoredProtection.RESTRICTED))
        var outcome: SecureStoreResolution? = null

        assertEquals("stored-id", readExisting(store, ITEM, onResolution = { outcome = it }))
        assertEquals(SecureStoreResolution.Found(StoredProtection.RESTRICTED, migrated = true), outcome)
    }
}
