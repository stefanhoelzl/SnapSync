package app.snapsync.services.links

import app.snapsync.model.EntryScope
import app.snapsync.model.inviteLinkFromInstallReferrer
import app.snapsync.model.invocation
import app.snapsync.model.redactEventKeys
import app.snapsync.ports.LinkDelivery
import co.touchlab.kermit.Logger

/**
 * What the app does with what the platform opened it with: a link opens the join gate on it, fragment intact; an install
 * referrer that carries an invite opens that invite as a link, and one that carries none opens nothing; anything else
 * is logged by name — so "the platform called with nothing to open" stays apart from "the platform never called".
 * Every delivery is one entry line named by the platform's hook.
 */
class LinkOpening(private val log: Logger, private val entryScope: EntryScope) {

    /** Hand [delivery]'s link, if it opens one, to [open]; answers what became of it, for the entry line. */
    fun deliver(delivery: LinkDelivery, open: (url: String) -> Unit): String =
        log.invocation(entryScope, delivery.hook, params = delivery.describe(), result = { it }) {
            when (delivery) {
                is LinkDelivery.Link -> opened(delivery.url, open)
                is LinkDelivery.InstallReferrer ->
                    inviteLinkFromInstallReferrer(delivery.referrer)?.let { opened(it, open) } ?: "no invite"
                is LinkDelivery.NotALink -> "not a link"
                is LinkDelivery.NoUrl -> "no url"
            }
        }

    private fun opened(url: String, open: (String) -> Unit): String {
        // The device log keeps the link, never an encrypted event's key in it.
        log.invocation(entryScope, "onOpenUrl", params = "url=${redactEventKeys(url)}") { open(url) }
        return "opened"
    }
}

/** What the platform handed over, for the entry line: never a link itself, which `onOpenUrl` logs redacted. */
private fun LinkDelivery.describe(): String = when (this) {
    is LinkDelivery.Link -> "link"
    is LinkDelivery.InstallReferrer -> "install referrer"
    is LinkDelivery.NotALink -> "kind=${kind ?: "«none»"}"
    is LinkDelivery.NoUrl -> "url=«absent»"
}
