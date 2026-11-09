package app.snapsync.domain.invite

import kotlin.jvm.JvmInline

/** Making an invite's text: only the invite's text form (`InviteLinkText`) may. */
@RequiresOptIn("Only the invite's text form makes an invite's text.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR)
annotation class MintsInviteText

/** Reading an invite's text: only what shows or shares it (the screen, the share sheet's adapters) may. */
@RequiresOptIn("Only what shows or shares an invite reads its text.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY)
annotation class RendersInvite

/**
 * An invite as a member shares it — the text its QR code and the share sheet carry, an encrypted event's key included.
 * A secret type (`docs/patterns.md`, "Secret types"): printed, it reads masked, so a line that logged it by accident
 * would carry no key; only what shows or shares it reads its [raw] text.
 */
@JvmInline
value class InviteText @MintsInviteText constructor(@property:RendersInvite val raw: String) {
    override fun toString(): String = "InviteText(‹invite›)"
}

/**
 * The joined event's invite in the two forms the joined screen offers it (decision record `changes/seal-event-name`,
 * D5): [scanned], what its QR code encodes — a QR code is scanned, never previewed, so it carries no preview name — and
 * [shared], what the share sheet hands on: the same invite, an encrypted event's carrying the event's name readable for
 * link previews. Both open the same event in every app; nothing relies on the preview name. Printed, each reads masked
 * as an [InviteText] does.
 */
class JoinedInvite(val scanned: InviteText, val shared: InviteText)
