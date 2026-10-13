package app.snapsync.contracts.attest

import app.snapsync.ports.DeviceIntegrity
import app.snapsync.ports.Proof
import app.snapsync.ports.ProofFormat
import app.snapsync.ports.ProveResult

/**
 * The [DeviceIntegrity] a test host's app attests with where the platform has no integrity service it can use against
 * the local `api/` — the JVM, the simulator: a fresh [SoftwareKeyAttestation] per attested key, proving as an Android
 * device's Keystore does (`ProofFormat.ANDROID_KEY`: the chain as concatenated DER when fresh, an ECDSA signature when
 * renewing). The keys live in memory: a handle from an earlier process names no key, as a reinstall's does.
 */
class SoftwareKeyIntegrity : DeviceIntegrity {
    private val keys = mutableMapOf<String, SoftwareKeyAttestation>()

    override suspend fun prove(challenge: String, handle: String?): ProveResult {
        if (handle != null) {
            val key = keys[handle] ?: return ProveResult.Refused.UnknownKey("no software key '$handle'")
            return ProveResult.Ok(Proof(handle, ProofFormat.ANDROID_KEY, key.sign(challenge)))
        }
        val created = "software-key-${keys.size + 1}"
        val key = SoftwareKeyAttestation().also { keys[created] = it }
        val chain = key.chain(challenge).fold(ByteArray(0)) { all, certificate -> all + certificate }
        return ProveResult.Ok(Proof(created, ProofFormat.ANDROID_KEY, chain))
    }
}
