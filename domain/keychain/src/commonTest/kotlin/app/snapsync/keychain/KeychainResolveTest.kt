package app.snapsync.keychain

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
}
