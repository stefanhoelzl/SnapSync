package app.snapsync.contracts

import app.snapsync.ports.AttestClient
import app.snapsync.model.TokenOutcome
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The backend states an [AttestClient] clause needs. */
enum class AttestClientState {
    /** An edge serving this build. */
    SERVING,
}

/**
 * What the attestation client promises that a real host can check (`docs/architecture.md` — this list IS
 * the port's specification): the challenge, and every **refusal** — a forged attestation, a challenge the edge
 * never issued, a renewal for a device that never attested.
 *
 * A **successful** mint or renewal has no clause, and cannot: it needs a genuine App Attest attestation over a
 * challenge the edge issued within the last five minutes, verified against a certificate chain valid NOW. A
 * device recording is dead five minutes after it is taken, and teaching the dev edge to accept one would be the
 * rig faking attestation. So those beliefs stay in `HttpAttestClient`'s documentation, their behaviour in
 * `HttpAttestClientTest`, and the edge's verification in `api/test/attest.test.ts` against a real Apple fixture
 * ("Every clause runs against a real implementation on some host").
 *
 * The refusal inputs are shaped like what the in-memory double's own key would produce, so a double that
 * minted for any bytes — or over any challenge — fails here rather than passing by accident.
 */
object AttestClientContract : Contract<AttestClientState, EdgeSubject<AttestClient>>("AttestClient") {

    @Suppress("UNUSED_PARAMETER")
    suspend fun seed(state: AttestClientState, clauseId: String, setup: EdgeSetup): Seeded =
        Seeded(eventId = setup.freshId(), deviceId = setup.freshId())

    override val clauses = clauses {

        clause("A_CHALLENGE_IS_ISSUED", AttestClientState.SERVING) { s ->
            val challenge = assertNotNull(s.port.challenge(), "the ungated challenge route answers")
            assertTrue(challenge.isNotBlank())
        }

        clause("A_FORGED_ATTESTATION_IS_REFUSED", AttestClientState.SERVING) { s ->
            val challenge = assertNotNull(s.port.challenge())
            val forged = "not an attestation".encodeToByteArray()
            assertFalse(s.port.mintToken(s.seeded.deviceId, KEY_ID, forged, challenge) is TokenOutcome.Minted, "no token for a forgery")
        }

        clause("A_CHALLENGE_THE_EDGE_NEVER_ISSUED_IS_REFUSED", AttestClientState.SERVING) { s ->
            val notIssued = "a-challenge-no-edge-issued"
            val overIt = "attestation:$KEY_ID:$notIssued".encodeToByteArray()
            assertFalse(s.port.mintToken(s.seeded.deviceId, KEY_ID, overIt, notIssued) is TokenOutcome.Minted, "no replay against another nonce")
        }

        clause("AN_UNATTESTED_DEVICE_CANNOT_RENEW", AttestClientState.SERVING) { s ->
            val challenge = assertNotNull(s.port.challenge())
            val assertion = "assertion:$KEY_ID:$challenge".encodeToByteArray()
            assertFalse(s.port.renewToken(s.seeded.deviceId, assertion, challenge) is TokenOutcome.Minted, "renewal needs an enrolment")
        }
    }

    private const val KEY_ID = "contract-key"
}
