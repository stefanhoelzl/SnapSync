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
 * **Answers a [Handoff], and nothing acts on it**, like both of those: whether the platform opened anything
 * changes nothing on screen — the screen is already showing the only thing it has to say. But a refusal
 * leaves the user on the screen whose only remedy just failed, so the answer is returned and recorded
 * rather than dropped (spec `module-architecture`, "Absence is never silent"). It suspends until the
 * platform answers.

 * **Runs on the main lane** (`AppPorts.uiLane`), because leaving for another app asserts the platform's
 * UI thread. Implementations name the lane themselves, so an adapter is correct for any caller.
 */
interface LinkOpener {
    /** Ask the platform to open [url] outside this app, and answer whether it did. */
    suspend fun open(url: String): Handoff

    companion object {
        /**
         * Opens nothing — for compositions with no platform to leave for (the desktop harnesses and the
         * world). It answers [Handoff.Refused], which is exactly what is true there: nothing was opened.
         */
        val None: LinkOpener = object : LinkOpener {
            override suspend fun open(url: String): Handoff = Handoff.Refused("no platform to open a link in")
        }
    }
}
