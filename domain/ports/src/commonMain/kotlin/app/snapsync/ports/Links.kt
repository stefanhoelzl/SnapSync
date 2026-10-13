package app.snapsync.ports

import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Inbound

/**
 * **The links the platform opens this app with**: a Universal Link delivered cold at
 * launch or warm to a running app, and an opened URL. On iOS the scene delegate's user activities and SwiftUI's
 * `onOpenURL`; on Android the launching intent.
 *
 * One external system, deciding nothing: it hands every delivery over raw ([LinkDelivery]), and whether it is an
 * event link — and what opening one does — is the core's. An event port, registered once per adapter as the graph is
 * composed, so a link that launched the app finds its handler.
 */
interface Links : EventPort<LinkHandlers>

/**
 * What the platform opened the app with, as the platform delivered it. Each names the [hook] that delivered it — the
 * platform's own entry (a cold Universal Link, a warm continuation, an opened URL, an activity's intent) — for the
 * log, so a device log records which path ran.
 */
sealed interface LinkDelivery {
    val hook: String

    /** A link, complete: the fragment an invite carries its payload in is intact. */
    data class Link(override val hook: String, val url: String) : LinkDelivery

    /** The referrer this installation came with, raw — at most once per installation, on a foreground start only. */
    data class InstallReferrer(override val hook: String, val referrer: String) : LinkDelivery

    /** The platform opened the app with something that is not a link; [kind] names it as the platform does. */
    data class NotALink(override val hook: String, val kind: String?) : LinkDelivery

    /** A link delivery that carried no URL. Not expected; delivered so it never passes for "never called". */
    data class NoUrl(override val hook: String) : LinkDelivery
}

/** What the platform's link deliveries tell the core. Built only by a composition. */
class LinkHandlers @CompositionOnly constructor(
    /** The app was opened with [LinkDelivery]. */
    val onLink: Inbound<LinkDelivery, Unit>,
)
