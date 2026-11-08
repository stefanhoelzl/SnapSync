package app.snapsync.domain.push

import app.snapsync.domain.event.EventId

/** The event this device is a member of, as the push's wake read it. */
sealed interface MemberOf {
    /** A membership is held — active, or deleted and still shown — of [event]. */
    data class Event(val event: EventId) : MemberOf

    data object Nothing : MemberOf

    /** The membership cannot be read now: not "no event", and the one answer that is a reason to look at the lock. */
    data object Unreadable : MemberOf
}

/**
 * Whether a silent push's wake joins the tail — the upload arm's **active-event guard** (decision record
 * `changes/own-work-per-wake`, D1). The tail acts on the membership read fresh by every unit, so no push can name the
 * event it works on; what a push can still do wrong is wake it **for another event** — and a locally-left one is the
 * one that matters: leave is local first, so its backend membership persists and keeps pushing this device.
 */
sealed interface PushTailVerdict {
    /** The push names the event this device is a member of. */
    data object Joins : PushTailVerdict

    /** Another event — a left one among them — or none held. */
    data object OtherEvent : PushTailVerdict

    /** The push names no usable event. */
    data object NamesNoEvent : PushTailVerdict

    data object MembershipUnreadable : PushTailVerdict

    companion object {
        fun of(pushed: EventId?, member: MemberOf): PushTailVerdict = when (pushed) {
            null -> NamesNoEvent
            else -> named(pushed, member)
        }

        private fun named(pushed: EventId, member: MemberOf): PushTailVerdict = when (member) {
            is MemberOf.Event -> if (member.event == pushed) Joins else OtherEvent
            MemberOf.Nothing -> OtherEvent
            MemberOf.Unreadable -> MembershipUnreadable
        }
    }
}
