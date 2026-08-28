package app.snapsync.model

import kotlinx.serialization.Serializable

/**
 * The two ends of a member's capture-date **range**, as PRESETS rather than instants (capability
 * `photo-selection-policy`).
 *
 * They live in `model/` for the same reason [Arrow] does: they are the one vocabulary the presentation
 * reduction and the design-system skin BOTH name. The reduction resolves a preset against the event
 * window to produce the instant that would be committed; the picker renders which preset is selected.
 * Putting them in either UI module would force an edge between two modules that deliberately have none —
 * `:ui:presentation` is Compose-free and `:ui:components` knows nothing of the reduction — and the
 * shared enum would have to be duplicated or the layering inverted.
 *
 * A preset is deliberately **semantic**, not an instant: "the event's start" survives an event whose
 * start is later corrected, where a captured instant would silently keep the old value.
 */
@Serializable
enum class FromChoice {
    /** Share everything back to the event's start — the default, and the floor. */
    EVENT_START,

    /**
     * Start contributing at the present instant. Offered only while the present is INSIDE the event
     * window: outside it this preset would clamp to a bound the member did not choose, so it is shown
     * disabled rather than as a button that visibly does nothing.
     */
    NOW,

    /** A guest who arrived partway through picks their own start, bounded to the window. */
    CUSTOM,
}

/**
 * The upper bound of the same range (capability `photo-selection-policy`); see [FromChoice] for why this
 * lives in `model/`.
 */
@Serializable
enum class UntilChoice {
    /** Share up to when the event ends — the default. Narrow, never widen. */
    EVENT_END,

    /** Stop contributing earlier than the event's end, bounded to the window. */
    CUSTOM,
}
