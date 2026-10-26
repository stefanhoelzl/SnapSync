package app.snapsync.domain.event

import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.membership.Membership

/** How one upload goes up. Never plaintext into an encrypted event, and never sealed for another event than its own. */
sealed interface SealVerdict {
    /** A plain event's: the bytes as the library holds them. */
    data object Plain : SealVerdict

    /** An encrypted event's: sealed under its [key], bound to the resource as [device] shares it. */
    class Sealed(val key: IdentifiedKey, val device: DeviceId) : SealVerdict

    /** Nothing goes up now, [because] — the next cycle asks again. */
    class Withheld(val because: Because) : SealVerdict {
        enum class Because {
            /** No membership, or one of another event than the bytes go to: the cycle outlived its membership. */
            NOT_JOINED,

            /** The key cannot be read now — a phone locked since it started. */
            KEY_UNREADABLE,

            /** The event's key is not kept. */
            KEY_LOST,

            /** This device cannot name itself now, and a sealed file is bound to it. */
            DEVICE_UNKNOWN,
        }
    }

    companion object {
        /**
         * How bytes bound for [sendingTo] — the event the cycle's requests address — go up, given the [membership]
         * held, its key's [presence] and this device's id ([device], `null` when it cannot be read).
         */
        fun of(membership: Membership?, sendingTo: EventId, presence: KeyPresence, device: DeviceId?): SealVerdict =
            when (membership) {
                null -> Withheld(Withheld.Because.NOT_JOINED)
                else -> sending(membership.event, sendingTo, presence, device)
            }

        private fun sending(joined: Event, sendingTo: EventId, presence: KeyPresence, device: DeviceId?): SealVerdict =
            when (joined.id == sendingTo) {
                true -> joined(joined.encryption, presence, device)
                false -> Withheld(Withheld.Because.NOT_JOINED)
            }

        private fun joined(encryption: Encryption, presence: KeyPresence, device: DeviceId?): SealVerdict =
            when (encryption) {
                is Encryption.Encrypted -> keyed(presence, device)
                is Encryption.Plain -> Plain
            }

        private fun keyed(presence: KeyPresence, device: DeviceId?): SealVerdict = when (presence) {
            is KeyPresence.Held -> device?.let { Sealed(presence.key, it) } ?: Withheld(Withheld.Because.DEVICE_UNKNOWN)
            is KeyPresence.Unknown -> Withheld(Withheld.Because.KEY_UNREADABLE)
            is KeyPresence.Lost, is KeyPresence.NotNeeded -> Withheld(Withheld.Because.KEY_LOST)
        }
    }
}

/** Whether a downloaded resource may be opened, and with which key. */
sealed interface OpenVerdict {
    /** With the joined [event]'s [key]. */
    class OpenWith(val key: IdentifiedKey, val event: EventId) : OpenVerdict

    /** Nothing is joined. */
    data object NotJoined : OpenVerdict

    /** Fetched for an event a switch left behind: only the joined event's key opens anything. */
    data object OtherEvent : OpenVerdict

    /** The key cannot be read now — a phone locked since it started. */
    data object KeyUnreadable : OpenVerdict

    /** No key of the event is kept: the membership's state, not this file's fault — it counts toward no report. */
    data object NoKey : OpenVerdict

    companion object {
        /**
         * Whether a resource fetched for [fetchedFor] opens, given the [membership] held and its key's [presence].
         * [fetchedFor] is `null` for a transfer started before transfers named their event (`PENDING_CLEANUPS.md`): it
         * was fetched for the event joined then, which is the one joined now.
         */
        fun of(membership: Membership?, fetchedFor: EventId?, presence: KeyPresence): OpenVerdict = when (membership) {
            null -> NotJoined
            else -> joined(membership.event.id, fetchedFor ?: membership.event.id, presence)
        }

        private fun joined(joined: EventId, fetchedFor: EventId, presence: KeyPresence): OpenVerdict =
            when (fetchedFor == joined) {
                true -> keyed(joined, presence)
                false -> OtherEvent
            }

        private fun keyed(joined: EventId, presence: KeyPresence): OpenVerdict = when (presence) {
            is KeyPresence.Held -> OpenWith(presence.key, joined)
            is KeyPresence.Unknown -> KeyUnreadable
            is KeyPresence.Lost, is KeyPresence.NotNeeded -> NoKey
        }
    }
}

/** One resource's failures to open in a row, and whether this one is reported. */
class OpenFailures private constructor(val count: Int) {
    /** One more. */
    fun next(): OpenFailures = OpenFailures(count + 1)

    /**
     * Retrying forever must not be silent: the [REPORT_AFTER]th failure in a row is reported ONCE — past a transfer
     * glitch, and still within the first hour — and nothing after it, so a file that never opens costs one report.
     */
    val report: Report get() = if (count == REPORT_AFTER) Report.ONCE else Report.QUIETLY

    enum class Report { ONCE, QUIETLY }

    companion object {
        const val REPORT_AFTER: Int = 4

        val NONE: OpenFailures = OpenFailures(0)
    }
}
