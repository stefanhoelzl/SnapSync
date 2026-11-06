package app.snapsync.presentation

import app.snapsync.presentation.range.EventRange
import app.snapsync.presentation.range.RangeEdit
import app.snapsync.presentation.range.after
import app.snapsync.presentation.range.validUntil
import kotlinx.coroutines.flow.update

/**
 * The range calendars: the gestures on the create form's calendar or the open one's; and the calendar over a
 * participation surface, which opens on the range the surface resolves, complete — so the first tap on a day starts a
 * new range — held to the share-range rule around the event's window. OK takes its span as a custom range ([form]);
 * closing changes nothing.
 */
class RangePicker internal constructor(
    private val cells: ScreenCells,
    private val display: Display,
    private val cutoffFormatter: CutoffFormatter,
    private val form: FormEdits,
) {
    private val local = cells.local

    /**
     * A gesture on a range calendar: the open calendar's, or else the create form's. Applied at once, in the order the
     * gestures came, so a drag's moves land one after another.
     */
    internal fun onEdit(edit: RangeEdit) = local.update { l ->
        val picking = l.picking
        if (picking != null) {
            l.copy(picking = picking.copy(range = picking.range.after(edit, picking.bounds, picking.range.from.hour)))
        } else {
            val hour = cutoffFormatter.nowLocal().hour
            l.copy(create = l.create.choose(l.create.range.after(edit, display.createBounds, hour)))
        }
    }

    fun onOpen() {
        val r = cells.runner.current().shown.range ?: return
        val range = EventRange(r.from, r.until.date, r.until.time, endPending = false)
        local.update { it.copy(picking = Picking(range, cutoffFormatter.shareBounds(r.windowStart, r.windowEnd))) }
    }

    fun onDismiss() = local.update { it.copy(picking = null) }

    fun onConfirm() {
        val open = local.value.picking ?: return
        val until = open.range.validUntil(open.bounds) ?: return
        onDismiss()
        form.onRangeCustom(open.range.from, until)
    }
}
