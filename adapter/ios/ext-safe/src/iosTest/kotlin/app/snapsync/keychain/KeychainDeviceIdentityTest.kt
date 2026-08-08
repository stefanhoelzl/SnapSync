package app.snapsync.keychain

import app.snapsync.ports.DeviceIdentityAbsent
import app.snapsync.ports.KeychainRead
import app.snapsync.ports.KeychainUnavailable

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The device identity, on the two axes that have actually failed in the field (capability
 * `device-identity`).
 *
 * **Why this is worth a file of its own.** The device id is written **once**, at mint, and never
 * rewritten — the Keychain item survives app uninstall, so nothing in a device's remaining lifetime
 * will correct a value or a placement that was wrong when it was created. There is no migration to
 * write later and no support path: a wrong id orphans that device's `/files/devices/<deviceId>/`
 * partition and makes every photo it already uploaded read back as another member's, which
 * `DownloadController` then re-imports into its owner's own library, one duplicate per photo. That
 * is remotely unfixable, and it shipped: on 2026-07-20 an SE2 ran nine hours with the app on one id
 * and the upload extension on another, **both reads reporting success**.
 *
 * `RuntimeIdentityTest` (a JVM text gate) already pins that the literals appear exactly once and that
 * the device-id seat names an access group at all. What it cannot see is the *wiring*: which of the
 * two views is consulted first, whether the unscoped one is consulted at all in the process that must
 * not, and whether the group named is the shared one rather than some other string. Those are the
 * assertions below.
 *
 * They run against [StubKeychain] rather than `securityd`, and that is not a compromise: a
 * Kotlin/Native test binary is refused Keychain access outright (see [IosKeychainTest]), so no
 * environment exists in which the real item can be exercised. The address assertions close the gap
 * from the other side, by reading back the query the adapter would have issued.
 */
class KeychainDeviceIdentityTest {

    private val shared = StubKeychain()
    private val legacy = StubKeychain()

    private fun identity(role: DeviceIdentityRole, mint: () -> String = { "minted-id" }) =
        KeychainDeviceIdentity(role = role, shared = shared, legacy = legacy, mint = mint)

    // ---- the item's address -------------------------------------------------------------------

    /**
     * The item the installed base holds, addressed exactly as production addresses it. Every field
     * here is unrecoverable if it moves: a different service, account or group is a different real
     * item, and reading it succeeds — it simply returns something else, or nothing.
     */
    @Test
    fun `the device-id item names the shared group and the pinned service and account`() {
        val address = KeychainDeviceIdentity.deviceIdItem(SHARED_KEYCHAIN_ACCESS_GROUP).itemAddress()

        assertEquals("app.snapsync.deviceid", address["svce"], "the service names the item")
        assertEquals("deviceid", address["acct"], "the account names the item")
        assertEquals(
            "E9Z8BADH58.app.snapsync.shared",
            address["agrp"],
            "the app and the upload extension must address ONE group by name; when no group is named " +
                "the platform picks one at WRITE time from the writing build's entitlements, and the " +
                "two processes then hold different items while both reads succeed",
        )
    }

    /** The constant itself, since the address above is only as good as what production passes in. */
    @Test
    fun `the shared access group is the one both entitlements declare`() {
        assertEquals("E9Z8BADH58.app.snapsync.shared", SHARED_KEYCHAIN_ACCESS_GROUP)
    }

    /**
     * The legacy view is the *same item* with the group dropped — not a different service or account.
     * If it addressed something else it would find nothing, and every device an older build
     * provisioned would be re-minted a second identity instead of having its first one adopted.
     */
    @Test
    fun `the legacy view is the same item searched without a group`() {
        val address = KeychainDeviceIdentity.deviceIdItem(accessGroup = null).itemAddress()

        assertEquals("app.snapsync.deviceid", address["svce"])
        assertEquals("deviceid", address["acct"])
        assertEquals(null, address["agrp"], "the legacy read must span every group this process can reach")
    }

    // ---- the read-only role (the upload extension) ---------------------------------------------

    @Test
    fun `the extension reads the shared item and returns its value verbatim`() {
        val stored = StubKeychain(KeychainRead.Found("device-42", ACCESSIBLE_AFTER_FIRST_UNLOCK))
        val identity = KeychainDeviceIdentity(DeviceIdentityRole.READ_ONLY, stored, legacy) { "minted-id" }

        assertEquals("device-42", identity.deviceId())
        assertTrue(stored.untouched(), "a healthy read writes nothing")
        assertEquals(0, legacy.reads, "the extension must never consult the unscoped view")
    }

