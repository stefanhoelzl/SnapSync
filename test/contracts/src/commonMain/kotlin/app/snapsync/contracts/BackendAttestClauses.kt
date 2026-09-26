package app.snapsync.contracts

import app.snapsync.model.MintRequest
import app.snapsync.model.Reply
import app.snapsync.model.RenewRequest
import app.snapsync.ports.Backend
import kotlin.test.assertIs
import kotlin.test.assertIsNot
import kotlin.test.assertTrue

/**
 * The three ungated `/attest/…` routes that issue the credential — what a real host can check: the challenge, and
 * every **refusal**. Part of [BackendContract]'s clause list.
 *
 * A **successful** mint or renewal has no clause, and cannot: it needs a genuine App Attest attestation over a
 * challenge the backend issued within the last five minutes, verified against a certificate chain valid NOW. A device
 * recording is dead five minutes after it is taken, and teaching the dev backend to accept one would be the rig
 * faking attestation. So the edge's verification is proven in `api/test/attest.test.ts` against a real Apple fixture
 * ("Every clause runs against a real implementation on some host").
 *
 * The refusal inputs are shaped like what the in-memory integrity's own key would produce, so a double that minted
 * for any bytes — or over any challenge — fails here rather than passing by accident.
 */
internal fun ClauseList<BackendState, EdgeSubject<Backend>>.attestClauses() {

    clause("ATTEST_A_CHALLENGE_IS_ISSUED", BackendState.SERVING) { s ->
        assertTrue(assertOk(s.port.challenge(), "the ungated challenge route answers").isNotBlank())
    }

    clause("ATTEST_A_FORGED_ATTESTATION_IS_REFUSED", BackendState.SERVING) { s ->
        val challenge = assertOk(s.port.challenge())
        val forged = "not an attestation".encodeToByteArray()
        assertIs<Reply.Refused>(s.port.mintToken(MintRequest(s.seeded.deviceId, KEY_ID, forged, challenge)), "no token for a forgery")
    }

    clause("ATTEST_A_CHALLENGE_THE_BACKEND_NEVER_ISSUED_IS_REFUSED", BackendState.SERVING) { s ->
        val notIssued = "a-challenge-no-edge-issued"
        val overIt = "attestation:$KEY_ID:$notIssued".encodeToByteArray()
        assertIs<Reply.Refused>(
            s.port.mintToken(MintRequest(s.seeded.deviceId, KEY_ID, overIt, notIssued)),
            "no replay against another nonce",
        )
    }

    clause("ATTEST_AN_UNATTESTED_DEVICE_CANNOT_RENEW", BackendState.SERVING) { s ->
        val challenge = assertOk(s.port.challenge())
        val assertion = "assertion:$KEY_ID:$challenge".encodeToByteArray()
        assertIsNot<Reply.Ok<*>>(s.port.renewToken(RenewRequest(s.seeded.deviceId, assertion, challenge)), "renewal needs an enrolment")
    }
}

private const val KEY_ID = "contract-key"
