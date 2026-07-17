package app.snapsync.keychain

import app.snapsync.ports.KeychainRead
import app.snapsync.ports.KeychainUnavailable
import app.snapsync.ports.readExisting
import app.snapsync.ports.resolveOrMint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The iOS adapter against the **real** `SecItem*` API.
 *
 * **What a test can and cannot reach here.** A Kotlin/Native test binary is not an app bundle: it has
 * no keychain-access-group entitlement, so `securityd` refuses it Keychain access entirely and every
 * call returns `errSecNotAvailable` (**-25291**). There is therefore no environment — simulator or
 * otherwise — in which a unit test can exercise the happy path: store an item, read its accessibility
 * class back, migrate a legacy item. Only a real app bundle on a device can do that, which is why this
 * capability's end-to-end evidence is the on-device diagnostic log (capability `ios-app-shell`) rather
 * than a test. This was discovered the hard way — the first version of this file assumed a working
 * Keychain and failed 9 of its 19 assertions.
 *
 * What remains is not nothing. It is, in fact, **the bug itself**: an inaccessible Keychain is exactly
 * what a locked device presents, and the build-297 crash *was* the adapter mistaking that condition for
 * "no value is stored" — it minted a fresh device id, tried to persist it, failed for the same reason
 * the read had, and aborted the process. So this file proves, against the real API, the invariant whose
 * absence caused that:
 *
 * > when the Keychain cannot be read, the adapter reports `Unavailable` — never `Absent` — and mints
 * > nothing, writes nothing, and changes no identity.
 *
 * Plus the one structural fact that needs no `securityd`: every item the adapter writes carries
 * `kSecAttrAccessibleAfterFirstUnlock`.
 */
class IosKeychainTest {

    private val keychain = IosKeychain(service = "app.snapsync.test.keychain", account = "testitem")

    /**
     * The half of capability `architecture-guards`'s argument that containment cannot supply: Konsist
     * proves all Keychain code lives in this module; this proves this module always writes items a
     * locked device can read. [IosKeychain.writtenAttributes] is the single source that both `write` and
     * `migrateAccessibility` build their dictionaries from, so it cannot drift from what is applied.
     */
    @Test
    fun `every written item carries AfterFirstUnlock`() {
        assertEquals(
            "ck",
            ACCESSIBLE_AFTER_FIRST_UNLOCK,
            "the raw value of kSecAttrAccessibleAfterFirstUnlock — 'ak' is WhenUnlocked, the iOS default that crashed",
        )
        assertEquals(
            mapOf("pdmn" to ACCESSIBLE_AFTER_FIRST_UNLOCK), // "pdmn" is kSecAttrAccessible's raw key
            keychain.writtenAttributes(),
            "every Keychain item must be readable by background work on a locked device",
        )
    }

    /**
     * The crash condition, against the real `SecItemCopyMatching`: `securityd` refuses this test binary,
     * which is the same refusal an app gets on a device not unlocked since boot.
     *
     * If a future toolchain ever *does* grant a test binary Keychain access, this fails loudly and
     * someone re-reads this file. That is the correct outcome — it must never fail *open*.
     */
    @Test
    fun `an inaccessible keychain reads as Unavailable and never as Absent`() {
        val read = keychain.read()

        assertIs<KeychainRead.Unavailable>(
            read,
            "a refusal is 'I could not look', never 'there is nothing there' — conflating the two minted " +
                "a new device id on a locked phone and aborted the process",
        )
        assertTrue(read.status != 0, "an unavailable read must carry the OSStatus, for the device log")
    }

    /** The never-mint invariant, end to end, through the real adapter. */
    @Test
    fun `resolving against an inaccessible keychain mints nothing and writes nothing`() {
        var generated = false

        val failure = assertFailsWith<KeychainUnavailable> {
            resolveOrMint(keychain, ACCESSIBLE_AFTER_FIRST_UNLOCK) {
                generated = true
                "a-brand-new-identity"
            }
        }

        assertTrue(
            !generated,
            "minting here is what orphans a device's byte partition and ledger, and re-uploads its library",
        )
        assertTrue(failure.status != 0)
        assertIs<KeychainRead.Unavailable>(keychain.read(), "the failed resolve must leave nothing behind")
    }

    /** `readExisting` (the config path) must draw the same line: unreadable is not absent. */
    @Test
    fun `readExisting on an inaccessible keychain raises rather than reporting no value`() {
        assertFailsWith<KeychainUnavailable> { readExisting(keychain, ACCESSIBLE_AFTER_FIRST_UNLOCK) }
    }
}
