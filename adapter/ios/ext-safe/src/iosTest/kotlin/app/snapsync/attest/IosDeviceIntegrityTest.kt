package app.snapsync.attest

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * App Attest where it is **not** available (capability `privacy-security`).
 *
 * The successful ceremony is out of reach here and always will be: `DCAppAttestService.isSupported`
 * is false on a simulator, and the real attestation is anchored to a Secure Enclave key a simulator
 * does not have. That half of the capability is evidenced on device.
 *
 * That a refusal is an exception at all — not a hang, not an invented answer — is `DeviceIntegrityContract`'s, run
 * live on this host by `AttestContractTest`. What stays here is this adapter's own diagnostic: WHICH step
 * refused, with the platform's domain and code, which no other implementation of the port need spell alike.
 *
 * What is reachable is the half that decides how a device behaves when the ceremony **cannot** run —
 * which is not an exotic state. `isSupported` is also false inside the upload extension on real
 * hardware (measured on device: the app process reported `true` and completed the ceremony while the
 * extension, in the same build and a healthy `process()` cycle, reported `false`), so every renewal
 * happens in the app and the extension lives permanently on this path. It therefore matters that a
 * refusal is **reported** — with the platform's own domain and code — rather than hanging on a
 * completion handler that never fires or resuming with a null nobody can explain. A silent hang here
 * would park an OS-invoked cycle until its watchdog killed it.
 */
class IosDeviceIntegrityTest {

    private val integrity = IosDeviceIntegrity()

    /**
     * The refusal must arrive as a diagnosable exception. `DeviceAttestation` catches it and reduces
     * it to "no fresh token"; what it cannot reduce is a coroutine that never resumes.
     */
    @Test
    fun `a fresh proof on an unavailable service raises with the platform's own error for the key generation`() {
        val failure = assertFailsWith<IllegalStateException> { runBlocking { integrity.prove("challenge") } }

        assertTrue(
            failure.message.orEmpty().startsWith("App Attest generateKey failed:"),
            "the step must be named — three calls share this error shape: ${failure.message}",
        )
        assertTrue(
            "domain=" in failure.message.orEmpty() && "code=" in failure.message.orEmpty(),
            "the platform's domain and code are the only diagnosis available: ${failure.message}",
        )
    }

    // The `attestKey` step's own label is reachable only after a key was generated, which no simulator does; the
    // device recording (`DeviceIntegrity@IOS_DEVICE_APP.rec`) holds that call.

    @Test
    fun `a proof by an unusable handle names the assertion step`() {
        val failure = assertFailsWith<IllegalStateException> {
            runBlocking { integrity.prove("challenge", "no-such-key") }
        }

        assertTrue(
            failure.message.orEmpty().startsWith("App Attest generateAssertion failed:"),
            "the assertion is the cheap renewal path and must be distinguishable in a log: ${failure.message}",
        )
    }
}
