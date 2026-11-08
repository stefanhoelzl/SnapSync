package app.snapsync.domain.trust

import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.time.Timestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The decisions trust stands on — when to renew, what can authorize a request, what each answer means. */
class TrustVerdictsTest {
    private val now = Timestamp.of(Instant.fromEpochSeconds(1_800_000_000))

    private fun expiring(inDays: Int) = BearerToken("t", Timestamp.of(now.at + inDays.days))

    private val unread = BearerToken("not-a-token", expiry = null)

    @Test
    fun renewal_is_due_once_less_than_a_week_remains_and_for_a_token_missing_or_unread() {
        // The margin is the whole point: waiting for expiry would mean the FIRST failed upload discovers the dead token.
        assertEquals(RenewalVerdict.Fresh, RenewalVerdict.of(expiring(8), now))
        assertEquals(RenewalVerdict.Due, RenewalVerdict.of(expiring(6), now))
        assertEquals(RenewalVerdict.Due, RenewalVerdict.of(expiring(-1), now))
        assertEquals(RenewalVerdict.Due, RenewalVerdict.of(null, now))
        assertEquals(RenewalVerdict.Due, RenewalVerdict.of(unread, now))
    }

    @Test
    fun a_token_authorizes_until_the_instant_it_expires_and_one_missing_or_unread_never() {
        // SNAPSYNC-20: a token due for renewal still authorizes every request — usability is NOT renewal.
        assertEquals(UsabilityVerdict.Usable, UsabilityVerdict.of(expiring(3), now))
        assertEquals(
            UsabilityVerdict.Usable,
            UsabilityVerdict.of(BearerToken("t", now), now),
            "valid while now <= expiry",
        )
        assertEquals(
            UsabilityVerdict.Unusable,
            UsabilityVerdict.of(BearerToken("t", Timestamp.of(now.at - 1.seconds)), now),
        )
        assertEquals(UsabilityVerdict.Unusable, UsabilityVerdict.of(null, now))
        assertEquals(UsabilityVerdict.Unusable, UsabilityVerdict.of(unread, now))
    }

    @Test
    fun a_device_is_attested_when_it_obtained_a_token_or_holds_one_that_still_authorizes() {
        assertEquals(AttestationHealthVerdict.Attested, AttestationHealthVerdict.of(true, UsabilityVerdict.Unusable))
        assertEquals(AttestationHealthVerdict.Attested, AttestationHealthVerdict.of(false, UsabilityVerdict.Usable))
        assertEquals(AttestationHealthVerdict.Unattested, AttestationHealthVerdict.of(false, UsabilityVerdict.Unusable))
    }

    @Test
    fun only_a_stale_challenge_earns_a_second_one() {
        assertEquals(ChallengeVerdict.RetryOnce, ChallengeVerdict.of(TokenIssuance.ChallengeStale))
        for (other in listOf(
            TokenIssuance.NoAnswer(ServiceFault.UNREACHABLE),
            TokenIssuance.NotAttested,
            TokenIssuance.ProofFailed,
        )) {
            assertEquals(ChallengeVerdict.Done, ChallengeVerdict.of(other))
        }
    }

    @Test
    fun a_renewal_keeps_the_held_token_when_the_answer_says_nothing_and_attests_afresh_when_it_cannot_work() {
        val token = expiring(30)
        assertEquals(token, (RenewalAnswerVerdict.of(TokenIssuance.Minted(token)) as RenewalAnswerVerdict.Accept).token)
        assertEquals(
            RenewalAnswerVerdict.KeepHeld,
            RenewalAnswerVerdict.of(TokenIssuance.NoAnswer(ServiceFault.UNREACHABLE)),
        )
        assertEquals(RenewalAnswerVerdict.KeepHeld, RenewalAnswerVerdict.of(TokenIssuance.ChallengeStale))
        for (cannot in listOf(
            TokenIssuance.NotAttested,
            TokenIssuance.ProofFailed,
            TokenIssuance.Refused(DeviceRefusal.DEVICE_UNVERIFIABLE, detail = null),
        )) {
            assertEquals(RenewalAnswerVerdict.AttestAfresh, RenewalAnswerVerdict.of(cannot), "$cannot")
        }
    }

    @Test
    fun a_mint_tells_only_the_services_refusal() {
        val token = expiring(30)
        assertEquals(token, (MintAnswerVerdict.of(TokenIssuance.Minted(token)) as MintAnswerVerdict.Accept).token)
        assertEquals(
            MintAnswerVerdict.Refused(DeviceRefusal.DEVICE_MODIFIED, "certificate"),
            MintAnswerVerdict.of(TokenIssuance.Refused(DeviceRefusal.DEVICE_MODIFIED, "certificate")),
        )
        for (none in listOf(
            TokenIssuance.NoAnswer(ServiceFault.UNREACHABLE),
            TokenIssuance.ChallengeStale,
            TokenIssuance.NotAttested,
            TokenIssuance.ProofFailed,
        )) {
            assertEquals(MintAnswerVerdict.NoVerdict, MintAnswerVerdict.of(none), "$none is no verdict on the phone")
        }
    }

    @Test
    fun only_a_401_rejects_the_credential_a_request_carried() {
        assertEquals(CredentialVerdict.Rejected, CredentialVerdict.of(BackendAnswer.Rejected))
        for (other in listOf(
            BackendAnswer.Served,
            BackendAnswer.Refused,
            BackendAnswer.TooOld(null),
            BackendAnswer.ServerIssue,
            BackendAnswer.Updating,
            BackendAnswer.NoAnswer,
        )) {
            assertEquals(CredentialVerdict.Answered, CredentialVerdict.of(other), "$other is no verdict on a token")
        }
    }

    @Test
    fun a_request_is_retried_once_only_with_a_token_other_than_the_one_rejected() {
        val sent = BearerToken("T1", null)
        val other = BearerToken("T2", null)
        assertEquals(other, (RetryVerdict.of(sent, other) as RetryVerdict.Retry).token)
        assertEquals(RetryVerdict.Stands, RetryVerdict.of(sent, BearerToken("T1", null)))
        assertEquals(RetryVerdict.Stands, RetryVerdict.of(sent, null))
    }

    @Test
    fun only_the_token_a_rejected_request_carried_is_dropped() {
        assertEquals(RejectionVerdict.Drop, RejectionVerdict.of(BearerToken("T1", null), BearerToken("T1", null)))
        assertEquals(
            RejectionVerdict.AlreadyReplaced,
            RejectionVerdict.of(BearerToken("T1", null), BearerToken("T2", null)),
        )
        assertEquals(RejectionVerdict.AlreadyReplaced, RejectionVerdict.of(BearerToken("T1", null), null))
    }

    @Test
    fun a_token_renders_masked_and_a_key_id_in_full() {
        val token = BearerToken("D.1800000000.secret", null)
        assertTrue("secret" !in token.toString())
        assertEquals(token.hashCode(), BearerToken("D.1800000000.secret", now).hashCode(), "equal by its secret")
        assertEquals("key-1", AttestKeyId("key-1").toString())
        assertEquals(AttestKeyId("key-1"), AttestKeyId("key-1"))
        assertEquals(AttestKeyId("key-1").hashCode(), AttestKeyId("key-1").hashCode())
        assertTrue(AttestKeyId("key-1") != AttestKeyId("key-2"))
        assertTrue((token as Any) != "D.1800000000.secret", "a token equals only a token")
        assertTrue((AttestKeyId("key-1") as Any) != "key-1", "a key id equals only a key id")
    }
}
