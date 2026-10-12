package app.snapsync.ui.state

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
 * One preset, not a start list and an end list: the surface offers exactly three choices, side by side, and the model
 * has exactly three values — a start preset crossed with an end preset admitted combinations no surface
 * offered any more (decision record `simplify-join-screen`, D1).
 */
@Serializable
enum class RangeChoice {
    /**
     * From the event's start until its end — exactly the event's own dates, and the default. Not "the whole event": a
     * custom range may reach outside the event's dates, so this is not the widest a range can be.
     */
    EVENT_START,

    /**
     * From the present instant until the event's end. Offered only while the present is INSIDE the event
     * window: outside it this preset would clamp to a bound the member did not choose.
     */
    FROM_NOW,

    /**
     * A range the member picked on the calendar — at most an event's length and overlapping the event, and it MAY
     * reach outside the event's dates.
     */
    CUSTOM,
}
