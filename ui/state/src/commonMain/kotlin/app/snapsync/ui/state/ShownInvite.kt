package app.snapsync.ui.state

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Making the invite the screen shows: only the reduction that derived it from the event's invite may. */
@RequiresOptIn("Only the reduction makes the invite the screen shows.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR)
annotation class MintsShownInvite

/** Reading the invite the screen shows: only what draws its QR code may. */
@RequiresOptIn("Only the QR code reads the invite's text.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY)
annotation class ShowsInvite

/**
 * The invite the joined screen shows as a QR code — an encrypted event's key included. The screen's own secret type (it
 * names no domain type): printed, it reads masked, so a logged screen state would carry no key; only the QR code reads
 * its [raw] text.
 */
@Serializable
@JvmInline
value class ShownInvite @MintsShownInvite constructor(@property:ShowsInvite val raw: String) {
    override fun toString(): String = "ShownInvite(‹invite›)"
}
