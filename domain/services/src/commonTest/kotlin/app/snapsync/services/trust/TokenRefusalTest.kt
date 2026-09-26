package app.snapsync.services.trust

import app.snapsync.model.TokenOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A refused `/attest/token` or `/attest/renew`, classified by its status and plain-text body (decision record
 * `harden-seam-bug-classes`, D10) — each class has its own remedy, and none of them is dropping the token held.
 */
class TokenRefusalTest {

    @Test
    fun a_stale_challenge_is_one_fresh_challenge_under_both_api_versions() {
        assertEquals(TokenOutcome.ChallengeStale, tokenRefusal(409, "stale challenge"))
        assertEquals(TokenOutcome.ChallengeStale, tokenRefusal(401, " stale challenge\n"), "the frozen v1 says it with a 401")
    }

    @Test
    fun no_record_on_file_is_its_own_answer() {
        assertEquals(TokenOutcome.NotAttested, tokenRefusal(401, "not attested"))
    }

    @Test
    fun any_other_4xx_is_a_verdict_on_what_was_sent_and_a_5xx_no_verdict_at_all() {
        assertEquals(TokenOutcome.Refused, tokenRefusal(401, "attestation rejected"))
        assertEquals(TokenOutcome.Refused, tokenRefusal(400, "bad body"))
        assertEquals(TokenOutcome.Unreachable, tokenRefusal(502, "write failed"))
    }
}
