package app.snapsync.fake

import app.snapsync.model.Proof
import app.snapsync.ports.DeviceIntegrity

/**
 * The honest in-memory [DeviceIntegrity] — App Attest's device half, without a Secure Enclave.
 *
 * [available] is initial state rather than a constant because it is the one fact that differs between the
 * processes and hosts this double stands in for: `true` in the app, **`false` in the upload extension**
 * (measured on device), and **`false` on any simulator**, where App Attest does not exist at all. A
 * composition given `available = false` never attests and never renews — which is exactly what the
 * extension and the simulator do, and why `DeviceAttestation` has a branch for it.
 *
 * Its proofs have a recognisable shape — `attestation:<handle>:<challenge>` for a fresh key,
 * `assertion:<handle>:<challenge>` for a renewal — which is what the in-memory backends verify in place of Apple's
 * certificate chain, so they mint only for a proof this double really produced.
 *
 * No call counters: a fake's public surface is its port contract plus a constructor taking initial state
 * (gate: `FakeHonestyTest`). A test that needs to observe calls wraps this, or asserts the outcome.
 */
internal class InMemoryDeviceIntegrity(
    private val available: Boolean = true,
) : DeviceIntegrity {

    /** Distinct per call, like the real Secure Enclave — a fresh key each time one is created. */
    private var generated = 0
    private val keys = mutableSetOf<String>()

    override fun isAvailable(): Boolean = available

    // Refusals are exceptions, as the real service's are (`DeviceIntegrityContract`): an unavailable process has no
    // App Attest to prove WITH, and no key but one this service created can sign.
    override suspend fun prove(challenge: String, handle: String?): Proof {
        if (handle == null) {
            check(available) { "App Attest attestKey failed: unsupported in this process" }
            val key = "in-memory-key-${++generated}".also { keys += it }
            return Proof(key, "attestation:$key:$challenge".encodeToByteArray())
        }
        check(available) { "App Attest generateAssertion failed: unsupported in this process" }
        check(handle in keys) { "App Attest generateAssertion failed: no such key $handle" }
        return Proof(handle, "assertion:$handle:$challenge".encodeToByteArray())
    }
}
