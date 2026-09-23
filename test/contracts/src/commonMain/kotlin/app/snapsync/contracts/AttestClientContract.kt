package app.snapsync.contracts

import app.snapsync.ports.AttestClient
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The backend states an [AttestClient] clause needs. */
enum class AttestClientState {
    /** An edge serving this build. */
    SERVING,
}

/**
 * What the attestation client promises that a real host can check (capability `port-contracts` — this list IS
 * the port's specification). ONLY the challenge: `mintToken` and `renewToken` need an App Attest attestation or
 * assertion the edge verifies, and no host CI runs can produce one, so their beliefs stay in
 * `HttpAttestClient`'s documentation and their behaviour in `HttpAttestClientTest` ("Every clause runs against
 * a real implementation on some host").
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
    }
}
