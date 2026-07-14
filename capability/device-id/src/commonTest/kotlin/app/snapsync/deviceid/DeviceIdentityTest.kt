package app.snapsync.deviceid

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The mint-once-then-read behaviour this file used to test now lives in `:domain:keychain`
 * (`KeychainResolveTest`), because the decision is no longer two-state: a Keychain read answers
 * *found* / *absent* / *unreadable*, and **only absent may mint**. The old
 * `resolveDeviceId(read, write, generate)` core could not express "unreadable" — it mapped every read
 * failure to "no id stored" — which is what minted a new identity on a locked device and aborted the
 * process. It is gone, and its coverage moved (with the `Unavailable` and migration cases added).
 */
class DeviceIdentityTest {

    @Test
    fun fixed_identity_returns_its_id() {
        assertEquals("fixed", FixedDeviceIdentity("fixed").deviceId())
    }
}