    /**
     * The branch that closed the split. An unscoped search from the *extension* finds that process's
     * OWN stale item — so adopting here would re-create the second identity rather than heal it.
     */
    @Test
    fun `the extension refuses to mint when the shared item is absent and consults no legacy view`() {
        val identity = identity(DeviceIdentityRole.READ_ONLY)

        assertFailsWith<DeviceIdentityAbsent> { identity.deviceId() }
        assertEquals(0, legacy.reads, "an unscoped fallback here is what produced two device ids")
        assertTrue(shared.writes.isEmpty(), "the extension may not create an identity under any circumstances")
    }

    @Test
    fun `an unreadable shared item defers the extension rather than inventing an identity`() {
        val locked = StubKeychain(KeychainRead.Unavailable(-25308))
        val identity = KeychainDeviceIdentity(DeviceIdentityRole.READ_ONLY, locked, legacy) { "minted-id" }

        val failure = assertFailsWith<KeychainUnavailable> { identity.deviceId() }
        assertEquals(-25308, failure.status, "the OSStatus must survive to the device log")
        assertTrue(locked.untouched())
    }

    // ---- the minting role (the app) -------------------------------------------------------------

    @Test
    fun `the app adopts an out-of-group id instead of minting a second one`() {
        val legacyHolder = StubKeychain(KeychainRead.Found("provisioned-in-july", null))
        var minted = false
        val identity = KeychainDeviceIdentity(DeviceIdentityRole.MINTING, shared, legacyHolder) {
            minted = true
            "minted-id"
        }

        assertEquals("provisioned-in-july", identity.deviceId())
        assertTrue(!minted, "adopting is the whole repair path; minting here strands the device's partition")
        assertEquals(
            listOf("provisioned-in-july"),
            shared.writes,
            "the adopted value must be re-filed under the shared group VERBATIM — a re-mint on adoption " +
                "would be the same fault wearing the repair's clothes",
        )
    }

    @Test
    fun `the app mints only when the id exists nowhere it can reach`() {
        val identity = identity(DeviceIdentityRole.MINTING)

        assertEquals("minted-id", identity.deviceId())
        assertEquals(1, legacy.reads, "the unscoped view must be consulted BEFORE minting")
        assertEquals(listOf("minted-id"), shared.writes, "a minted id is persisted to the shared group")
    }

    /**
     * "I could not look" on the legacy read is as disqualifying as on the primary one. This is the
     * arm that is easiest to get wrong, because minting here *works* — it just quietly hands a device
     * that already has an identity a second one.
     */
    @Test
    fun `an unreadable legacy view blocks the mint rather than being treated as absence`() {
        val unreadableLegacy = StubKeychain(KeychainRead.Unavailable(-25308))
        var minted = false
        val identity = KeychainDeviceIdentity(DeviceIdentityRole.MINTING, shared, unreadableLegacy) {
            minted = true
            "minted-id"
        }

        assertFailsWith<KeychainUnavailable> { identity.deviceId() }
        assertTrue(!minted, "a locked device must wait for the next launch, not acquire a new identity")
        assertTrue(shared.writes.isEmpty())
    }

    /**
     * A device provisioned by a pre-fix build carries the weaker accessibility class. It must be
     * upgraded **in place**, value untouched: re-minting would orphan the partition and the ledger.
     */
    @Test
    fun `a legacy-accessibility item is upgraded in place with its value untouched`() {
        val old = StubKeychain(KeychainRead.Found("provisioned-in-june", "ak")) // ak = WhenUnlocked
        val identity = KeychainDeviceIdentity(DeviceIdentityRole.MINTING, old, legacy) { "minted-id" }

        assertEquals("provisioned-in-june", identity.deviceId())
        assertEquals(1, old.migrations, "the item must be upgraded so background wakes can read it")
        assertTrue(old.writes.isEmpty(), "migration supplies no value; the id is never rewritten")
        assertEquals(0, legacy.reads, "a found item ends the resolution — no legacy read")
    }

    @Test
    fun `an item already stored background-readable is left completely alone`() {
        val healthy = StubKeychain(KeychainRead.Found("device-42", ACCESSIBLE_AFTER_FIRST_UNLOCK))
        val identity = KeychainDeviceIdentity(DeviceIdentityRole.MINTING, healthy, legacy) { "minted-id" }

        assertEquals("device-42", identity.deviceId())
        assertEquals(0, healthy.migrations, "an already-correct item costs no write")
    }

    /**
     * One resolve per instance. It matters beyond cost: the extension's process is short-lived and a
     * second resolve of an absent-then-present item would let one process report two different ids.
     */
    @Test
    fun `the identity is resolved once and then cached`() {
        var mints = 0
        val identity = KeychainDeviceIdentity(DeviceIdentityRole.MINTING, shared, legacy) {
            mints++
            "minted-$mints"
        }

        assertEquals("minted-1", identity.deviceId())
        assertEquals("minted-1", identity.deviceId())
        assertEquals(1, mints, "a second mint inside one process would be two identities")
        assertEquals(1, shared.reads, "the resolution is not re-run per call")
    }
}
