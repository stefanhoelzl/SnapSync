package app.snapsync.contracts

import app.snapsync.model.MintRequest
import app.snapsync.model.ProofFormat
import app.snapsync.model.RenewRequest
import app.snapsync.model.Reply
import app.snapsync.ports.Backend
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The three ungated `/attest/…` routes that issue the credential: the challenge, every **refusal**, and a mint and
 * renewal through the Android verifier. Part of [BackendContract]'s clause list.
 *
 * A successful **App Attest** mint has no clause, and cannot: it needs a genuine attestation over a challenge the
 * backend issued within the last five minutes, verified against Apple's chain valid NOW. A device recording is dead
 * five minutes after it is taken, and teaching the dev backend to accept one would be the rig faking attestation; the
 * edge's App Attest verification is proven in `api/test/attest.test.ts` against a real Apple fixture. The successful
 * mint and renewal that do run are [BackendState.ATTESTABLE]'s: a software Android key attestation, which the local
 * rig's `any` policy accepts exactly as it accepts an emulator's — proving the routes and the verifier, never hardware
 * trust, which a deployed backend's `hardware` policy demands and refuses that chain for.
 *
 * The refusal inputs are shaped like what the in-memory integrity's own key would produce, so a double that minted
 * for any bytes — or over any challenge — fails here rather than passing by accident.
 */
internal fun ClauseList<BackendState, EdgeSubject<Backend>>.attestClauses() {
    clause(
        "ATTEST_A_CHALLENGE_IS_ISSUED",
        BackendState.SERVING,
        covers = cells { on<Backend>().answers(Backend::challenge).withGenericLeaf(Reply.Ok::class) },
    ) { s ->
        assertTrue(assertOk(s.port.challenge(), "the ungated challenge route answers").isNotBlank())
    }

    // The version gate stands in front of the issuers too: a build too old for the API is told so before it attests.
    clause(
        "ATTEST_A_REFUSED_BUILD_IS_ISSUED_NO_CHALLENGE",
        BackendState.VERSION_REFUSED,
        covers = cells { on<Backend>().answers(Backend::challenge).with(Reply.Refused::class) },
    ) { s ->
        assertRefused(UPGRADE_REQUIRED, s.port.challenge(), "the issuers are behind the version gate")
    }

    clause(
        "ATTESTABLE_A_SOFTWARE_KEY_ATTESTATION_MINTS_AND_ITS_KEY_RENEWS",
        BackendState.ATTESTABLE,
        covers = cells {
            on<Backend> {
                answers(Backend::challenge).withGenericLeaf(Reply.Ok::class)
                answers(Backend::mintToken).withGenericLeaf(Reply.Ok::class)
                answers(Backend::renewToken).withGenericLeaf(Reply.Ok::class)
            }
        },
    ) { s ->
        val key = assertNotNull(s.attester, "a binding that reaches this state attests with a key of its own")
        val device = s.seeded.deviceId
        val first = assertOk(s.port.challenge())
        val minted = assertOk(
            s.port.mintToken(MintRequest(device, KEY_ID, ProofFormat.ANDROID_KEY, key.attest(first), first)),
            "an attestation over a challenge the backend issued mints a token",
        )
        assertTrue(minted.isNotBlank())
        val second = assertOk(s.port.challenge())
        val renewed = assertOk(
            s.port.renewToken(RenewRequest(device, key.sign(second), second)),
            "the attested key's signature over a fresh challenge renews it",
        )
        assertTrue(renewed.isNotBlank())
    }

    clause(
        "ATTEST_A_FORGED_ATTESTATION_IS_REFUSED",
        BackendState.SERVING,
        covers = cells {
            on<Backend> {
                answers(Backend::challenge).withGenericLeaf(Reply.Ok::class)
                answers(Backend::mintToken).with(Reply.Refused::class)
            }
        },
    ) { s ->
        val challenge = assertOk(s.port.challenge())
        val forged = "not an attestation".encodeToByteArray()
        assertIs<Reply.Refused>(
            s.port.mintToken(MintRequest(s.seeded.deviceId, KEY_ID, ProofFormat.APP_ATTEST, forged, challenge)),
            "no token for a forgery",
        )
    }

    clause(
        "ATTEST_A_FORGED_ANDROID_KEY_ATTESTATION_IS_REFUSED",
        BackendState.SERVING,
        covers = cells {
            on<Backend> {
                answers(Backend::challenge).withGenericLeaf(Reply.Ok::class)
                answers(Backend::mintToken).with(Reply.Refused::class)
            }
        },
    ) { s ->
        val challenge = assertOk(s.port.challenge())
        // Shaped as a chain of one DER certificate, and not one: the Android verifier, not a body check, refuses it.
        val forged = byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x00)
        assertIs<Reply.Refused>(
            s.port.mintToken(MintRequest(s.seeded.deviceId, KEY_ID, ProofFormat.ANDROID_KEY, forged, challenge)),
            "no token for a forged key attestation",
        )
    }

    clause(
        "ATTEST_A_CHALLENGE_THE_BACKEND_NEVER_ISSUED_IS_REFUSED",
        BackendState.SERVING,
        covers = cells { on<Backend>().answers(Backend::mintToken).with(Reply.Refused::class) },
    ) { s ->
        val notIssued = "a-challenge-no-edge-issued"
        val overIt = "attestation:$KEY_ID:$notIssued".encodeToByteArray()
        assertIs<Reply.Refused>(
            s.port.mintToken(MintRequest(s.seeded.deviceId, KEY_ID, ProofFormat.APP_ATTEST, overIt, notIssued)),
            "no replay against another nonce",
        )
    }

    clause(
        "ATTEST_AN_UNATTESTED_DEVICE_CANNOT_RENEW",
        BackendState.SERVING,
        covers = cells {
            on<Backend> {
                answers(Backend::challenge).withGenericLeaf(Reply.Ok::class)
                answers(Backend::renewToken).with(Reply.Refused::class)
            }
        },
    ) { s ->
        val challenge = assertOk(s.port.challenge())
        val assertion = "assertion:$KEY_ID:$challenge".encodeToByteArray()
        assertIs<Reply.Refused>(
            s.port.renewToken(RenewRequest(s.seeded.deviceId, assertion, challenge)),
            "renewal needs an enrolment",
        )
    }
}

internal const val KEY_ID = "contract-key"
