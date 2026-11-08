package app.snapsync.presentation

import app.snapsync.domain.report.ReportContext
import app.snapsync.domain.request.ReportRequest
import app.snapsync.presentation.JoinedLayer.shown
import app.snapsync.presentation.JoinedLayer.shownCounts
import app.snapsync.presentation.RenameRequests.answerTo
import app.snapsync.presentation.ScreenCells.Companion.editOverlays
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch

/** A bug report sent from the sheet, and the brief word on what became of it. */
internal class ReportFlow(
    private val cells: ScreenCells,
    private val commands: UserCommands,
    private val requests: RequestStatusFlows,
    private val scope: CoroutineScope,
) {
    private val local = cells.local
    private var noticeClear: Job? = null

    /**
     * Send the diagnostic dump with the operator's account of the problem — already trimmed and length-bounded by the
     * sheet that collected it — an opaque label for the surface it was sent from (the screen, which only the screen
     * itself can name), and the counts that surface showed, taken from the state the container last reduced
     * ([shownCounts]). On a build that reports nowhere the report is kept on the device instead
     * (`UiState.reportDestination` says which). Once it has been handed off, the app briefly says what became of it
     * (`Overlays.reportNotice`) — sent, saved, or neither — and makes no delivery claim (the channel may queue and
     * retransmit).
     */
    fun send(note: String, screen: String) = cells.runner.intent {
        // Read and spent here: the sheet is dismissed before it sends, so the report remembers how it was opened.
        val verification = local.getAndUpdate { it.copy(verifiedReport = false) }.verifiedReport
        val id = cells.ids.next()
        val shown = shownCounts(cells.runner.current().ui)
        commands.sendDiagnostics.fire(ReportRequest(id, note, ReportContext(screen, shown, verification)))
        val outcome = requests.report.answerTo(id)
        // Shown, then cleared after a moment; a later report's word replaces an earlier one's.
        local.editOverlays { it.copy(reportNotice = outcome.shown) }
        noticeClear?.cancel()
        noticeClear = scope.launch {
            delay(REPORT_NOTICE_MILLIS)
            local.editOverlays { it.copy(reportNotice = null) }
        }
    }

    /** The report's brief word, tapped away before it cleared itself. */
    fun dismissNotice() = cells.runner.intent {
        noticeClear?.cancel()
        local.editOverlays { it.copy(reportNotice = null) }
    }
}

/** How long the word on a sent report stays: long enough to read, then gone. */
private const val REPORT_NOTICE_MILLIS = 4_000L
