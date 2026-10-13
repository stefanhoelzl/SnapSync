package app.snapsync.data.trust

import app.snapsync.data.backend.BackendExchange
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.trust.AttestKeyId
import app.snapsync.domain.trust.ChallengeVerdict
import app.snapsync.domain.trust.TokenIssuance
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.ports.ProveResult
import app.snapsync.wire.api.ChallengeReply
import app.snapsync.wire.api.Routes
import app.snapsync.wire.models.MintRequest
import app.snapsync.wire.models.RenewRequest
import kotlin.io.encoding.Base64

/**
 * The backend's token issuers — `/attest/challenge`, `/attest/token`, `/attest/renew`, all open — and the platform's
 * integrity service that proves over their challenge: one renewal or one mint per call, each over a fresh challenge and
 * once more over another if the first went stale before the backend verified it (the app can be suspended between
 * fetching a challenge and using it). Translation only: what an issuance means is the domain's.
 *
 * It reaches the backend through the [BackendExchange], never the gateway: the issuers are open (their `401`s are their
 * own answer, never a verdict on a token), and a recovery that went through the gateway it recovers for would re-enter
 * itself.
 */
class AttestationIssuer(private val integrity: DeviceIntegrity, private val exchange: BackendExchange) {
    private val log = Log("AttestationIssuer")

    /** A renewal: an assertion by the key [key] names, over a fresh challenge. Local work, no throttled path. */
    suspend fun renew(device: DeviceId, key: AttestKeyId): TokenIssuance {
        val first = renewOnce(device, key)
        return when (ChallengeVerdict.of(first)) {
            ChallengeVerdict.RetryOnce -> renewOnce(device, key)
            ChallengeVerdict.Done -> first
        }
    }

    /**
     * A mint: a NEW key, attested over a fresh challenge — the platform's throttled path. Answers the key it was made
     * with, which is kept only once the backend accepted it: a key id stored for an attestation the backend never
     * recorded would send every renewal down the assertion path against a key the server has never heard of.
     */
    suspend fun mint(device: DeviceId): Minting {
        val first = mintOnce(device)
        return when (ChallengeVerdict.of(first.issuance)) {
            ChallengeVerdict.RetryOnce -> mintOnce(device)
            ChallengeVerdict.Done -> first
        }
    }

    private suspend fun renewOnce(device: DeviceId, key: AttestKeyId): TokenIssuance {
        val challenge = challenge() ?: return TokenIssuance.NoAnswer
        return renewOver(device, key, challenge)
    }

    private suspend fun mintOnce(device: DeviceId): Minting {
        val challenge = challenge() ?: return Minting.Unproved(TokenIssuance.NoAnswer)
        return mintOver(device, challenge)
    }

    private suspend fun challenge(): String? =
        (exchange.send(Routes.Challenge.request()) as? ChallengeReply.Issued)?.let { it.challenge.challenge }

    /** A failure to produce the assertion is LOCAL — no request was sent — and logged with the platform's own words. */
    private suspend fun renewOver(device: DeviceId, key: AttestKeyId, challenge: String): TokenIssuance =
        when (val proved = integrity.prove(challenge, key.value)) {
            is ProveResult.Ok -> IssuerReplies.renewed(
                exchange.send(
                    Routes.RenewToken.request(RenewRequest(device.value, Base64.encode(proved.proof.bytes), challenge)),
                ),
            )
            is ProveResult.Refused -> TokenIssuance.ProofFailed.also {
                log.w("could not produce a renewal assertion", "reason" of proved.reason)
            }
        }

    private suspend fun mintOver(device: DeviceId, challenge: String): Minting =
        when (val proved = integrity.prove(challenge)) {
            is ProveResult.Ok -> Minting.Proved(mintWith(device, challenge, proved), ProvedKey.of(proved.proof))
            is ProveResult.Refused -> Minting.Unproved(TokenIssuance.ProofFailed).also {
                log.w("attestation failed", "reason" of proved.reason)
            }
        }

    private suspend fun mintWith(device: DeviceId, challenge: String, proved: ProveResult.Ok): TokenIssuance {
        val proof = Proofs.wire(proved.proof) ?: return TokenIssuance.ProofFailed
        return IssuerReplies.minted(
            exchange.send(Routes.MintToken.request(MintRequest(device.value, challenge, proof))),
        )
    }
}

/** One mint's answer: with the key it was proved with, or without a proof at all. */
sealed class Minting(val issuance: TokenIssuance) {
    class Proved(issuance: TokenIssuance, val key: ProvedKey) : Minting(issuance)

    class Unproved(issuance: TokenIssuance) : Minting(issuance)
}

/** The key a mint was proved with: its id, and the chain it presented where the platform exposes one. */
class ProvedKey(val keyId: AttestKeyId, val chain: PresentedChain?) {
    companion object {
        fun of(proof: app.snapsync.ports.Proof): ProvedKey =
            ProvedKey(AttestKeyId(proof.handle), proof.chain?.let(PresentedChain::of))
    }
}
