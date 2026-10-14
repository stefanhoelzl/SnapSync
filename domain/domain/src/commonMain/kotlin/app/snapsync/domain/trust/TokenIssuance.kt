package app.snapsync.domain.trust

import app.snapsync.domain.service.ServiceFault

/**
 * What one attempt to obtain a token came to — a mint over a fresh attestation, or a renewal over an assertion.
 * Each case needs a different answer: a stale challenge wants one fresh challenge, not a new key; no answer wants the
 * next wake, not the platform's throttled path; and none of them is a reason to drop the token the device holds.
 */
sealed interface TokenIssuance {
    /** The backend minted [token]. */
    data class Minted(val token: BearerToken) : TokenIssuance

    /** The challenge expired before the backend verified it. One fresh challenge may succeed. */
    data object ChallengeStale : TokenIssuance

    /** The backend holds no attestation for this device: renewing cannot work, attesting can. */
    data object NotAttested : TokenIssuance

    /**
     * The backend verified and declined, for [reason], which the user is told; [detail] is its diagnostic code beside
     * it (`certificate`), which the user is never told and an offered report carries.
     */
    data class Refused(val reason: DeviceRefusal, val detail: String?) : TokenIssuance

    /** The proof could not be produced on this device, so no request was sent and there is no verdict to tell. */
    data object ProofFailed : TokenIssuance

    /**
     * No answer: no connection, a server's failure, or a body that names no token — [fault] says which, as the member
     * is told it (decision record `changes/tell-service-faults`).
     */
    data class NoAnswer(val fault: ServiceFault) : TokenIssuance
}
