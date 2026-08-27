package app.snapsync.ui.components


/**
 * The variant axis of the status hero. A sealed semantic value, not five components,
 * because the variant is runtime data arriving from UI state — [Progress] even carries a
 * payload. (Design-time choices remain distinct components, per the design-system rules.)
 */
sealed interface StatusIndicator {
    data object Success : StatusIndicator
    data object Error : StatusIndicator
    data object Waiting : StatusIndicator

    /** Neutral photo-library glyph: an ask, not a fault. */
    data object Photos : StatusIndicator

    /** Indeterminate spinner: work with no measurable progress (e.g. reading persisted state). */
    data object Loading : StatusIndicator

    /** LED dot: sync underway. The skin paints it amber/yellow. */
    data object InProgress : StatusIndicator

    /** LED dot: caught up (everything synced, or nothing to sync). The skin paints it green. */
    data object Complete : StatusIndicator
}
