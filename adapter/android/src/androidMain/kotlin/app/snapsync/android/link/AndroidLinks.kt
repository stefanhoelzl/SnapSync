package app.snapsync.android.link

import android.content.Intent
import app.snapsync.model.LinkDelivery
import app.snapsync.model.PlatformEntry
import app.snapsync.ports.LinkHandlers
import app.snapsync.ports.Links
import co.touchlab.kermit.Logger

/**
 * The Android [Links] (capability `join-event`): the intent an App Link opens the activity with — at its creation (a
 * cold start, or an activity the platform recreated) and in `onNewIntent` (a running, single-top activity). The
 * activity forwards each intent whole.
 *
 * It answers the one platform question — is this a VIEW of a web URL — and hands the URI over raw, `toString()` of the
 * intent's data, so the FRAGMENT an event link carries its payload in (`/join#v=3&d=…`) reaches the core unchanged;
 * whether it is an event link, and what opening one does, is the core's. An intent that carries no URI (the launcher's)
 * is not a link and is not delivered.
 */
class AndroidLinks(private val log: Logger) : Links {
    private var handlers: LinkHandlers? = null

    override fun listen(handlers: LinkHandlers) {
        this.handlers = handlers
    }

    /**
     * The intent the activity was created with. A [restored] activity (a rotation, a process the platform brought back)
     * carries the intent it was FIRST started with, already delivered then — so it is not delivered again.
     */
    @PlatformEntry
    fun deliverCreated(intent: Intent?, restored: Boolean) {
        if (!restored) deliverIntent("onCreate", intent)
    }

    /** The intent the activity was started or re-delivered with, through [hook]'s path. */
    @PlatformEntry
    fun deliverIntent(hook: String, intent: Intent?) {
        val url = intent?.data?.toString() ?: return
        val isWebLink = intent.action == Intent.ACTION_VIEW && (url.startsWith("https://") || url.startsWith("http://"))
        deliver(LinkDelivery(hook, isWebLink, activityType = intent.action, url = url))
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
