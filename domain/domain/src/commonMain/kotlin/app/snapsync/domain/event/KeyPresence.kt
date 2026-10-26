package app.snapsync.domain.event

import app.snapsync.domain.membership.Membership

/** What the key slot holds, as read. */
sealed interface KeyFact {
    /** A key, and its id. */
    class Kept(val key: IdentifiedKey) : KeyFact

    /** Nothing: the slot is empty. */
    data object Absent : KeyFact

    /** Something that is no key — an earlier build's, or damaged. None of it is carried. */
    data object Invalid : KeyFact

    /** The store did not answer — a phone locked since it started: read again at the next entry. */
    data object Unreadable : KeyFact
}

/** Whether this device holds the key of its membership's event. Only [Lost] stops anything. */
sealed interface KeyPresence {
    /** No membership, or a plain event's. */
    data object NotNeeded : KeyPresence

    /** The event's own [key]. */
    class Held(val key: IdentifiedKey) : KeyPresence

    /** The slot could not be read: shown as nothing, and never mistaken for a lost key. */
    data object Unknown : KeyPresence

    /**
     * An encrypted event's, and no key of its id is kept: nothing goes up or comes down until its invite gives it back.
     */
    data object Lost : KeyPresence

    companion object {
        /** [membership]'s (any, active or deleted) key presence, given what the slot holds. */
        fun of(
            membership: Membership?,
            fact: KeyFact,
        ): KeyPresence = when (val encryption = membership?.event?.encryption) {
            null, is Encryption.Plain -> NotNeeded
            is Encryption.Encrypted -> encrypted(encryption, fact)
        }

        private fun encrypted(encryption: Encryption.Encrypted, fact: KeyFact): KeyPresence = when (fact) {
            is KeyFact.Kept -> if (fact.key.id == encryption.keyId) Held(fact.key) else Lost
            KeyFact.Absent, KeyFact.Invalid -> Lost
            KeyFact.Unreadable -> Unknown
        }
    }
}

/** What a reopened invite of the joined event does to its key. */
sealed interface RestoreVerdict {
    /** [event]'s key was lost and [keep] is its own: it is kept again. */
    class Restore(val keep: KeyToKeep.Key, val event: EventId) : RestoreVerdict

    /** Nothing is lost — held, unknown, plain, or nothing joined: nothing changes. */
    data object NotLost : RestoreVerdict

    /** The key is lost, and the invite's is another: nothing changes. */
    data object NotTheEventsKey : RestoreVerdict

    companion object {
        fun of(
            membership: Membership?,
            presence: KeyPresence,
            offered: IdentifiedKey,
        ): RestoreVerdict = when (presence) {
            is KeyPresence.NotNeeded, is KeyPresence.Held, is KeyPresence.Unknown -> NotLost
            is KeyPresence.Lost -> membership?.let { lost(it, offered) } ?: NotTheEventsKey
        }

        private fun lost(membership: Membership, offered: IdentifiedKey): RestoreVerdict =
            when (membership.event.encryption.openedBy(offered)) {
                is KeyVerdict.Opens -> Restore(KeyToKeep.Key(offered), membership.event.id)
                KeyVerdict.WrongKey -> NotTheEventsKey
            }
    }
}
