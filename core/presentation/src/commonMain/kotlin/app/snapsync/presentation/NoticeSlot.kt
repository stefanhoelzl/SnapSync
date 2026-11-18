package app.snapsync.presentation

import app.snapsync.presentation.ScreenCells.Companion.editOverlays
import app.snapsync.ui.state.LinkNotice
import app.snapsync.ui.state.Overlays
import app.snapsync.ui.state.ReportNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The one place on the screen where a brief word shows — what became of a sent report, or that a page did not open —
 * until it clears itself or is tapped away. The words share the place: a newer one replaces whatever was there and
 * stays its own few seconds. The automatic clear is a timer here, not in the UI, so the reduction stays the one place
 * a flag changes.
 */
internal class NoticeSlot(private val cells: ScreenCells, private val scope: CoroutineScope) {
    private val local = cells.local
    private var clear: Job? = null

    /** What the slot can show. */
    sealed interface Notice {
        data class Report(val notice: ReportNotice) : Notice

        data class Link(val notice: LinkNotice) : Notice
    }

    /** Show [notice] in place of whatever was there, and clear it after a moment. */
    fun show(notice: Notice) {
        local.editOverlays { it.holding(notice) }
        clear?.cancel()
        clear = scope.launch {
            delay(NOTICE_MILLIS)
            local.editOverlays { it.holding(null) }
        }
    }

    /** The word, tapped away before it cleared itself. */
    fun dismiss() = cells.runner.intent {
        clear?.cancel()
        local.editOverlays { it.holding(null) }
    }

    private fun Overlays.holding(notice: Notice?) = copy(
        reportNotice = (notice as? Notice.Report)?.notice,
        linkNotice = (notice as? Notice.Link)?.notice,
    )
}

/** How long a brief word stays: long enough to read, then gone. */
private const val NOTICE_MILLIS = 4_000L
