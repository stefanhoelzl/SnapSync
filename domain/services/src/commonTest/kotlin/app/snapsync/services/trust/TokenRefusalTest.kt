package app.snapsync.services.trust

import app.snapsync.model.DeviceRefusal
import app.snapsync.model.TokenOutcome
import app.snapsync.wire.api.AppTooOld
import app.snapsync.wire.api.Invalid
import app.snapsync.wire.api.Malformed
import app.snapsync.wire.api.MintTokenReply
import app.snapsync.wire.api.RenewTokenReply
import app.snapsync.wire.api.ServerError
import app.snapsync.wire.api.StaleChallenge
import app.snapsync.wire.api.Unreachable
import app.snapsync.wire.models.AppTooOldError
import app.snapsync.wire.models.AppTooOldErrorError
import app.snapsync.wire.models.Token
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An `/attest/token` or `/attest/renew` answer, classified (decision record `harden-seam-bug-classes`, D10) — each
 * class has its own remedy, and none of them is dropping the token held.
 */
class TokenRefusalTest {

    @Test
    fun a_token_is_minted_by_either_issuer() {
        assertEquals(TokenOutcome.Minted("T"), mintOutcome(MintTokenReply.Minted(Token("T"))))
        assertEquals(TokenOutcome.Minted("T"), renewOutcome(RenewTokenReply.Renewed(Token("T"))))
    }

    @Test
    fun a_stale_challenge_is_one_fresh_challenge_from_either_issuer() {
        assertEquals(TokenOutcome.ChallengeStale, mintOutcome(StaleChallenge))
        assertEquals(TokenOutcome.ChallengeStale, renewOutcome(StaleChallenge))
    }

    @Test
    fun no_record_on_file_is_its_own_answer() {
        assertEquals(TokenOutcome.NotAttested, renewOutcome(RenewTokenReply.NotAttested))
    }

    @Test
    fun any_other_refusal_is_a_verdict_on_what_was_sent_and_a_server_or_no_answer_no_verdict_at_all() {
        val unverifiable = TokenOutcome.Refused(DeviceRefusal.DEVICE_UNVERIFIABLE)
        assertEquals(unverifiable, mintOutcome(Invalid("invalid body")))
        assertEquals(unverifiable, renewOutcome(RenewTokenReply.AssertionRejected))
        assertEquals(unverifiable, mintOutcome(AppTooOld(AppTooOldError(AppTooOldErrorError.APP_TOO_OLD, "9.0"))))
        assertEquals(TokenOutcome.Unreachable, mintOutcome(ServerError("write failed")))
        assertEquals(TokenOutcome.Unreachable, renewOutcome(Unreachable("offline")))
        assertEquals(TokenOutcome.Unreachable, mintOutcome(Malformed("not JSON")), "an unreadable answer is no verdict")
    }

    @Test
    fun a_refused_attestation_carries_the_reason_named() {
        assertEquals(
            TokenOutcome.Refused(DeviceRefusal.DEVICE_MODIFIED),
            mintOutcome(MintTokenReply.Rejected("attestation rejected: device-modified")),
        )
        assertEquals(
            TokenOutcome.Refused(DeviceRefusal.APP_NOT_GENUINE),
            mintOutcome(MintTokenReply.Rejected("attestation rejected: app-not-genuine\n")),
        )
        assertEquals(
            TokenOutcome.Refused(DeviceRefusal.DEVICE_UNVERIFIABLE),
            mintOutcome(MintTokenReply.Rejected("attestation rejected: device-unverifiable")),
        )
    }

    @Test
    fun a_certificate_problem_carries_its_diagnostic_code_beside_the_reason() {
        assertEquals(
            TokenOutcome.Refused(DeviceRefusal.DEVICE_UNVERIFIABLE, detail = "certificate"),
            mintOutcome(MintTokenReply.Rejected("attestation rejected: device-unverifiable (certificate)")),
        )
    }

    @Test
    fun an_unknown_reason_is_the_default_that_accuses_no_one() {
        assertEquals(
            TokenOutcome.Refused(DeviceRefusal.DEVICE_UNVERIFIABLE),
            mintOutcome(MintTokenReply.Rejected("attestation rejected: tpm-missing")),
        )
    }
}
