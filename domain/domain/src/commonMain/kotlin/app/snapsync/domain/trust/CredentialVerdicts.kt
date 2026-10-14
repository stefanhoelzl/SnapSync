package app.snapsync.domain.trust

/** A backend answer as the credential and the version gate read it: the category of the route's reply. */
sealed interface BackendAnswer {
    /** The route served the request. */
    data object Served : BackendAnswer

    /** `401` to a gated route: the backend rejected the credential the request carried. */
    data object Rejected : BackendAnswer

    /** Any other refusal the app made it to: a client error past the backend's gates. */
    data object Refused : BackendAnswer

    /** `426`: the backend refuses this build as too old, naming the oldest it serves when it does. */
    data class TooOld(val minimumVersion: String?) : BackendAnswer

    /** The server failed, or something before it did. */
    data object ServerIssue : BackendAnswer

    /** `503`: the service is briefly unavailable for an update — a server issue the member is told apart. */
    data object Updating : BackendAnswer

    /** No answer reached the app, or one outside the contract. */
    data object NoAnswer : BackendAnswer
}

/**
 * Whether an answer to a request that carried a token is a rejection of THAT token — only a [BackendAnswer.Rejected]
 * is; the expiry check already sees an expired one, so a rejection means the backend no longer honours it at all.
 */
sealed interface CredentialVerdict {
    data object Rejected : CredentialVerdict

    data object Answered : CredentialVerdict

    companion object {
        fun of(answer: BackendAnswer): CredentialVerdict =
            if (answer == BackendAnswer.Rejected) Rejected else Answered
    }
}

/** A rejected request is retried once, and only with a token other than the one rejected; else the rejection stands. */
sealed interface RetryVerdict {
    class Retry(val token: BearerToken) : RetryVerdict

    data object Stands : RetryVerdict

    companion object {
        fun of(sent: BearerToken, recovered: BearerToken?): RetryVerdict =
            if (recovered != null && recovered != sent) Retry(recovered) else Stands
    }
}

/**
 * Compare-and-clear: a rejection names the token the refused request CARRIED, and only that one is dropped. A late
 * rejection of a token already replaced — by this process or the other — must not erase its successor.
 */
sealed interface RejectionVerdict {
    data object Drop : RejectionVerdict

    data object AlreadyReplaced : RejectionVerdict

    companion object {
        fun of(sent: BearerToken, held: BearerToken?): RejectionVerdict = if (held == sent) Drop else AlreadyReplaced
    }
}
