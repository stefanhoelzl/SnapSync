package app.snapsync.model

/**
 * What an `/attest/token` or `/attest/renew` call came to, classified by the adapter that owns the route (capability
 * `privacy-security`, "Only a rejected credential is invalidated, and only that one"; decision record
 * `harden-seam-bug-classes`, D10).
 *
 * It used to be `String?`, and every `null` got one answer — attest afresh — whatever the cause. Each case below
 * needs a different one: a stale challenge wants one fresh challenge, not a new key; an unreachable backend wants
 * the next wake, not Apple's throttled path; and none of them is a reason to drop the token the device holds.
 */
sealed interface TokenOutcome {
    /** The backend minted [token]. */
    data class Minted(val token: String) : TokenOutcome

    /** The challenge expired before the backend verified it (v2's `409 stale challenge`). One fresh challenge may succeed. */
    data object ChallengeStale : TokenOutcome

    /** The backend holds no attestation for this device (`401 not attested`): renewing cannot work, attesting can. */
    data object NotAttested : TokenOutcome

    /** The backend verified and declined — the attestation or assertion itself was rejected, or the body was invalid. */
    data object Refused : TokenOutcome

    /** No answer: transport failure, a `5xx`, or a body that names no token. The next wake retries. */
    data object Unreachable : TokenOutcome
}
