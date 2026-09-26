package app.snapsync.ports

import app.snapsync.model.UiIntent
import app.snapsync.model.UiState

/**
 * **The platform's user interface** (capability `sync-status`): show the status screen's state, and hear what the
 * person did on it. On iOS the Compose scene SwiftUI hosts; on Android the activity's.
 *
 * One external system, deciding nothing: [show] renders a state the core reduced, and every tap crosses as a
 * [UiIntent] whose meaning is the status container's. While no screen is live, [show] only keeps the latest state
 * for the next screen to render.
 *
 * An event port, registered once per adapter as the graph is composed.
 */
interface Ui : Listenable<UiHandlers> {
    /** Render [state]. Called on the UI lane; cheap and non-blocking — the latest state wins. */
    fun show(state: UiState)
}

/** What the platform's user interface tells the core. Built only by a composition. */
class UiHandlers(
    /** A person did [UiIntent] on the screen. */
    val onIntent: (UiIntent) -> Unit,
    /**
     * A live screen is about to be built. Synchronous, on the main thread, and **idempotent** — a platform may build
     * the screen more than once (a SwiftUI rebuild, an Android re-creation): it assembles the status host and shows
     * its current state, so the screen's first frame already renders it.
     */
    val onLive: () -> Unit,
)
