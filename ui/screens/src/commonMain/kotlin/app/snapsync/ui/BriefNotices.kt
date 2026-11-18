package app.snapsync.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.snapsync.ui.components.AppNotice
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.link_site_not_opened
import app.snapsync.ui.resources.link_store_not_opened
import app.snapsync.ui.resources.report_not_sent
import app.snapsync.ui.resources.report_saved
import app.snapsync.ui.resources.report_sent
import app.snapsync.ui.state.LinkNotice
import app.snapsync.ui.state.Overlays
import app.snapsync.ui.state.ReportNotice
import org.jetbrains.compose.resources.stringResource

/**
 * The one place at the foot of the screen where a brief word shows — what became of a sent report, or that a page did
 * not open. The presentation sets at most one of the two, so they never stack.
 */
@Composable
internal fun BoxScope.BriefNotice(overlays: Overlays, actions: MenuActions) {
    overlays.reportNotice?.let { outcome ->
        AppNotice(
            text = reportNoticeText(outcome),
            onDismiss = actions.onReportNoticeDismiss,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    overlays.linkNotice?.let { notice ->
        AppNotice(
            text = linkNoticeText(notice),
            onDismiss = actions.onLinkNoticeDismiss,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * The word on a confirmed report: what became of it, and never more — "sent" is a
 * hand-off, so it claims no delivery, and the failure is told calmly.
 */
@Composable
internal fun reportNoticeText(outcome: ReportNotice): String = stringResource(
    when (outcome) {
        ReportNotice.SENT -> Res.string.report_sent
        ReportNotice.SAVED -> Res.string.report_saved
        ReportNotice.NOT_SENT -> Res.string.report_not_sent
    },
)

/**
 * The word on a page the phone did not open: that it did not, told calmly, and where the
 * page is — the site's address, or the store by name.
 */
@Composable
internal fun linkNoticeText(notice: LinkNotice): String = when (notice) {
    is LinkNotice.Site -> stringResource(Res.string.link_site_not_opened, notice.address)
    is LinkNotice.Store -> stringResource(Res.string.link_store_not_opened, notice.store)
}
