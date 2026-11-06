package app.snapsync.services.links

import app.snapsync.domain.invite.InviteLinkVerdict
import app.snapsync.format.InviteLinkText
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.model.redactEventKeys
import app.snapsync.ports.LinkDelivery

/**
 * What the app does with what the platform opened it with, read at the boundary: a link is read as an invite, fragment
 * intact — an invite, one whose key cannot be read, or none at all; an install referrer that carries an invite opens
 * that invite, and one that carries none opens nothing; anything else is logged by name — so "the platform called
 * with nothing to open" stays apart from "the platform never called". Every delivery is one line named by the
 * platform's hook, the link in it redacted: the device log keeps the link, never an encrypted event's key.
 */
class LinkReading {
    private val log = Log("LinkReading")

    /** The invite [delivery] opens, read, or `null` when it opens none. */
    fun inviteIn(delivery: LinkDelivery): InviteLinkVerdict? {
        val url = when (delivery) {
            is LinkDelivery.Link -> delivery.url
            is LinkDelivery.InstallReferrer ->
                InviteLinkText.fromInstallReferrer(delivery.referrer)?.let(InviteLinkText::format)
            is LinkDelivery.NotALink, is LinkDelivery.NoUrl -> null
        }
        val opens = if (url == null) "nothing" else redactEventKeys(url)
        log.unlabelled.i("delivered", "hook" of delivery.hook, "from" of describe(delivery), "opens" of opens)
        return url?.let(InviteLinkText::parse)
    }

    /** What the platform handed over, for the line: never a link itself, which is logged redacted. */
    private fun describe(delivery: LinkDelivery): String = when (delivery) {
        is LinkDelivery.Link -> "link"
        is LinkDelivery.InstallReferrer -> "install referrer"
        is LinkDelivery.NotALink -> "kind=${delivery.kind ?: "«none»"}"
        is LinkDelivery.NoUrl -> "url=«absent»"
    }
}
