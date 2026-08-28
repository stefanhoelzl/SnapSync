package app.snapsync.ports

/**
 * Hands a piece of text — in practice the event's invite URL (capability `event-link`) — to the
 * platform's share surface, so the user can pass it to whoever they are inviting through whatever
 * channel they already use (`UIActivityViewController` on iOS; a second platform would offer its own
 * chooser, which is why this port names the *need* and not the sheet).
 *
 * **Fire-and-forget, and it must stay that way.** Which app the user picked, and whether they sent
 * anything at all, is not something this app is entitled to know or act on: nothing in `UiState`
 * depends on the outcome, and an invite is valid whether or not it was ever sent. So there is no
 * completion, no result, and no suspension — the same shape [PhotoAccessRequester] has, and for the
 * same reason.
 *
 * **Runs on the main lane** (`AppPorts.uiLane`; spec `module-architecture`, "Dispatcher lanes are fixed
 * by the composition"): presenting system UI asserts the platform's UI thread, and the command that
 * calls this is declared on that lane where it is built. Implementations name the lane themselves
 * anyway, so the adapter is correct for any caller rather than only that one.
 *
 * This was `AppPorts.share: (String) -> Unit`, a function-typed field the shell filled with a
 * top-level UIKit presenter. A `(String) -> Unit` is the least informative type in the bundle — it is
 * equally the shape of pure in-core coordination — so nothing about it said that invoking it left the
 * process (spec `module-architecture`, "Ports are the I/O boundary named for the need").
 */
interface SharePresenter {
    /** Present the platform's share surface carrying [text]. */
    fun share(text: String)

    companion object {
        /**
         * Presents nothing — for compositions with no platform share surface to reach (the desktop
         * harnesses and the world, whose "device" is in-memory).
         *
         * Inert is honest here and only here: the tap is still recorded on the way in (`tap.share`,
         * `compose/`'s user-command instrumentation), so a run never loses the fact that the user asked
         * — only the surface that would have opened is missing, which is precisely what is true off
         * device.
         */
        val None: SharePresenter = object : SharePresenter {
            override fun share(text: String) = Unit
        }
    }
}
