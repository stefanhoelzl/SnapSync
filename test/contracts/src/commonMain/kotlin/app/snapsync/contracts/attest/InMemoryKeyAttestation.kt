package app.snapsync.contracts.attest

import app.snapsync.contracts.Attester
import app.snapsync.wire.models.AppleAppAttestProof
import app.snapsync.wire.models.Proof
import kotlin.io.encoding.Base64

/**
 * A key in the in-memory device integrity's own proofs — `attestation:<key>:<challenge>`, `assertion:<key>:<challenge>`
 * — the ones the backend mock verifies, as the integrity mock beside it produces them. What a test host attests with
 * when its backend is the mock.
 */
class InMemoryKeyAttestation(private val key: String = "test-host-key") : Attester {
    override fun proof(challenge: String): Proof =
        AppleAppAttestProof(key, Base64.encode("attestation:$key:$challenge".encodeToByteArray()))

    override fun sign(challenge: String): ByteArray = "assertion:$key:$challenge".encodeToByteArray()
}
