package app.snapsync.model

/**
 * Whether this process's echo-suppression read can answer right now — asked by the upload cycle after its
 * admission and before anything else.
 */
sealed interface SuppressionReadiness {
    /** It can answer (an absent store answers "nothing suppressed": nothing was ever downloaded). */
    data object Ready : SuppressionReadiness

    /** The store is at an older schema this process may not migrate: pause, and ask to be re-invoked. */
    data object OldSchema : SuppressionReadiness

    /** The store could not be opened: "I could not look" — upload nothing this run. */
    data class Unavailable(val detail: String) : SuppressionReadiness
}
