package app.snapsync.presentation

import app.snapsync.domain.mobiledata.MobileDataChoice
import app.snapsync.domain.mobiledata.MobileDataOutcome
import app.snapsync.domain.request.AppLinkRequest
import app.snapsync.domain.request.MobileDataRequest
import app.snapsync.query.mobiledata.MobileDataQuery
import app.snapsync.ui.state.AppLink
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.SwitchTo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Opening and closing what is drawn over — or instead of — the current layer.
 *
 * Grouped for the same reason the form is: these are one question ("what is on screen"), and each of them reduces and
 * nothing more. That is the property decision record `add-reconfigure-membership` D4 asked for — a pure navigation act
 * must not cross a flow command — and it still holds now that the answer is state rather than a screen-held flag.
 */
class SurfaceCommands internal constructor(
    private val cells: ScreenCells,
    private val commands: UserCommands,
    private val mobileDataQuery: MobileDataQuery,
    /** The choice the menu's switch shows: read as the screen is built, then moved only by a change's answer. */
    private val mobileData: MutableStateFlow<Boolean>,
    private val settings: SettingsChanges,
    private val report: ReportFlow,
) {
    private val local = cells.local
    private val runner = cells.runner

    fun onConfirmLeaveOpen() = runner.intent { local.editOverlays { it.copy(confirmingLeave = true) } }

    fun onConfirmLeaveDismiss() = runner.intent { local.editOverlays { it.copy(confirmingLeave = false) } }

    /**
     * Open the rename sheet on a clean latch: a rename that finished after the sheet was dismissed left its terminal
     * status behind, and the sheet would read it as this edit's outcome and close itself.
     */
    fun onRenameOpen() = runner.intent {
        local.update { it.copy(renameRequest = null, overlays = it.overlays.copy(renaming = true)) }
    }

    fun onRenameDismiss() = runner.intent { local.editOverlays { it.copy(renaming = false) } }

    fun onQrOpen() = runner.intent { local.editOverlays { it.copy(showingQr = true) } }

    fun onQrDismiss() = runner.intent { local.editOverlays { it.copy(showingQr = false) } }

    fun onReportBugOpen() = runner.intent {
        local.update {
            it.copy(overlays = it.overlays.copy(reportingBug = true, reportSeed = null), verifiedReport = false)
        }
    }

    /** "Report this" beside a refusal: the same sheet, its description written for [message]. */
    fun onReportRefusal(message: ScreenMessage) = runner.intent {
        local.update {
            it.copy(
                overlays = it.overlays.copy(menuOpen = false, reportingBug = true, reportSeed = message),
                verifiedReport = true,
            )
        }
    }

    fun onReportBugDismiss() = runner.intent {
        local.editOverlays { it.copy(reportingBug = false, reportSeed = null) }
    }

    /** The app menu. Where the layer does not offer it, an open flag is masked. */
    fun onMenuOpen() = runner.intent {
        local.update { it.copy(overlays = it.overlays.copy(menuOpen = true), mobileDataNotSaved = false) }
    }

    fun onMenuDismiss() = runner.intent {
        local.update { it.copy(overlays = it.overlays.copy(menuOpen = false), mobileDataNotSaved = false) }
    }

    /**
     * The menu's mobile-data switch: applied as it is flipped, the menu staying open. The switch shows the device's
     * choice, which only a save moves — so a flip that could not be saved leaves it where it was, and the menu says so
     * (decision record `changes/archive/2026-10-07-mobile-data-per-device`, D3).
     */
    fun onMobileData(to: SwitchTo) = runner.intent {
        local.update { it.copy(mobileDataNotSaved = false) }
        val id = cells.ids.next()
        commands.setMobileData.fire(MobileDataRequest(id, to.mobileData()))
        val outcome = mobileDataQuery.changes.answerTo(id)
        mobileData.value = outcome.inEffect == MobileDataChoice.ALLOWED
        when (outcome) {
            is MobileDataOutcome.Saved -> Unit
            is MobileDataOutcome.NotSaved -> local.update { it.copy(mobileDataNotSaved = true) }
        }
    }

    /** The menu's "Report a problem": the menu gives way to the sheet in one edit, so the two never stack. */
    fun onMenuReportBug() = runner.intent {
        local.update {
            it.copy(
                overlays = it.overlays.copy(menuOpen = false, reportingBug = true, reportSeed = null),
                verifiedReport = false,
            )
        }
    }

    /**
     * One of the menu's links, opened outside the app. The menu closes first, so coming back finds the screen rather
     * than the drawer; a refused hand-off is logged by the command.
     */
    fun onOpenLink(link: AppLink) = runner.intent {
        local.editOverlays { it.copy(menuOpen = false) }
        commands.platform.openLink.fire(AppLinkRequest.SitePage(link.page))
    }

    /** The report's brief word, tapped away before it cleared itself. */
    fun onReportNoticeDismiss() = report.dismissNotice()

    /** Open the event's settings, pre-filled from the membership held ([SettingsChanges.open]). */
    fun onOpenReconfigure() = settings.open()

    /** Close the event's settings ([SettingsChanges.close]). */
    fun onCancelReconfigure() = settings.close()
}
