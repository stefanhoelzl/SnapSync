package app.snapsync.domain.report

/**
 * One fact a report carries about the device or the app, as it could be read at the moment it was taken.
 *
 * Three cases because a report renders them three ways: a [Known] value is written as its label, an [Unsupported] fact
 * — one the platform does not have at all, Android's standby bucket on an iPhone — is left out, and a [Failed] read is
 * written as [Failed.shown], so a reading that could not be taken is never mistaken for one that does not exist.
 */
sealed interface Fact<out T> {
    data class Known<out T>(val value: T) : Fact<T>

    data object Unsupported : Fact<Nothing>

    data class Failed(val reason: String) : Fact<Nothing> {
        /** How a report writes it: `failed (<reason>)`, the reason cut to [FAILURE_REASON_CHARS]. */
        val shown: String get() = "failed (${reason.take(FAILURE_REASON_CHARS)})"
    }

    companion object {
        /**
         * The longest reason a failed fact carries in a report: a platform message can be arbitrarily long, and the
         * state section's share of the whole-event sum (`DiagnosticDump.LOG_BUDGET_BYTES`) is fixed.
         */
        const val FAILURE_REASON_CHARS: Int = 80
    }
}
