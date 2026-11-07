package app.snapsync.data.links

import app.snapsync.domain.invite.InviteLinkVerdict
import app.snapsync.format.InviteLinkText
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.ports.LinkDelivery

/**
 * What the app does with what the platform opened it with, read at the boundary: a link is read as an invite, fragment
 * intact — an invite, one whose key cannot be read, or none at all; an install referrer that carries an invite opens
 * that invite, and one that carries none opens nothing; anything else is logged by name — so "the platform called
 * with nothing to open" stays apart from "the platform never called".
 *
 * Every delivery is one line named by the platform's hook, logging what it READ — the invite, its key masked by its
 * type; an invite whose key cannot be read, by its event; and how long a link was — never the link's
 * text, which may carry an encrypted event's key, whole or cut short.
 */
class LinkReading {
    private val log = Log("LinkReading").unlabelled

    /** The invite [delivery] opens, read, or `null` when it opens none. */
    fun inviteIn(delivery: LinkDelivery): InviteLinkVerdict? {
        val verdict = when (delivery) {
            is LinkDelivery.Link -> InviteLinkText.parse(delivery.url)
            is LinkDelivery.InstallReferrer ->
                InviteLinkText.fromInstallReferrer(delivery.referrer)?.let(InviteLinkVerdict::Invite)
            is LinkDelivery.NotALink, is LinkDelivery.NoUrl -> null
        }
        log.i("delivered", "hook" of delivery.hook, "from" of describe(delivery), "opens" of opened(verdict))
        return verdict
    }

    /** What was read, for the line: never the link itself. */
    private fun opened(verdict: InviteLinkVerdict?): String = when (verdict) {
        null -> "nothing"
        is InviteLinkVerdict.Invite -> "${verdict.link}"
        is InviteLinkVerdict.KeyUnreadable -> "an invite to ${verdict.event} whose key cannot be read"
        InviteLinkVerdict.NotAnInvite -> "no invite"
    }

    /** What the platform handed over, for the line. */
    private fun describe(delivery: LinkDelivery): String = when (delivery) {
        is LinkDelivery.Link -> "link of ${delivery.url.length} characters"
        is LinkDelivery.InstallReferrer -> "install referrer"
        is LinkDelivery.NotALink -> "kind=${delivery.kind ?: "«none»"}"
        is LinkDelivery.NoUrl -> "url=«absent»"
    }
}
