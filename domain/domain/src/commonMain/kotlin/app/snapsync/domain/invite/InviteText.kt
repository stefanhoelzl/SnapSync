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
