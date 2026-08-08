package app.snapsync.attest

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * App Attest where it is **not** available (capability `device-attestation`).
 *
 * The successful ceremony is out of reach here and always will be: `DCAppAttestService.isSupported`
 * is false on a simulator, and the real attestation is anchored to a Secure Enclave key a simulator
 * does not have. That half of the capability is evidenced on device.
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
class IosAttestKeyTest {

    private val key = IosAttestKey()

    @Test
    fun `app attest reports itself unsupported rather than pretending`() {
        assertFalse(
            key.isSupported(),
            "if a simulator ever DOES support App Attest this must be re-read, not silently believed: " +
                "the rest of this file asserts the refusal path",
        )
    }

    /**
     * The refusal must arrive as a diagnosable exception. `DeviceAttestation` catches it and reduces
     * it to "no fresh token"; what it cannot reduce is a coroutine that never resumes.
     */
    @Test
    fun `generating a key on an unsupported service raises with the platform's own error`() {
        val failure = assertFailsWith<IllegalStateException> { runBlocking { key.generateKey() } }

        assertTrue(
            failure.message.orEmpty().startsWith("App Attest generateKey failed:"),
            "the step must be named — three calls share this error shape: ${failure.message}",
        )
        assertTrue(
            "domain=" in failure.message.orEmpty() && "code=" in failure.message.orEmpty(),
            "the platform's domain and code are the only diagnosis available: ${failure.message}",
        )
    }

    @Test
    fun `attesting an unusable key names the attest step rather than the key generation step`() {
        val failure = assertFailsWith<IllegalStateException> {
            runBlocking { key.attest("no-such-key", "challenge") }
        }

        assertTrue(
            failure.message.orEmpty().startsWith("App Attest attestKey failed:"),
            "a mislabelled step sends the reader to the wrong half of the ceremony: ${failure.message}",
        )
    }

    @Test
    fun `asserting with an unusable key names the assertion step`() {
        val failure = assertFailsWith<IllegalStateException> {
            runBlocking { key.assert("no-such-key", "challenge") }
        }

        assertTrue(
            failure.message.orEmpty().startsWith("App Attest generateAssertion failed:"),
            "the assertion is the cheap renewal path and must be distinguishable in a log: ${failure.message}",
        )
    }
}
