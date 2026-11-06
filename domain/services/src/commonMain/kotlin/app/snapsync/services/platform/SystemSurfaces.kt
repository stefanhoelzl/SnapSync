package app.snapsync.services.platform

import app.snapsync.domain.request.AppLinkRequest
import app.snapsync.format.OwnPageText
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.model.Handoff
import app.snapsync.ports.Gallery
import app.snapsync.ports.SystemUi

/**
 * **What the member's taps hand to the platform** — the share sheet, a page outside the app, this app's Settings page.
 * Putting the platform's surface on the main thread is the adapter's; nothing here acts on its answer, but a refusal is
 * logged as an error, because the member then tapped and nothing happened (`docs/architecture.md`, "Absence is never
 * silent").
 */
class SystemSurfaces(private val systemUi: SystemUi) {
    private val log = Log("SystemSurfaces")

    /** The share sheet, carrying [text], titled [title] where the platform shows one. */
    suspend fun share(text: String, title: String) = recorded("share", systemUi.share(text, title))

    /** The page [link] names, opened outside this app. */
    suspend fun open(link: AppLinkRequest) = recorded("link", systemUi.openUrl(urlOf(link)))

    /** This app's own Settings page. */
    fun openSettings() = systemUi.openSettings()

    private fun urlOf(link: AppLinkRequest): String = when (link) {
        is AppLinkRequest.StorePage -> link.store.url
        is AppLinkRequest.SitePage -> OwnPageText.url(link.page)
    }

    private suspend fun recorded(what: String, handoff: Handoff) = when (handoff) {
        Handoff.Accepted -> Unit
        is Handoff.Refused -> log.e("nothing was handed off", "what" of what, "reason" of handoff.reason)
    }
}

/**
 * **The member's asks of the photo grant**: the platform's access question, and its picker for revising a partial
 * grant's selection. Their answers are not this app's to act on — the grant and the selection arrive through their own
 * observers.
 */
class PhotoAccessAsks(private val gallery: Gallery) {
    /** The access question. */
    suspend fun ask() {
        gallery.requestAccess()
    }

    /** The picker for a partial grant's selection. */
    suspend fun widen() {
        gallery.widenSelection()
    }
}
