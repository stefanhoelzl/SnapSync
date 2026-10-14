package app.snapsync.data.trust

import app.snapsync.data.backend.faultOf
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.domain.trust.TokenIssuance
import app.snapsync.ports.Proof
import app.snapsync.ports.ProofFormat
import app.snapsync.wire.api.ClientError
import app.snapsync.wire.api.MintTokenReply
import app.snapsync.wire.api.NoAnswer
import app.snapsync.wire.api.RenewTokenReply
import app.snapsync.wire.api.Reply
import app.snapsync.wire.api.ServerIssue
import app.snapsync.wire.api.StaleChallenge
import app.snapsync.wire.models.AndroidKeyProof
import app.snapsync.wire.models.AppleAppAttestProof
import kotlin.io.encoding.Base64

/**
 * The issuers' answers in trust's words — each route's own vocabulary, read only here (decision record
 * `harden-seam-bug-classes`, D10).
 */
internal object IssuerReplies {
    /** An `/attest/token` answer: [MintTokenReply.Rejected] names the reason the user is told. */
    fun minted(reply: MintTokenReply): TokenIssuance = when (reply) {
        is MintTokenReply.Minted -> TokenIssuance.Minted(TokenText.parse(reply.token.token))
        is MintTokenReply.Rejected -> RefusalText.parse(reply.text)
        StaleChallenge -> TokenIssuance.ChallengeStale
        is ClientError -> REFUSED
        is ServerIssue, is NoAnswer -> noAnswer(reply)
    }

    /** An `/attest/renew` answer: [RenewTokenReply.NotAttested] is "no record on file — attest afresh". */
    fun renewed(reply: RenewTokenReply): TokenIssuance = when (reply) {
        is RenewTokenReply.Renewed -> TokenIssuance.Minted(TokenText.parse(reply.token.token))
        RenewTokenReply.NotAttested -> TokenIssuance.NotAttested
        StaleChallenge -> TokenIssuance.ChallengeStale
        is ClientError -> REFUSED
        is ServerIssue, is NoAnswer -> noAnswer(reply)
    }

    /** No verdict, and how the service failed to give one. */
    fun noAnswer(
        reply: Reply,
    ): TokenIssuance.NoAnswer = TokenIssuance.NoAnswer(faultOf(reply) ?: ServiceFault.UNREACHABLE)

    /**
     * Beside each route's own answers: a stale challenge — one fresh challenge is the remedy; any other refusal — a
     * verdict on what was sent, told as the default reason; no answer, or a server's, is no verdict at all.
     */
    private val REFUSED = TokenIssuance.Refused(DeviceRefusal.DEVICE_UNVERIFIABLE, detail = null)
}

/**
 * A refused attestation's body: `attestation rejected: <reason>` or `<reason> (<detail>)`. A reason this build does not
 * know — or none — is [DeviceRefusal.DEVICE_UNVERIFIABLE], the one that never accuses the user; the detail is a
 * diagnostic code, never told to the user.
 */
internal object RefusalText {
    private const val PREFIX = "attestation rejected:"

    fun parse(body: String): TokenIssuance.Refused {
        val named = body.substringAfter(PREFIX, "").trim()
        val detail = named.substringAfter("(", "").substringBefore(")").trim().takeIf { it.isNotEmpty() }
        val reason = named.substringBefore("(").trim()
        return TokenIssuance.Refused(
            DeviceRefusal.entries.firstOrNull { it.wireName == reason } ?: DeviceRefusal.DEVICE_UNVERIFIABLE,
            detail,
        )
    }
}

/** A proof as the mint takes it: its format names the verifier the backend runs. */
internal object Proofs {
    /** [proof] on the wire, or `null` for an Android chain that is not whole DER — never sent as fewer certificates. */
    fun wire(proof: Proof): app.snapsync.wire.models.Proof? = when (proof.format) {
        ProofFormat.APP_ATTEST -> AppleAppAttestProof(keyId = proof.handle, attestation = Base64.encode(proof.bytes))
        // The chain crosses the integrity port as concatenated DER; the backend takes its certificates one by one.
        ProofFormat.ANDROID_KEY -> DerChain.elements(proof.bytes)?.let { chain ->
            AndroidKeyProof(chain = chain.map { Base64.encode(it) })
        }
    }
}
