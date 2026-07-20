package app.snapsync.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val REQUIRED = "AfterFirstUnlock"
private const val LEGACY = "WhenUnlocked"

/** A recording fake: every effect the real Keychain would perform is observable. */
private class FakeKeychain(var answer: KeychainRead) : Keychain {
    val writes = mutableListOf<String>()
    var migrations = 0
    var deletes = 0

    override fun read(): KeychainRead = answer
    override fun write(value: String) {
        writes += value
        answer = KeychainRead.Found(value, REQUIRED)
    }
    override fun migrateAccessibility() {
        migrations++
        val found = answer as KeychainRead.Found
        answer = found.copy(accessibility = REQUIRED)
    }
    override fun delete() {
        deletes++
        answer = KeychainRead.Absent
    }
}

class KeychainResolveTest {

    @Test
    fun `a stored value is returned verbatim and nothing is minted`() {
        val keychain = FakeKeychain(KeychainRead.Found("stored-id", REQUIRED))

        val resolved = resolveOrMint(keychain, REQUIRED) { "minted-id" }

        assertEquals("stored-id", resolved)
        assertTrue(keychain.writes.isEmpty(), "a present value must never be rewritten")
        assertEquals(0, keychain.migrations, "an already-correct item must not be rewritten")
    }

    @Test
    fun `an absent item mints exactly once and persists it`() {
        val keychain = FakeKeychain(KeychainRead.Absent)

        val resolved = resolveOrMint(keychain, REQUIRED) { "minted-id" }

        assertEquals("minted-id", resolved)
        assertEquals(listOf("minted-id"), keychain.writes)
    }

    // The build-297 crash, as a unit test. A locked device answers `Unavailable`, NOT `Absent`.
    // Before the fix this path minted a fresh UUID and tried to persist it — which threw (aborting the
    // process) and, had it succeeded, would have silently given the device a NEW identity, orphaning
    // its byte partition and ledger.
    @Test
    fun `an unavailable keychain never mints and never writes`() {
        val keychain = FakeKeychain(KeychainRead.Unavailable(-25308)) // errSecInteractionNotAllowed
        var generated = false

        val failure = assertFailsWith<KeychainUnavailable> {
            resolveOrMint(keychain, REQUIRED) { generated = true; "minted-id" }
        }

        assertEquals(-25308, failure.status)
        assertTrue(!generated, "an unreadable keychain must NEVER mint a new identity")
        assertTrue(keychain.writes.isEmpty(), "an unreadable keychain must never be written to")
        assertEquals(0, keychain.migrations)
    }

    @Test
    fun `a legacy item is migrated in place and its value is preserved`() {
        val keychain = FakeKeychain(KeychainRead.Found("stored-id", LEGACY))

        val resolved = resolveOrMint(keychain, REQUIRED) { "minted-id" }

        assertEquals("stored-id", resolved, "migration must never change the value")
        assertEquals(1, keychain.migrations)
        assertTrue(keychain.writes.isEmpty(), "migration upgrades the class, it does not rewrite the value")
    }

    @Test
    fun `readExisting maps absent to null without minting`() {
        val keychain = FakeKeychain(KeychainRead.Absent)

        assertNull(readExisting(keychain, REQUIRED))
        assertTrue(keychain.writes.isEmpty())
    }

    @Test
    fun `readExisting distinguishes unreadable from absent`() {
        val keychain = FakeKeychain(KeychainRead.Unavailable(-25308))

        val failure = assertFailsWith<KeychainUnavailable> { readExisting(keychain, REQUIRED) }

        assertEquals(-25308, failure.status)
    }

    @Test
    fun `readExisting migrates a legacy item it can read`() {
        val keychain = FakeKeychain(KeychainRead.Found("payload", LEGACY))

        assertEquals("payload", readExisting(keychain, REQUIRED))
        assertEquals(1, keychain.migrations)
    }

    @Test
    fun `needsMigration only when the class differs`() {
        assertTrue(needsMigration(LEGACY, REQUIRED))
        assertTrue(needsMigration(null, REQUIRED), "an unreported class is not the required one")
        assertTrue(!needsMigration(REQUIRED, REQUIRED))
    }

    // ── The adoption branch ──────────────────────────────────────────────────────────────────────
    // An id an older build wrote into a different access group must be ADOPTED, never re-minted: a
    // second identity orphans the device's byte partition and makes its own uploads read as another
    // member's. This is the split observed on device on 2026-07-20.

