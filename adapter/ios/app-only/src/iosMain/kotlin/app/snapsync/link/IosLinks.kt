package app.snapsync.link

import app.snapsync.model.LinkDelivery
import app.snapsync.model.PlatformEntry
import app.snapsync.ports.LinkHandlers
import app.snapsync.ports.Links
import co.touchlab.kermit.Logger
import platform.Foundation.NSUserActivity

/**
 * The iOS [Links] (capability `join-event`): both halves of Universal-Link delivery — the scene delegate's cold
 * `willConnectTo` activities and its warm `continue` — and SwiftUI's `onOpenURL`. The Swift shell forwards each whole.
 *
 * It answers the one platform question — is this a browsing-web activity ([isWebLinkActivity]) — and hands every
 * delivery over raw, named by the [LinkDelivery.hook] that brought it, so a device log records which of UIKit's paths
 * ran. Whether it is an event link, and what opening one does, is the core's.
 */
class IosLinks(private val log: Logger) : Links {
    private var handlers: LinkHandlers? = null

    override fun listen(handlers: LinkHandlers) {
        this.handlers = handlers
    }

    /** A restored or continued user activity, through [hook]'s path (a cold launch's, or a running app's). */
    @PlatformEntry
    fun deliverUserActivity(hook: String, activity: NSUserActivity) {
        val type = activity.activityType
        deliver(LinkDelivery(hook, isWebLinkActivity(type), type, activity.webpageURL?.absoluteString))
    }

    /** An opened URL, through [hook]'s path (SwiftUI's `onOpenURL`): a link by construction, carried whole. */
    @PlatformEntry
    fun deliverOpenUrl(hook: String, url: String) {
        deliver(LinkDelivery(hook, isWebLink = true, activityType = null, url = url))
    }

    private fun deliver(delivery: LinkDelivery) {
        val registered = handlers
        if (registered == null) {
            log.e { "a link arrived before the Links port was listened to: ${delivery.hook}" }
        } else {
            registered.onLink(delivery)
        }
    }
}
