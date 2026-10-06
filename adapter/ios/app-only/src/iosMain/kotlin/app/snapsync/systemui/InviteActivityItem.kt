package app.snapsync.systemui

import app.snapsync.objc.objcBoundary
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.LinkPresentation.LPLinkMetadata
import platform.UIKit.UIActivityItemSourceProtocol
import platform.UIKit.UIActivityViewController
import platform.darwin.NSObject

/**
 * The share sheet's one item (capability `manage-membership`, "The joined screen offers the invite"): the invite as a
 * URL, with link metadata titled [title] — the event's name — so the sheet's header names the event instead of
 * fetching the page for a preview of its own.
 *
 * A URL rather than a string, so the receiving app gets a link; and the metadata is the app's own, so the header is
 * right before the page is ever fetched, offline included. An item whose [url] does not parse falls back to the text,
 * exactly what was shared before this item existed.
 */
internal class InviteActivityItem(private val text: String, private val title: String) :
    NSObject(),
    UIActivityItemSourceProtocol {

    private val url: NSURL? = NSURL.URLWithString(text)
    private val log = Logger.withTag("shareSheet")

    // UIKit calls each of these: every body is contained, and a throw answers the plain text (or no metadata).
    override fun activityViewControllerPlaceholderItem(activityViewController: UIActivityViewController): Any =
        objcBoundary(log, "share.placeholderItem", fallback = text) { url ?: text }

    override fun activityViewController(activityViewController: UIActivityViewController, itemForActivityType: String?): Any? =
        objcBoundary(log, "share.item", fallback = text) { url ?: text }

    // The UIKit klib declares this return as a FORWARD declaration of LPLinkMetadata (UIKit does not import
    // LinkPresentation), so the real class is cast to it: one Objective-C class, two Kotlin names.
    @OptIn(ExperimentalForeignApi::class)
    @Suppress("CAST_NEVER_SUCCEEDS")
    override fun activityViewControllerLinkMetadata(
        activityViewController: UIActivityViewController,
    ): objcnames.classes.LPLinkMetadata? = objcBoundary(log, "share.linkMetadata", fallback = null) {
        val metadata = LPLinkMetadata()
        metadata.title = title
        metadata.originalURL = url
        metadata.URL = url
        metadata as objcnames.classes.LPLinkMetadata
    }
}
