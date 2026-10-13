package app.snapsync.data.trust

import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.domain.trust.TokenIssuance
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
import kotlin.test.assertNull

/**
 * An `/attest/token` or `/attest/renew` answer, in trust's words (decision record `harden-seam-bug-classes`, D10) — each
 * has its own remedy, and none of them is dropping the token held.
 */
class IssuerRepliesTest {
    private val unverifiable = TokenIssuance.Refused(DeviceRefusal.DEVICE_UNVERIFIABLE, detail = null)

    @Test
    fun a_token_is_minted_by_either_issuer_with_the_expiry_it_carries() {
        val minted = IssuerReplies.minted(MintTokenReply.Minted(Token("D.1800000000.sig")))
        assertEquals(TokenIssuance.Minted(TokenText.parse("D.1800000000.sig")), minted)
        assertEquals(1_800_000_000L, (minted as TokenIssuance.Minted).token.expiry?.at?.epochSeconds)
        assertEquals(
            TokenIssuance.Minted(TokenText.parse("T")),
            IssuerReplies.renewed(RenewTokenReply.Renewed(Token("T"))),
        )
        assertNull(TokenText.parse("a.b.c").expiry, "a token whose expiry cannot be read has none")
    }

    @Test
    fun a_stale_challenge_is_one_fresh_challenge_from_either_issuer() {
        assertEquals(TokenIssuance.ChallengeStale, IssuerReplies.minted(StaleChallenge))
        assertEquals(TokenIssuance.ChallengeStale, IssuerReplies.renewed(StaleChallenge))
    }

    @Test
    fun no_record_on_file_is_its_own_answer() {
        assertEquals(TokenIssuance.NotAttested, IssuerReplies.renewed(RenewTokenReply.NotAttested))
    }

    @Test
    fun any_other_refusal_is_a_verdict_on_what_was_sent_and_a_server_or_no_answer_no_verdict_at_all() {
        assertEquals(unverifiable, IssuerReplies.minted(Invalid("invalid body")))
        assertEquals(unverifiable, IssuerReplies.renewed(RenewTokenReply.AssertionRejected))
        assertEquals(
            unverifiable,
            IssuerReplies.minted(AppTooOld(AppTooOldError(AppTooOldErrorError.APP_TOO_OLD, "9.0"))),
        )
        assertEquals(TokenIssuance.NoAnswer, IssuerReplies.minted(ServerError("write failed")))
        assertEquals(TokenIssuance.NoAnswer, IssuerReplies.renewed(Unreachable("offline")))
        assertEquals(TokenIssuance.NoAnswer, IssuerReplies.renewed(ServerError("write failed")))
        assertEquals(
            TokenIssuance.NoAnswer,
            IssuerReplies.minted(Malformed("not JSON")),
            "an unreadable answer is no verdict",
        )
    }

    @Test
    fun a_refused_attestation_carries_the_reason_named_and_its_diagnostic_code() {
        assertEquals(
            TokenIssuance.Refused(DeviceRefusal.DEVICE_MODIFIED, detail = null),
            IssuerReplies.minted(MintTokenReply.Rejected("attestation rejected: device-modified")),
        )
        assertEquals(
            TokenIssuance.Refused(DeviceRefusal.APP_NOT_GENUINE, detail = null),
            IssuerReplies.minted(MintTokenReply.Rejected("attestation rejected: app-not-genuine\n")),
        )
        assertEquals(
            TokenIssuance.Refused(DeviceRefusal.DEVICE_UNVERIFIABLE, detail = "certificate"),
            IssuerReplies.minted(MintTokenReply.Rejected("attestation rejected: device-unverifiable (certificate)")),
        )
    }

    @Test
    fun an_unknown_or_absent_reason_is_the_default_that_accuses_no_one() {
        assertEquals(unverifiable, RefusalText.parse("attestation rejected: tpm-missing"))
        assertEquals(unverifiable, RefusalText.parse("unattested"))
    }
}
