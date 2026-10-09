package app.snapsync.model

import kotlinx.serialization.Serializable

/**
 * A member's capture-date **range**, as ONE preset rather than two instants.
 *
 * It lives in `model/` for the same reason [Arrow] does: it is the one vocabulary the presentation
 * reduction and the design-system skin BOTH name. The reduction resolves the preset against the event
 * window to produce the instants that would be committed; the range row and its dialog render which
 * preset is selected. Putting it in either UI module would force an edge between two modules that
 * deliberately have none — `:domain:presentation` is Compose-free and `:ui:components` knows nothing of
 * the reduction.
 *
 * One preset, not a start list and an end list: the surface offers exactly three choices, and the model
 * has exactly three values — a start preset crossed with an end preset admitted combinations no surface
 * offered any more (decision record `simplify-join-screen`, D1).
 */
@Serializable
enum class RangeChoice {
    /** The whole event window — the default, and the widest a range can be. */
    WHOLE_EVENT,

    /**
     * From the present instant until the event's end. Offered only while the present is INSIDE the event
     * window: outside it this preset would clamp to a bound the member did not choose.
     */
    FROM_NOW,

    /** A range the member picked on the calendar, bounded to the window. */
    CUSTOM,
}
