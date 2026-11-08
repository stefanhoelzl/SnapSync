package app.snapsync.domain.event

/** Whether an event's photos are stored encrypted, and under which key. */
sealed interface Encryption {
    /** Whether [key] — an invite's, or none — opens an event of this encryption. */
    fun openedBy(key: IdentifiedKey?): KeyVerdict

    /** Stored as they are: an invite carries no key, and one that does is not this event's. */
    data object Plain : Encryption {
        override fun openedBy(key: IdentifiedKey?): KeyVerdict =
            if (key == null) KeyVerdict.Opens(KeyToKeep.None) else KeyVerdict.WrongKey
    }

    /** Stored encrypted under the key whose id is [keyId]: only that key opens it. */
    data class Encrypted(val keyId: EventKeyId) : Encryption {
        override fun openedBy(key: IdentifiedKey?): KeyVerdict =
            if (key != null && key.id == keyId) KeyVerdict.Opens(KeyToKeep.Key(key)) else KeyVerdict.WrongKey
    }
}

/** Whether an invite's key opens its event. */
sealed interface KeyVerdict {
    /** It does; a join keeps [keep]. */
    class Opens(val keep: KeyToKeep) : KeyVerdict

    /** A key the event does not want: another key, a key for a plain event, or none for an encrypted one. */
    data object WrongKey : KeyVerdict
}

/** What a join or a restore keeps in the key slot — produced only by a verdict that checked it. */
sealed interface KeyToKeep {
    /** A plain event's: the slot is cleared, so no earlier event's key outlives its membership. */
    data object None : KeyToKeep

    /** An encrypted event's own key. */
    class Key(val key: IdentifiedKey) : KeyToKeep
}
