package app.snapsync.ports

import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Inbound

/**
 * **The platform's user interface**: show a state of type [S], and hear what the person did
 * on it as an intent of type [I]. On iOS the Compose scene SwiftUI hosts; on Android the activity's.
 *
 * One external system, deciding nothing — and naming nothing of the app: the screen's vocabulary is the composition's
 * to bind ([S], [I]), so a change to what the screen shows touches no port. [show] renders a state the core reduced,
 * and every tap crosses as an [I] whose meaning is the core's. While no screen is live, [show] only keeps the latest
 * state for the next screen to render.
 *
 * An event port, registered once per adapter as the graph is composed.
 */
interface Ui<S, I> : EventPort<UiHandlers<S, I>> {
    /** Render [state]. Called on the UI lane; cheap and non-blocking — the latest state wins. */
    fun show(state: S)
}

/** What the platform's user interface tells the core. Built only by a composition. */
class UiHandlers<S, I> @CompositionOnly constructor(
    /** A person did [I] on the screen. */
    val onIntent: Inbound<I, Unit>,
    /**
     * A live screen is about to be built. Synchronous, on the main thread, and **idempotent** — a platform may build
     * the screen more than once (a SwiftUI rebuild, an Android re-creation). It answers the state the screen's first
     * frame renders.
     */
    val onLive: Inbound<Unit, S>,
)
