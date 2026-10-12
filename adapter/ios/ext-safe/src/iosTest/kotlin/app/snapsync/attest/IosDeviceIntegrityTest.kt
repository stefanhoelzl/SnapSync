package app.snapsync.attest

import app.snapsync.ports.ProveResult
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSData
import platform.Foundation.NSError
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * App Attest where it is **not** available (capability `privacy-security`).
 *
 * The successful ceremony is out of reach here and always will be: `DCAppAttestService.isSupported`
 * is false on a simulator, and the real attestation is anchored to a Secure Enclave key a simulator
 * does not have. That half of the capability is evidenced on device.
 *
 * That an unavailable service answers `NotAvailable` — not a hang, not an invented proof — is `DeviceIntegrityContract`'s,
 * run live on this host by `AttestContractTest`. What stays here is this adapter's own diagnostic: WHICH step refused,
 * with the platform's domain and code, which no other implementation of the port need spell alike. A silent hang
 * would park the caller until a watchdog killed it; a refusal without the platform's code would be undiagnosable.
 */
class IosDeviceIntegrityTest {

    private val integrity = IosDeviceIntegrity()

    @Test
    fun `a fresh proof on an unavailable service names the key generation and the platform's own error`() {
        val refused = assertIs<ProveResult.Refused.NotAvailable>(runBlocking { integrity.prove("challenge") })

        assertTrue(
            refused.reason.startsWith("App Attest generateKey:"),
            "the step must be named — three calls share this error shape: ${refused.reason}",
        )
        assertTrue(
            "domain=" in refused.reason && "code=" in refused.reason,
            "the platform's domain and code are the only diagnosis available: ${refused.reason}",
        )
    }

    // The `attestKey` step's own label is reachable only after a key was generated, which no simulator does; the
    // device recording (`DeviceIntegrity@IOS_DEVICE_APP.rec`) holds that call.

    @Test
    fun `a proof by an unusable handle names the assertion step`() {
        val refused = assertIs<ProveResult.Refused>(runBlocking { integrity.prove("challenge", "no-such-key") })

        assertTrue(
            refused.reason.startsWith("App Attest generateAssertion:"),
            "the assertion is the cheap renewal path and must be distinguishable in a log: ${refused.reason}",
        )
    }

    /**
     * How App Attest's refusals are told apart (`DCError.h`): answered here by a fake service at the adapter's own seam,
     * for the codes no host produces on request. An unsupported service is also observed live (this host, above), and
     * `invalidKey` in the device recording.
     */
    @Test
    fun `every other App Attest refusal is Failed and the two it names are told apart`() = runBlocking {
        fun answering(code: Long?, supported: Boolean = true) = IosDeviceIntegrity(RefusingAppAttest(code, supported))
        assertIs<ProveResult.Refused.NotAvailable>(answering(1).prove("challenge"))
        assertIs<ProveResult.Refused.NotAvailable>(
            answering(0, supported = false).prove("challenge"),
            "any code, unsupported",
        )
        assertIs<ProveResult.Refused.UnknownKey>(answering(3).prove("challenge", "key"))
        listOf(0L, 2L, 4L).forEach { code ->
            val refused = assertIs<ProveResult.Refused.Failed>(answering(code).prove("challenge", "key"), "code $code")
            assertTrue("code=$code" in refused.reason, refused.reason)
        }
        assertIs<ProveResult.Refused.Failed>(answering(null).prove("challenge"), "neither a value nor an error")
        Unit
    }
}

/** App Attest refusing every call with `DCError` `code` — or, for `null`, with neither a value nor an error. */
private class RefusingAppAttest(code: Long?, private val supported: Boolean) : AppAttestApi {
    private val error = code?.let { NSError.errorWithDomain("com.apple.devicecheck.error", it, null) }

    override fun isSupported(): Boolean = supported

    override fun generateKey(completion: (String?, NSError?) -> Unit) = completion(null, error)

    override fun attestKey(keyId: String, clientDataHash: NSData, completion: (NSData?, NSError?) -> Unit) =
        completion(null, error)

    override fun generateAssertion(keyId: String, clientDataHash: NSData, completion: (NSData?, NSError?) -> Unit) =
        completion(null, error)
}
