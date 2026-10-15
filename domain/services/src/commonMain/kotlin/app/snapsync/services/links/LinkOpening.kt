package app.snapsync.services.links

import app.snapsync.format.InviteLinkText
import app.snapsync.model.redactEventKeys
import app.snapsync.ports.LinkDelivery
import co.touchlab.kermit.Logger

/**
 * What the app does with what the platform opened it with: a link opens the join gate on it, fragment intact; an install
 * referrer that carries an invite opens that invite as a link, and one that carries none opens nothing; anything else
 * is logged by name — so "the platform called with nothing to open" stays apart from "the platform never called".
 * Every delivery is one line named by the platform's hook.
 */
class LinkOpening(private val log: Logger) {

    /** The link [delivery] opens, or `null` when it opens none. */
    fun urlIn(delivery: LinkDelivery): String? {
        val url = when (delivery) {
            is LinkDelivery.Link -> delivery.url
            is LinkDelivery.InstallReferrer ->
                InviteLinkText.fromInstallReferrer(delivery.referrer)?.let(InviteLinkText::format)
            is LinkDelivery.NotALink, is LinkDelivery.NoUrl -> null
        }
        // The device log keeps the link, never an encrypted event's key in it.
        val opens = if (url == null) "opens nothing" else "opens ${redactEventKeys(url)}"
        log.i { "${delivery.hook}(${delivery.describe()}): $opens" }
        return url
    }
}

/** What the platform handed over, for the line: never a link itself, which is logged redacted. */
private fun LinkDelivery.describe(): String = when (this) {
    is LinkDelivery.Link -> "link"
    is LinkDelivery.InstallReferrer -> "install referrer"
    is LinkDelivery.NotALink -> "kind=${kind ?: "«none»"}"
    is LinkDelivery.NoUrl -> "url=«absent»"
}
