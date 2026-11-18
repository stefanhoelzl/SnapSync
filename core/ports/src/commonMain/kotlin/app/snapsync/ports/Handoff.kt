package app.snapsync.ports

/**
 * What became of something this app handed to the platform (the `SystemUi` port).
 *
 * Both answers are recorded, because they have different consequences (`docs/architecture.md`, "Absence is never
 * silent"): after [Accepted] the user is elsewhere and it is not this app's business; after [Refused] the user is
 * still here, and the tap they made did nothing they can see. For a page opened outside the app, the member is told
 * of a refusal; a refused share sheet is only recorded.
 */
sealed interface Handoff {
    /** The platform took it: the sheet is on screen, or the app claiming the URL is. */
    data object Accepted : Handoff

    /**
     * Nothing was handed off. [reason] is for the log, in the adapter's own words; [circumstances] is what the adapter
     * could read about the platform's state when it refused, because the platform itself may give no reason.
     */
    data class Refused(val reason: String, val circumstances: RefusalCircumstances) : Handoff
}

/**
 * What the platform's state was when it refused a hand-off: read for a page opened outside the app, whose refusal the
 * platform does not explain (iOS answers only "not opened"), and [NotAsked] where the reason is already the whole
 * story.
 */
sealed interface RefusalCircumstances {
    /** The adapter did not look: its [Handoff.Refused.reason] already says why. */
    data object NotAsked : RefusalCircumstances

    /**
     * What the platform said, each fact read or not:
     * - [inFront]: whether this app was the one in front when the platform answered;
     * - [claimed]: whether the platform reports some app able to open what was asked for;
     * - [scene]: the platform's own word for the state of the app's window, for the log only.
     */
    data class Read(
        val inFront: Observed<Boolean>,
        val claimed: Observed<Boolean>,
        val scene: Observed<String>,
    ) : RefusalCircumstances
}

/** One fact about the platform: the value it gave, or why there is none — never a guess in its place. */
sealed interface Observed<out T> {
    /** The platform answered [value]. */
    data class Seen<out T>(val value: T) : Observed<T> {
        override fun toString() = value.toString()
    }

    /** There is no answer to read, for [reason]. */
    data class Unread(val reason: String) : Observed<Nothing> {
        override fun toString() = "unread ($reason)"
    }
}
