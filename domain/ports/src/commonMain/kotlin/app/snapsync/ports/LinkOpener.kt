package app.snapsync.ports

/**
 * Hands a URL to the platform to open **outside this app** (`UIApplication.openURL` on iOS; a second
 * platform would offer its own).
 *
 * Its one caller is the update-required screen's store button (capability `min-app-version`), whose
 * remedy is by definition not in this app. It is a port rather than a lambda for the reason
 * [SharePresenter] is: leaving the process is exactly the fact a `(String) -> Unit` field would fail to
 * state (spec `module-architecture`, "Ports are the I/O boundary named for the need").
 *
 * Distinct from [SharePresenter], which offers text to a CHOOSER the user picks a destination from, and
 * from [PhotoAccessRequester.openSettings], which opens this app's own Settings page and takes no URL.
 * Three different needs; three names.
 *
 * **Fire-and-forget**, like both of those: whether the platform actually opened anything is not
 * something this app can act on — the screen is already showing the only thing it has to say — so
 * there is no result and no suspension.
 *
 * **Runs on the main lane** (`AppPorts.uiLane`), because leaving for another app asserts the platform's
 * UI thread. Implementations name the lane themselves, so an adapter is correct for any caller.
 */
interface LinkOpener {
    /** Ask the platform to open [url] outside this app. */
    fun open(url: String)

    companion object {
        /**
         * Opens nothing — for compositions with no platform to leave for (the desktop harnesses and the
         * world). Inert is honest here for the same reason it is on [SharePresenter.None]: the tap is
         * still recorded on the way in, so a run never loses the fact that the user asked.
         */
        val None: LinkOpener = object : LinkOpener {
            override fun open(url: String) = Unit
        }
    }
}
