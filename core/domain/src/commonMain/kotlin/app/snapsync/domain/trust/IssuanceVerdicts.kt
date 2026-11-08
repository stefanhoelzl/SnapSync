package app.snapsync.domain.trust

/** A challenge that went stale before the backend verified it earns exactly one fresh one; anything else stands. */
sealed interface ChallengeVerdict {
    data object RetryOnce : ChallengeVerdict

    data object Done : ChallengeVerdict

    companion object {
        fun of(first: TokenIssuance): ChallengeVerdict =
            if (first == TokenIssuance.ChallengeStale) RetryOnce else Done
    }
}

/**
 * After a renewal: accept the token; keep the one held when the answer says nothing about this device (no answer, a
 * stale challenge — the token may well still authorize every request, and the platform's throttled path is not
 * spent on it); or attest afresh when the backend holds no record of the device or declined, or the assertion could
 * not be made.
 */
sealed interface RenewalAnswerVerdict {
    class Accept(val token: BearerToken) : RenewalAnswerVerdict

    data object KeepHeld : RenewalAnswerVerdict

    data object AttestAfresh : RenewalAnswerVerdict

    companion object {
        fun of(answer: TokenIssuance): RenewalAnswerVerdict = when (answer) {
            is TokenIssuance.Minted -> Accept(answer.token)
            is TokenIssuance.NoAnswer, TokenIssuance.ChallengeStale -> KeepHeld
            TokenIssuance.NotAttested, is TokenIssuance.Refused, TokenIssuance.ProofFailed -> AttestAfresh
        }
    }
}

/**
 * After a mint: accept the token; tell the service's refusal; or nothing to tell — a proof this device could not
 * produce, or no answer, is no verdict on the phone.
 */
sealed interface MintAnswerVerdict {
    class Accept(val token: BearerToken) : MintAnswerVerdict

    data class Refused(val reason: DeviceRefusal, val detail: String?) : MintAnswerVerdict

    data object NoVerdict : MintAnswerVerdict

    companion object {
        fun of(answer: TokenIssuance): MintAnswerVerdict = when (answer) {
            is TokenIssuance.Minted -> Accept(answer.token)
            is TokenIssuance.Refused -> Refused(answer.reason, answer.detail)
            is TokenIssuance.NoAnswer,
            TokenIssuance.ChallengeStale,
            TokenIssuance.NotAttested,
            TokenIssuance.ProofFailed,
            -> NoVerdict
        }
    }
}
