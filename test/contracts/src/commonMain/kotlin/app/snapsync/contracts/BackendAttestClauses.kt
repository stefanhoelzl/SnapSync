package app.snapsync.contracts

import app.snapsync.wire.api.AppTooOld
import app.snapsync.wire.api.ChallengeReply
import app.snapsync.wire.api.MintTokenReply
import app.snapsync.wire.api.NotThisDevice
import app.snapsync.wire.api.PutDeviceReply
import app.snapsync.wire.api.RenewTokenReply
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.StaleChallenge
import app.snapsync.wire.models.AndroidKeyProof
import app.snapsync.wire.models.AppleAppAttestProof
import app.snapsync.wire.models.DeviceConfig
import app.snapsync.wire.models.MintRequest
import app.snapsync.wire.models.PushRegistration
import app.snapsync.wire.models.RenewRequest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The three open `/attest/…` operations that issue the credential: the challenge, every **refusal**, and a mint and
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
@OptIn(ExperimentalEncodingApi::class)
internal fun ClauseList<BackendState, EdgeSubject<BackendClient>>.attestClauses() {
    clause(
        "ATTEST_A_CHALLENGE_IS_ISSUED",
        BackendState.SERVING,
        covers = cells { backend(Routes.Challenge.ID, ChallengeReply.Issued::class) },
    ) { s ->
        assertTrue(s.challenge().isNotBlank(), "the open challenge route answers")
    }

    // The version gate stands in front of the issuers too: a build too old for the API is told so before it attests.
    clause(
        "ATTEST_A_REFUSED_BUILD_IS_ISSUED_NO_CHALLENGE",
        BackendState.VERSION_REFUSED,
        covers = cells { backend(Routes.Challenge.ID, AppTooOld::class) },
    ) { s ->
        assertAnswer<AppTooOld>(s.port.send(Routes.Challenge.request()), "the issuers are behind the version gate")
    }

    clause(
        "ATTESTABLE_AN_ATTESTATION_MINTS_AND_ITS_KEY_RENEWS",
        BackendState.ATTESTABLE,
        covers = cells {
            backend(Routes.Challenge.ID, ChallengeReply.Issued::class)
            backend(Routes.MintToken.ID, MintTokenReply.Minted::class)
            backend(Routes.RenewToken.ID, RenewTokenReply.Renewed::class)
        },
    ) { s ->
        val minted = s.mintWithOwnKey()
        assertTrue(minted.isNotBlank())
        val key = s.attester!!
        val challenge = s.challenge()
        val renewed = assertAnswer<RenewTokenReply.Renewed>(
            s.port.send(
                Routes.RenewToken.request(RenewRequest(s.seeded.deviceId, b64(key.sign(challenge)), challenge)),
            ),
            "the attested key's signature over a fresh challenge renews it",
        )
        assertTrue(renewed.token.token.isNotBlank())
    }

    clause(
        "ATTESTABLE_A_SIGNATURE_OVER_ANYTHING_BUT_THE_CHALLENGE_IS_REJECTED",
        BackendState.ATTESTABLE,
        covers = cells {
            backend(Routes.MintToken.ID, MintTokenReply.Minted::class)
            backend(Routes.RenewToken.ID, RenewTokenReply.AssertionRejected::class)
        },
    ) { s ->
        s.mintWithOwnKey()
        // The challenge is stateless — two issued within one second are one — so the signature is over other text.
        val signed = s.attester!!.sign("not the challenge")
        assertAnswer<RenewTokenReply.AssertionRejected>(
            s.port.send(Routes.RenewToken.request(RenewRequest(s.seeded.deviceId, b64(signed), s.challenge()))),
            "an assertion is over the challenge it is sent with",
        )
    }

    // A token acts for the one device it was minted for (`api/openapi.yaml`, `deviceToken`).
    clause(
        "ATTESTABLE_A_TOKEN_ACTS_ONLY_FOR_ITS_OWN_DEVICE",
        BackendState.ATTESTABLE,
        covers = cells {
            backend(Routes.MintToken.ID, MintTokenReply.Minted::class)
            backend(Routes.PutDevice.ID, PutDeviceReply.Recorded::class)
            backend(Routes.PutDevice.ID, NotThisDevice::class)
        },
    ) { s ->
        val holder = s.declaring(ClientIdentity(SERVED_APP_VERSION, s.mintWithOwnKey()))
        val config = DeviceConfig(PushRegistration("fcm", "a-token", "production"))
        assertAnswer<PutDeviceReply.Recorded>(
            holder.send(Routes.PutDevice.request(s.seeded.deviceId, config)),
            "the token acts for its own device",
        )
        assertAnswer<NotThisDevice>(
            holder.send(Routes.PutDevice.request(s.setup.freshId(), config)),
            "and for no other",
        )
    }

    clause(
        "ATTEST_A_FORGED_ATTESTATION_IS_REFUSED",
        BackendState.SERVING,
        covers = cells {
            backend(Routes.Challenge.ID, ChallengeReply.Issued::class)
            backend(Routes.MintToken.ID, MintTokenReply.Rejected::class)
        },
    ) { s ->
        val proof = AppleAppAttestProof(KEY_ID, b64("not an attestation".encodeToByteArray()))
        assertAnswer<MintTokenReply.Rejected>(
            s.port.send(Routes.MintToken.request(MintRequest(s.seeded.deviceId, s.challenge(), proof))),
            "no token for a forgery",
        )
    }

    clause(
        "ATTEST_A_FORGED_ANDROID_KEY_ATTESTATION_IS_REFUSED",
        BackendState.SERVING,
        covers = cells {
            backend(Routes.Challenge.ID, ChallengeReply.Issued::class)
            backend(Routes.MintToken.ID, MintTokenReply.Rejected::class)
        },
    ) { s ->
        // Shaped as a chain of one DER certificate, and not one: the Android verifier, not a body check, refuses it.
        val proof = AndroidKeyProof(listOf(b64(byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x00))))
        assertAnswer<MintTokenReply.Rejected>(
            s.port.send(Routes.MintToken.request(MintRequest(s.seeded.deviceId, s.challenge(), proof))),
            "no token for a forged key attestation",
        )
    }

    clause(
        "ATTEST_A_CHALLENGE_THE_BACKEND_NEVER_ISSUED_IS_STALE",
        BackendState.SERVING,
        covers = cells { backend(Routes.MintToken.ID, StaleChallenge::class) },
    ) { s ->
        val notIssued = "a-challenge-no-edge-issued"
        val proof = AppleAppAttestProof(KEY_ID, b64("attestation:$KEY_ID:$notIssued".encodeToByteArray()))
        assertAnswer<StaleChallenge>(
            s.port.send(Routes.MintToken.request(MintRequest(s.seeded.deviceId, notIssued, proof))),
            "no replay against another nonce — and a stale challenge rejects no credential",
        )
    }

    clause(
        "ATTEST_AN_UNATTESTED_DEVICE_CANNOT_RENEW",
        BackendState.SERVING,
        covers = cells {
            backend(Routes.Challenge.ID, ChallengeReply.Issued::class)
            backend(Routes.RenewToken.ID, RenewTokenReply.NotAttested::class)
        },
    ) { s ->
        val challenge = s.challenge()
        val assertion = b64("assertion:$KEY_ID:$challenge".encodeToByteArray())
        // A device that never attested: not the seeded one, which holds a token.
        val unattested = s.setup.freshId()
        assertAnswer<RenewTokenReply.NotAttested>(
            s.port.send(Routes.RenewToken.request(RenewRequest(unattested, assertion, challenge))),
            "renewal needs an enrolment",
        )
    }
}

/** A challenge the backend issues. */
private suspend fun EdgeSubject<BackendClient>.challenge(): String =
    assertAnswer<ChallengeReply.Issued>(port.send(Routes.Challenge.request())).challenge.challenge

/** This state's device attested with the binding's own key: the token minted for it. */
@OptIn(ExperimentalEncodingApi::class)
private suspend fun EdgeSubject<BackendClient>.mintWithOwnKey(): String {
    val key = assertNotNull(attester, "a binding that reaches this state attests with a key of its own")
    val challenge = challenge()
    val proof = key.proof(challenge)
    return assertAnswer<MintTokenReply.Minted>(
        port.send(Routes.MintToken.request(MintRequest(seeded.deviceId, challenge, proof))),
        "an attestation over a challenge the backend issued mints a token",
    ).token.token
}

internal const val KEY_ID = "contract-key"

@OptIn(ExperimentalEncodingApi::class)
private fun b64(bytes: ByteArray): String = Base64.encode(bytes)