    @Test
    fun `an id found only by the legacy read is adopted verbatim and never minted`() {
        val shared = FakeKeychain(KeychainRead.Absent)
        var generated = false
        var outcome: KeychainResolution? = null

        val resolved = resolveOrMint(
            shared,
            REQUIRED,
            onResolution = { outcome = it },
            readLegacy = { KeychainRead.Found("legacy-id", REQUIRED) },
        ) { generated = true; "minted-id" }

        assertEquals("legacy-id", resolved, "the legacy value must be adopted byte for byte")
        assertTrue(!generated, "adoption must never mint")
        assertEquals(listOf("legacy-id"), shared.writes, "the adopted value is persisted to the addressed item")
        assertEquals(KeychainResolution.Adopted, outcome)
    }

    @Test
    fun `adoption never deletes the legacy item`() {
        val legacy = FakeKeychain(KeychainRead.Found("legacy-id", REQUIRED))
        val shared = FakeKeychain(KeychainRead.Absent)

        resolveOrMint(shared, REQUIRED, readLegacy = { legacy.read() }) { "minted-id" }

        assertEquals(0, legacy.deletes, "the out-of-group item survives, so a rollback still finds it")
        assertTrue(legacy.writes.isEmpty())
    }

    @Test
    fun `a present value never consults the legacy read`() {
        val shared = FakeKeychain(KeychainRead.Found("stored-id", REQUIRED))
        var legacyConsulted = false

        val resolved = resolveOrMint(
            shared,
            REQUIRED,
            readLegacy = { legacyConsulted = true; KeychainRead.Absent },
        ) { "minted-id" }

        assertEquals("stored-id", resolved)
        assertTrue(!legacyConsulted, "the addressed item answers; nothing else is searched")
    }

    @Test
    fun `absent everywhere mints exactly once`() {
        val shared = FakeKeychain(KeychainRead.Absent)
        var outcome: KeychainResolution? = null

        val resolved = resolveOrMint(
            shared,
            REQUIRED,
            onResolution = { outcome = it },
            readLegacy = { KeychainRead.Absent },
        ) { "minted-id" }

        assertEquals("minted-id", resolved)
        assertEquals(listOf("minted-id"), shared.writes)
        assertEquals(KeychainResolution.Minted, outcome)
    }

    // ── Unavailability outranks absence AND adoption, on either read ─────────────────────────────

    @Test
    fun `an unavailable addressed read never reaches the legacy read`() {
        val shared = FakeKeychain(KeychainRead.Unavailable(-25308))
        var legacyConsulted = false
        var generated = false

        assertFailsWith<KeychainUnavailable> {
            resolveOrMint(
                shared,
                REQUIRED,
                readLegacy = { legacyConsulted = true; KeychainRead.Absent },
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
        val shared = FakeKeychain(KeychainRead.Absent)
        var generated = false

        val failure = assertFailsWith<KeychainUnavailable> {
            resolveOrMint(
                shared,
                REQUIRED,
                readLegacy = { KeychainRead.Unavailable(-25308) },
            ) { generated = true; "minted-id" }
        }

        assertEquals(-25308, failure.status)
        assertTrue(!generated, "an unreadable legacy read must never license a mint")
        assertTrue(shared.writes.isEmpty())
    }

    // ── The extension's shape: read the addressed item and nothing else ──────────────────────────
    // It supplies no legacy read and no mint, because it cannot tell "this device has no identity"
    // from "the app's identity is not reachable from here".

    @Test
    fun `the read-only shape neither adopts nor mints on absence`() {
        val shared = FakeKeychain(KeychainRead.Absent)

        assertNull(readExisting(shared, REQUIRED))
        assertTrue(shared.writes.isEmpty(), "the extension must never write an identity")
        assertEquals(0, shared.deletes)
    }

    @Test
    fun `the read-only shape reports how it resolved`() {
        val shared = FakeKeychain(KeychainRead.Found("stored-id", LEGACY))
        var outcome: KeychainResolution? = null

        assertEquals("stored-id", readExisting(shared, REQUIRED, onResolution = { outcome = it }))
        assertEquals(KeychainResolution.Found(LEGACY, migrated = true), outcome)
    }
}
