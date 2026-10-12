package app.snapsync.android.link

import android.content.Intent
import app.snapsync.model.BeforeListen
import app.snapsync.model.EntryScope
import app.snapsync.model.HandlerSlot
import app.snapsync.model.PlatformEntry
import app.snapsync.model.invocation
import app.snapsync.ports.LinkDelivery
import app.snapsync.ports.LinkHandlers
import app.snapsync.ports.Links
import co.touchlab.kermit.Logger

/**
 * The Android [Links]: the intent an App Link opens the activity with — at its creation (a
 * cold start, or an activity the platform recreated) and in `onNewIntent` (a running, single-top activity). The
 * activity forwards each intent whole.
 *
 * It answers the one platform question — is this a VIEW of a web URL — and hands the URI over raw, `toString()` of the
 * intent's data, so the FRAGMENT an event link carries its payload in (`/join#v=3&d=…`) reaches the core unchanged;
 * whether it is an event link, and what opening one does, is the core's. Every other intent is delivered as what it
 * is — a VIEW carrying no URI, or another intent named by its action (the launcher's `MAIN`) — so a device log tells
 * "started with no link" from "never started".
 */
class AndroidLinks(private val log: Logger) : Links {
    private val handlers = HandlerSlot<LinkHandlers>("Links", BeforeListen.Logged(log))

    override fun listen(handlers: LinkHandlers) = this.handlers.set(handlers)

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
    fun deliverIntent(hook: String, intent: Intent?) =
        log.invocation(EntryScope.None, hook, params = "action=${intent?.action} data=${intent?.data != null}") {
            deliver(deliveryOf(hook, intent))
        }

    /** The referrer a Google Play install carried ([AndroidInstallReferrer]), raw: whether it is an invite is the core's. */
    @PlatformEntry
    fun deliverInstallReferrer(
        referrer: String,
    ) = deliver(LinkDelivery.InstallReferrer(INSTALL_REFERRER_HOOK, referrer))

    private fun deliver(delivery: LinkDelivery) {
        handlers.orNull("a link through ${delivery.hook}")?.onLink(delivery)
    }
}

/** The hook a Play-carried invite is delivered on — named in the log only. */
private const val INSTALL_REFERRER_HOOK = "installReferrer"

/** What [intent] opened the app with: a VIEW of a web URL is a link; a VIEW with no URI carries none; the rest is not one. */
private fun deliveryOf(hook: String, intent: Intent?): LinkDelivery {
    val url = intent?.data?.toString()
    val view = intent?.action == Intent.ACTION_VIEW
    return when {
        view && url == null -> LinkDelivery.NoUrl(hook)
        view && url != null && (url.startsWith("https://") || url.startsWith("http://")) -> LinkDelivery.Link(hook, url)
        else -> LinkDelivery.NotALink(hook, intent?.action)
    }
}
