package app.snapsync.domain.invite

import app.snapsync.domain.event.EventId

/**
 * An invite to [event]: what a link, a scanned code or an install's referrer opened. [key] is an encrypted event's key,
 * `null` for a plain event. A secret type (`docs/patterns.md`, "Secret types"): only the key is masked, so a logged
 * invite still names its event.
 */
data class InviteLink(val event: EventId, val key: InviteKey?)

/** An encrypted event's key as an invite carries it: never rendered. */
class InviteKey(val text: String) {
    override fun equals(other: Any?): Boolean = other is InviteKey && other.text == text

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = "InviteKey(***)"
}

/** What a link opened the app with. */
sealed interface InviteLinkVerdict {
    class Invite(val link: InviteLink) : InviteLinkVerdict

    /**
     * An invite to [event] whose key cannot be read — cut short in sharing: it opens the incomplete-invite screen, as a
     * key that does not open its event does.
     */
    class KeyUnreadable(val event: EventId) : InviteLinkVerdict

    /** Not an invite at all. */
    data object NotAnInvite : InviteLinkVerdict
}
