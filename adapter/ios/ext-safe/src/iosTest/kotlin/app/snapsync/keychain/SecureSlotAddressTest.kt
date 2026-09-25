package app.snapsync.keychain

import app.snapsync.model.SecureSlots
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where the [SecureSlots] live in the iOS Keychain (capabilities `photo-sharing` and `privacy-security`): the
 * service, account and access group [IosSecureStore] stamps on every operation for each slot.
 *
 * The device id is written **once**, at mint, and the Keychain item survives app uninstall, so a moved address is
 * unrecoverable: a different service, account or group is a different real item, and reading it succeeds — it
 * simply returns something else, or nothing. On 2026-07-20 an SE2 ran nine hours with the app on one id and the
 * upload extension on another, **both reads reporting success**.
 *
 * `RuntimeIdentityTest` (a JVM text gate) pins that the literals appear exactly once. What it cannot see is the
 * *mapping* from a slot to a query — whether a shared slot names the shared group, and whether the legacy slot is
 * the same item with the group dropped. A Kotlin/Native test binary is refused Keychain access outright (see
 * [IosSecureStoreTest]), so these assertions read back the address the adapter would have issued. The order and
 * roles of the resolution are pinned platform-free in `PersistedDeviceIdentityTest` and `AttestStateTest`.
 */
class SecureSlotAddressTest {

    private val store = IosSecureStore()

    /**
     * The item the installed base holds, addressed exactly as production addresses it.
     */
    @Test
    fun `the device-id slot names the shared group and the pinned service and account`() {
        val address = store.item(SecureSlots.DEVICE_ID).itemAddress()

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
     * The legacy slot is the *same item* with the group dropped — not a different service or account. If it
     * addressed something else it would find nothing, and every device an older build provisioned would be
     * re-minted a second identity instead of having its first one adopted.
     */
    @Test
    fun `the legacy device-id slot is the same item searched without a group`() {
        val address = store.item(SecureSlots.DEVICE_ID_LEGACY).itemAddress()

        assertEquals("app.snapsync.deviceid", address["svce"])
        assertEquals("deviceid", address["acct"])
        assertTrue("agrp" in address, "the access group must be reported even when there is none")
        assertEquals(null, address["agrp"], "the legacy read must span every group this process can reach")
    }

    /**
     * The attestation items as the installed base holds them: unscoped (a pinned inventory), under one service,
     * one account each. A moved token address reads as "never attested" and burns a throttled re-attestation.
     */
    @Test
    fun `the attestation slots address the pinned unscoped items`() {
        val token = store.item(SecureSlots.ATTEST_TOKEN).itemAddress()
        val keyId = store.item(SecureSlots.ATTEST_KEY_ID).itemAddress()

        assertEquals(mapOf("svce" to "app.snapsync.attest", "acct" to "token", "agrp" to null), token)
        assertEquals(mapOf("svce" to "app.snapsync.attest", "acct" to "keyid", "agrp" to null), keyId)
    }
}
