package app.snapsync.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val LOCKED = "OSStatus -25308" // errSecInteractionNotAllowed, as the iOS adapter formats it

/** A recording fake: every effect a real [SecureStore] would perform is observable. */
private class FakeSecureStore(var answer: SecureStoreRead) : SecureStore {
    val writes = mutableListOf<String>()
    var migrations = 0
    var deletes = 0

    override fun read(): SecureStoreRead = answer
    override fun write(value: String) {
        writes += value
        answer = SecureStoreRead.Found(value, StoredProtection.BACKGROUND_READABLE)
    }
    override fun migrateProtection() {
        migrations++
        val found = answer as SecureStoreRead.Found
        answer = found.copy(protection = StoredProtection.BACKGROUND_READABLE)
    }
    override fun delete() {
        deletes++
        answer = SecureStoreRead.Absent
    }
}

private fun found(value: String, protection: StoredProtection = StoredProtection.BACKGROUND_READABLE) =
    SecureStoreRead.Found(value, protection)

class SecureStoreResolveTest {

    @Test
    fun `a stored value is returned verbatim and nothing is minted`() {
        val store = FakeSecureStore(found("stored-id"))

        val resolved = resolveOrMint(store) { "minted-id" }

        assertEquals("stored-id", resolved)
        assertTrue(store.writes.isEmpty(), "a present value must never be rewritten")
        assertEquals(0, store.migrations, "an already-correct item must not be rewritten")
    }

    @Test
    fun `an absent item mints exactly once and persists it`() {
        val store = FakeSecureStore(SecureStoreRead.Absent)

        val resolved = resolveOrMint(store) { "minted-id" }

        assertEquals("minted-id", resolved)
        assertEquals(listOf("minted-id"), store.writes)
    }

    // The build-297 crash, as a unit test. A locked device answers `Unavailable`, NOT `Absent`.
    // Before the fix this path minted a fresh UUID and tried to persist it — which threw (aborting the
    // process) and, had it succeeded, would have silently given the device a NEW identity, orphaning
    // its byte partition and ledger.
    @Test
    fun `an unavailable store never mints and never writes`() {
        val store = FakeSecureStore(SecureStoreRead.Unavailable(LOCKED))
        var generated = false

        val failure = assertFailsWith<SecureStoreUnavailable> {
            resolveOrMint(store) { generated = true; "minted-id" }
        }

        assertEquals(LOCKED, failure.detail, "the adapter's diagnostic must survive to the device log")
        assertTrue(!generated, "an unreadable store must NEVER mint a new identity")
        assertTrue(store.writes.isEmpty(), "an unreadable store must never be written to")
        assertEquals(0, store.migrations)
    }

    @Test
    fun `a legacy item is migrated in place and its value is preserved`() {
        val store = FakeSecureStore(found("stored-id", StoredProtection.RESTRICTED))

        val resolved = resolveOrMint(store) { "minted-id" }

        assertEquals("stored-id", resolved, "migration must never change the value")
        assertEquals(1, store.migrations)
        assertTrue(store.writes.isEmpty(), "migration upgrades the protection; it does not rewrite the value")
    }

