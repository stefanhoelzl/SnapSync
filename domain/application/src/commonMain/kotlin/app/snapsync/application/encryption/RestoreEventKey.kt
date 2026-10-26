package app.snapsync.application.encryption

import app.snapsync.application.EntryPointCall
import app.snapsync.data.crypto.EventKeyIds
import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.crypto.KeepOutcome
import app.snapsync.data.membership.MembershipRead
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.KeyPresence
import app.snapsync.domain.event.RestoreVerdict
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.RestoreAnswer

/**
 * Keep the key a reopened invite of the joined event carries — only while this device has LOST the event's key, and
 * only the event's own. Anything else changes nothing.
 */
class RestoreEventKey(
    private val membership: MembershipRepository,
    private val keys: EventKeyStore,
    private val ids: EventKeyIds,
) {
    @EntryPointCall
    suspend operator fun invoke(offered: EventKey): RestoreOutcome = held().let { held ->
        restored(RestoreVerdict.of(held, KeyPresence.of(held, keys.state.value), ids.identify(offered)))
    }

    private fun held(): Membership? = when (val read = membership.state.value) {
        is MembershipRead.Joined -> read.membership
        is MembershipRead.NotJoined, is MembershipRead.Unavailable -> null
    }

    private fun restored(verdict: RestoreVerdict): RestoreOutcome = when (verdict) {
        is RestoreVerdict.Restore -> kept(keys.keep(verdict.keep, verdict.event))
        RestoreVerdict.NotLost, RestoreVerdict.NotTheEventsKey -> RestoreOutcome.NothingChanged
    }

    private fun kept(outcome: KeepOutcome): RestoreOutcome = when (outcome) {
        is KeepOutcome.Kept -> RestoreOutcome.Restored
        is KeepOutcome.Refused -> RestoreOutcome.Refused(outcome.detail)
    }
}

/** What a reopened invite's key came to; [answer] is what the request is answered, [resumes] whether the work does. */
sealed interface RestoreOutcome {
    val answer: RestoreAnswer
    val resumes: Boolean

    /** The lost key is kept again. */
    data object Restored : RestoreOutcome {
        override val answer = RestoreAnswer.Restored
        override val resumes = true
    }

    /** Nothing was lost, or the invite's key was another: nothing changed. */
    data object NothingChanged : RestoreOutcome {
        override val answer = RestoreAnswer.NothingChanged
        override val resumes = false
    }

    /** The store refused the key. */
    class Refused(val detail: String) : RestoreOutcome {
        override val answer = RestoreAnswer.Failed
        override val resumes = false
    }
}
