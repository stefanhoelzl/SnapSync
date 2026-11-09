package app.snapsync.domain.event

/**
 * How an event's name came off the server (decision record `changes/seal-event-name`): served readable — a plain
 * event, or an encrypted one created before names were sealed — or sealed, and then opened with the event's key, or
 * not. Only what the data layer could do with it; what it comes to is [NameVerdict.of]'s.
 */
sealed interface NameRead {
    /** The text the server served readable, or the name the event's key opened its sealed record to. */
    class Text(val text: String) : NameRead

    /** A sealed record the event's own key does not open: altered, or not this event's — the service misbehaving. */
    data object Damaged : NameRead

    /** A sealed record, and no key of the event's on this device to open it. */
    data object NoKey : NameRead
}

/** What an event's name, as read, comes to. */
sealed interface NameVerdict {
    /** The event is called [name]. */
    class Named(val name: EventName) : NameVerdict

    /**
     * No name could be read from what was served: a load that failed, retried like any other — never an event joined
     * or shown unnamed.
     */
    data object Unreadable : NameVerdict

    /**
     * Sealed under a key this device does not hold, and no name known from before: the event exists, but only its
     * whole invite names it.
     */
    data object Withheld : NameVerdict

    companion object {
        /**
         * [read], given the name this device last knew the event by ([lastKnown], `null` for an event it never
         * joined). A record that does not open under the event's own key is never covered by [lastKnown]: the key is
         * there, so the service sent something other than the event's record.
         */
        fun of(read: NameRead, lastKnown: EventName?): NameVerdict = when (read) {
            is NameRead.Text -> when (val name = EventName.of(read.text)) {
                is EventNameVerdict.Ok -> Named(name.name)
                EventNameVerdict.Blank, EventNameVerdict.TooLong -> Unreadable
            }
            NameRead.Damaged -> Unreadable
            NameRead.NoKey -> lastKnown?.let(::Named) ?: Withheld
        }
    }
}

/** Which of its two forms a served event named it by. */
sealed interface ServedName {
    /** Readable: a plain event, or an encrypted one created before names were sealed. */
    class Readable(val text: String) : ServedName

    /** Sealed: an encrypted event's record, for the event's key to open. */
    class Sealed(val record: String) : ServedName

    /** Neither — a served event that names itself not at all, which [NameVerdict.of] refuses as a blank name. */
    data object Neither : ServedName

    companion object {
        /** The served [name] when there is one, else the sealed [record]. */
        fun of(name: String?, record: String?): ServedName = when {
            name != null -> Readable(name)
            record != null -> Sealed(record)
            else -> Neither
        }
    }
}
