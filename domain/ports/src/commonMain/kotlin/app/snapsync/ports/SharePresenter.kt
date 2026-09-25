package app.snapsync.ports

import app.snapsync.model.Handoff

/**
 * Hands a piece of text — in practice the event's invite URL (capability `join-event`) — to the
 * platform's share surface, so the user can pass it to whoever they are inviting through whatever
 * channel they already use (`UIActivityViewController` on iOS; a second platform would offer its own
 * chooser, which is why this port names the *need* and not the sheet).
 *
 * **Answers only whether the surface appeared.** Which app the user picked, and whether they sent
 * anything at all, is not something this app is entitled to know or act on: an invite is valid whether
 * or not it was ever sent. Whether the sheet was PRESENTED is different — if it was not, the user tapped
 * and nothing happened — so that much is answered as a [Handoff] and recorded, and nothing acts on it.
 * It suspends until the surface is on screen or known not to be.

 * **Runs on the main lane** (`AppPorts.uiLane`; `docs/architecture.md`, "Dispatcher lanes are fixed
 * by the composition"): presenting system UI asserts the platform's UI thread, and the command that
 * calls this is declared on that lane where it is built. Implementations name the lane themselves
 * anyway, so the adapter is correct for any caller rather than only that one.
 *
 * This was `AppPorts.share: (String) -> Unit`, a function-typed field the shell filled with a
 * top-level UIKit presenter. A `(String) -> Unit` is the least informative type in the bundle — it is
 * equally the shape of pure in-core coordination — so nothing about it said that invoking it left the
 * process (`docs/architecture.md`, "Ports are the I/O boundary named for the need").
 */
interface SharePresenter {
    /** Present the platform's share surface carrying [text], and answer whether it appeared. */
    suspend fun share(text: String): Handoff

    companion object {
        /**
         * Presents nothing — for compositions with no platform share surface to reach (the desktop
         * harnesses and the world, whose "device" is in-memory).
         *
         * It answers [Handoff.Refused], which is precisely what is true off device: the tap is recorded
         * (`tap.share`, `compose/`'s user-command instrumentation) and no surface opened.
         */
        val None: SharePresenter = object : SharePresenter {
            override suspend fun share(text: String): Handoff = Handoff.Refused("no platform share surface")
        }
    }
}
