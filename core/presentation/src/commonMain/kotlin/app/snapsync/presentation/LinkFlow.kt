package app.snapsync.presentation

import app.snapsync.domain.request.AppLinkRequest
import app.snapsync.domain.request.LinkOutcome
import app.snapsync.domain.request.OpenLinkRequest
import app.snapsync.presentation.RenameRequests.answerTo
import app.snapsync.ui.state.LinkNotice

/**
 * A page opened outside the app — the menu's site pages, the update notice's store page — and, when the phone did not
 * open it, the brief word saying so and where the page is: otherwise the member is left with a tap that did nothing
 * they can see. An opened page says nothing: the member is in the browser or the store.
 */
internal class LinkFlow(
    private val cells: ScreenCells,
    private val commands: UserCommands,
    private val requests: RequestStatusFlows,
    private val notices: NoticeSlot,
    private val facts: BuildFacts,
) {
    /** Open [link], and tell the member where it is if the phone did not open it. */
    suspend fun open(link: AppLinkRequest) {
        val id = cells.ids.next()
        commands.platform.openLink.fire(OpenLinkRequest(id, link))
        when (requests.link.answerTo(id)) {
            LinkOutcome.OPENED -> Unit
            LinkOutcome.NOT_OPENED -> notices.show(NoticeSlot.Notice.Link(noticeFor(link)))
        }
    }

    private fun noticeFor(link: AppLinkRequest): LinkNotice = when (link) {
        is AppLinkRequest.SitePage -> LinkNotice.Site(facts.siteAddress)
        is AppLinkRequest.StorePage -> LinkNotice.Store(link.store.name)
    }
}
