package app.snapsync.domain.trust

import app.snapsync.domain.time.Timestamp
import kotlin.time.Duration.Companion.days

/**
 * Should this wake spend a renewal? A token is renewed once it has less than [RENEW_WITHIN] left — a full week early,
 * because renewing at every wake the app happens to get is the only thing that keeps it alive while the OS starves
 * the app of wakes. A missing token, or one whose expiry cannot be read, is due.
 *
 * NOT [UsabilityVerdict], and the two must never be collapsed: a due token still authorizes every request until it
 * expires (`SNAPSYNC-20`).
 */
sealed interface RenewalVerdict {
    data object Fresh : RenewalVerdict

    data object Due : RenewalVerdict

    companion object {
        val RENEW_WITHIN = 7.days

        fun of(held: BearerToken?, now: Timestamp): RenewalVerdict {
            val expiry = held?.expiry ?: return Due
            return if (expiry - now < RENEW_WITHIN) Due else Fresh
        }
    }
}

/**
 * Can the held token authorize a request right now — as the backend judges it, valid while `now <= expiry`? A missing
 * token, or one whose expiry cannot be read, cannot. What the status screen's "cannot be verified" stands on.
 */
sealed interface UsabilityVerdict {
    data object Usable : UsabilityVerdict

    data object Unusable : UsabilityVerdict

    companion object {
        fun of(held: BearerToken?, now: Timestamp): UsabilityVerdict {
            val expiry = held?.expiry ?: return Unusable
            return if (now > expiry) Unusable else Usable
        }
    }
}

/** Whether a device holds a usable token after a refresh: it obtained one, or the one it holds still authorizes. */
sealed interface AttestationHealthVerdict {
    data object Attested : AttestationHealthVerdict

    data object Unattested : AttestationHealthVerdict

    companion object {
        fun of(obtained: Boolean, held: UsabilityVerdict): AttestationHealthVerdict =
            if (obtained || held == UsabilityVerdict.Usable) Attested else Unattested
    }
}
