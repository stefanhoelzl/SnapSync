package app.snapsync.link

import app.snapsync.model.BeforeListen
import app.snapsync.model.HandlerSlot
import app.snapsync.model.PlatformEntry
import app.snapsync.ports.LinkDelivery
import app.snapsync.ports.LinkHandlers
import app.snapsync.ports.Links
import co.touchlab.kermit.Logger
import platform.Foundation.NSUserActivity

/**
 * The iOS [Links]: both halves of Universal-Link delivery — the scene delegate's cold
 * `willConnectTo` activities and its warm `continue` — and SwiftUI's `onOpenURL`. The Swift shell forwards each whole.
 *
 * It answers the one platform question — is this a browsing-web activity ([isWebLinkActivity]) — and hands every
 * delivery over as what it is: a link, a browsing-web activity with no URL, or another activity named by its type —
 * each named by the [LinkDelivery.hook] that brought it, so a device log records which of UIKit's paths ran. Whether
 * a link is an event link, and what opening one does, is the core's.
 */
class IosLinks(private val log: Logger) : Links {
    private val handlers = HandlerSlot<LinkHandlers>("Links", BeforeListen.Logged(log))

    override fun listen(handlers: LinkHandlers) = this.handlers.set(handlers)

    /** A restored or continued user activity, through [hook]'s path (a cold launch's, or a running app's). */
    @PlatformEntry
    fun deliverUserActivity(hook: String, activity: NSUserActivity) {
        val type = activity.activityType
        val url = activity.webpageURL?.absoluteString
        deliver(
            when {
                !isWebLinkActivity(type) -> LinkDelivery.NotALink(hook, type)
                url == null -> LinkDelivery.NoUrl(hook)
                else -> LinkDelivery.Link(hook, url)
            },
        )
    }

    /** An opened URL, through [hook]'s path (SwiftUI's `onOpenURL`): a link by construction, carried whole. */
    @PlatformEntry
    fun deliverOpenUrl(hook: String, url: String) {
        deliver(LinkDelivery.Link(hook, url))
    }

    private fun deliver(delivery: LinkDelivery) {
        handlers.orNull("a link through ${delivery.hook}")?.onLink(delivery)
    }
}