    @Test
    fun `an item whose protection is unreported is migrated rather than trusted`() {
        val store = FakeSecureStore(found("stored-id", StoredProtection.UNREPORTED))

        assertEquals("stored-id", resolveOrMint(store) { "minted-id" })
        assertEquals(1, store.migrations, "'the store did not say' is not 'the store said it is fine'")
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `readExisting maps absent to null without minting`() {
        val store = FakeSecureStore(SecureStoreRead.Absent)

        assertNull(readExisting(store))
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `readExisting distinguishes unreadable from absent`() {
        val store = FakeSecureStore(SecureStoreRead.Unavailable(LOCKED))

        val failure = assertFailsWith<SecureStoreUnavailable> { readExisting(store) }

        assertEquals(LOCKED, failure.detail)
    }

    @Test
    fun `readExisting migrates a legacy item it can read`() {
        val store = FakeSecureStore(found("payload", StoredProtection.RESTRICTED))

        assertEquals("payload", readExisting(store))
        assertEquals(1, store.migrations)
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
        val shared = FakeSecureStore(SecureStoreRead.Absent)
        var generated = false
        var outcome: SecureStoreResolution? = null

        val resolved = resolveOrMint(
            shared,
            onResolution = { outcome = it },
            readLegacy = { found("legacy-id") },
        ) { generated = true; "minted-id" }

        assertEquals("legacy-id", resolved, "the legacy value must be adopted byte for byte")
        assertTrue(!generated, "adoption must never mint")
        assertEquals(listOf("legacy-id"), shared.writes, "the adopted value is persisted to the addressed item")
        assertEquals(SecureStoreResolution.Adopted, outcome)
    }

    @Test
    fun `adoption never deletes the legacy item`() {
        val legacy = FakeSecureStore(found("legacy-id"))
        val shared = FakeSecureStore(SecureStoreRead.Absent)

        resolveOrMint(shared, readLegacy = { legacy.read() }) { "minted-id" }

        assertEquals(0, legacy.deletes, "the out-of-group item survives, so a rollback still finds it")
        assertTrue(legacy.writes.isEmpty())
    }

    @Test
    fun `a present value never consults the legacy read`() {
        val shared = FakeSecureStore(found("stored-id"))
        var legacyConsulted = false

        val resolved = resolveOrMint(
            shared,
            readLegacy = { legacyConsulted = true; SecureStoreRead.Absent },
        ) { "minted-id" }

        assertEquals("stored-id", resolved)
        assertTrue(!legacyConsulted, "the addressed item answers; nothing else is searched")
    }

    @Test
    fun `absent everywhere mints exactly once`() {
        val shared = FakeSecureStore(SecureStoreRead.Absent)
        var outcome: SecureStoreResolution? = null

        val resolved = resolveOrMint(
            shared,
            onResolution = { outcome = it },
            readLegacy = { SecureStoreRead.Absent },
        ) { "minted-id" }

        assertEquals("minted-id", resolved)
        assertEquals(listOf("minted-id"), shared.writes)
        assertEquals(SecureStoreResolution.Minted, outcome)
    }

    // ── Unavailability outranks absence AND adoption, on either read ─────────────────────────────

    @Test
    fun `an unavailable addressed read never reaches the legacy read`() {
        val shared = FakeSecureStore(SecureStoreRead.Unavailable(LOCKED))
        var legacyConsulted = false
        var generated = false

        assertFailsWith<SecureStoreUnavailable> {
            resolveOrMint(
                shared,
                readLegacy = { legacyConsulted = true; SecureStoreRead.Absent },
            ) { generated = true; "minted-id" }
        }

        assertTrue(!legacyConsulted, "'I could not look' must short-circuit before any fallback")
        assertTrue(!generated)
        assertTrue(shared.writes.isEmpty())
    }

    // The subtle one: the addressed item is genuinely absent, but the LEGACY read is unreadable — so
    // we cannot know whether this device already has an identity. Minting here would fork it.
    @Test
    fun `an unavailable legacy read defers instead of minting`() {
        val shared = FakeSecureStore(SecureStoreRead.Absent)
        var generated = false

        val failure = assertFailsWith<SecureStoreUnavailable> {
            resolveOrMint(
                shared,
                readLegacy = { SecureStoreRead.Unavailable(LOCKED) },
            ) { generated = true; "minted-id" }
        }

        assertEquals(LOCKED, failure.detail)
        assertTrue(!generated, "an unreadable legacy read must never license a mint")
        assertTrue(shared.writes.isEmpty())
    }

    // ── The extension's shape: read the addressed item and nothing else ──────────────────────────
    // It supplies no legacy read and no mint, because it cannot tell "this device has no identity"
    // from "the app's identity is not reachable from here".

    @Test
    fun `the read-only shape neither adopts nor mints on absence`() {
        val shared = FakeSecureStore(SecureStoreRead.Absent)

        assertNull(readExisting(shared))
        assertTrue(shared.writes.isEmpty(), "the extension must never write an identity")
        assertEquals(0, shared.deletes)
    }

    @Test
    fun `the read-only shape reports how it resolved`() {
        val shared = FakeSecureStore(found("stored-id", StoredProtection.RESTRICTED))
        var outcome: SecureStoreResolution? = null

        assertEquals("stored-id", readExisting(shared, onResolution = { outcome = it }))
        assertEquals(
            SecureStoreResolution.Found(StoredProtection.RESTRICTED, migrated = true),
            outcome,
        )
    }
}
