package app.snapsync.ports

import app.snapsync.model.LinkDelivery

/**
 * **The links the platform opens this app with** (capability `join-event`): a Universal Link delivered cold at
 * launch or warm to a running app, and an opened URL. On iOS the scene delegate's user activities and SwiftUI's
 * `onOpenURL`; on Android the launching intent.
 *
 * One external system, deciding nothing: it hands every delivery over raw ([LinkDelivery]), and whether it is an
 * event link — and what opening one does — is the core's. An event port, registered once per adapter as the graph is
 * composed, so a link that launched the app finds its handler.
 */
interface Links : Listenable<LinkHandlers>

/** What the platform's link deliveries tell the core. Built only by a composition. */
class LinkHandlers(
    /** A link arrived, raw. The handler assembles the status host first: a link is a person opening the app. */
    val onLink: (LinkDelivery) -> Unit,
)
