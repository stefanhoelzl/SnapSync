package app.snapsync.domain.push

/**
 * A push registration as the backend would hold it — the device's push service, environment, id and token, in the one
 * form a registration is recorded as. The domain compares two and never reads one: what it spells is the data layer's,
 * which builds it ([text], its stored form) from what the platform delivered and reads it back from the record.
 */
data class PushRegistrationKey(val text: String)

/** What asks for the registration: an OS delivery of the token, a new device credential, or a join. */
enum class PushTrigger {
    /** The OS delivered the token — at every app entry, mostly the same one: published only when it changed. */
    DELIVERED,

    /** The device obtained a new credential: what the backend refused for want of one is acceptable now. */
    CREDENTIAL,

    /** A join: the registration is published whatever the record holds. */
    JOINED,
}

/** The last registration the backend accepted, as recorded. "None" and "unreadable" both publish. */
sealed interface LastRegistration {
    data class Recorded(val key: PushRegistrationKey) : LastRegistration

    data object None : LastRegistration

    data object Unavailable : LastRegistration
}

/**
 * Whether a registration is published (decision record `changes/own-work-per-wake`, D12): an OS delivery publishes only
 * a registration that differs from the last one the backend accepted — the OS answers every entry, mostly with the same
 * token, and publishing each was measured on 102 of 108 process starts — while a new credential and a join publish
 * whatever the record holds: they heal a registration the backend lost or refused, so a record that believes otherwise
 * must not suppress them.
 */
enum class PushRegistrationVerdict {
    PUBLISH,

    /** Delivered again, and already the one the backend accepted. */
    UNCHANGED,
    ;

    companion object {
        fun of(trigger: PushTrigger, current: PushRegistrationKey, last: LastRegistration): PushRegistrationVerdict =
            when (trigger) {
                PushTrigger.DELIVERED -> changed(current, last)
                PushTrigger.CREDENTIAL, PushTrigger.JOINED -> PUBLISH
            }

        private fun changed(current: PushRegistrationKey, last: LastRegistration) = when (last) {
            is LastRegistration.Recorded -> if (last.key == current) UNCHANGED else PUBLISH
            LastRegistration.None, LastRegistration.Unavailable -> PUBLISH
        }
    }
}
