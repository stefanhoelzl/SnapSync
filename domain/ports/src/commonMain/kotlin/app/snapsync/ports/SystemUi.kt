package app.snapsync.ports

import app.snapsync.model.Handoff

/**
 * The platform's own UI, where this app hands something over and stops being involved — ONE external system: the
 * operating system's shell (`UIActivityViewController`, `UIApplication.openURL` and the app's Settings page on iOS;
 * a second platform offers its own chooser, intent and settings screen).
 *
 * - [share] offers text — in practice the event's invite URL (capability `join-event`) — to a chooser the user
 *   picks a destination from. It answers only whether the surface appeared: which app the user picked, and whether
 *   they sent anything, is not this app's to know.
 * - [openUrl] leaves for whichever app claims [url] — the update-required screen's store button (capability
 *   `app-update-required`), whose remedy is by definition not in this app.
 * - [openSettings] opens this app's own Settings page — the `DENIED` affordance (capability `photo-access`).
 *
 * **The two that answer, answer a [Handoff] that nothing acts on**: nothing in `UiState` depends on one. But a
 * hand-off that did NOT happen leaves the user who asked still here with nothing to show for the tap, so the answer
 * is returned and recorded rather than dropped (`docs/architecture.md`, "Absence is never silent"). They suspend
 * until the platform answers. [openSettings] answers nothing: the platform gives no answer to read.
 *
 * **Runs on the main lane** (`AppPorts.uiLane`): presenting or leaving asserts the platform's UI thread. An
 * implementation names the lane itself anyway, so the adapter is correct for any caller.
 *
 * Contracted by `SharePresenterContract` ([share]) and `LinkOpenerContract` ([openUrl]) in `:test:contracts` — two
 * contracts over this one port, named as they were recorded (`LinkOpener@IOS_DEVICE_APP.rec`).
 */
interface SystemUi {
    /** Present the platform's share surface carrying [text], and answer whether it appeared. */
    suspend fun share(text: String): Handoff

    /** Ask the platform to open [url] outside this app, and answer whether it did. */
    suspend fun openUrl(url: String): Handoff

    /** Open this app's own Settings page. */
    fun openSettings()

    companion object {
        /**
         * Hands nothing over — for compositions with no platform UI to reach (the desktop harnesses and the world).
         * [share] and [openUrl] answer [Handoff.Refused], which is exactly what is true there.
         */
        val None: SystemUi = object : SystemUi {
            override suspend fun share(text: String): Handoff = Handoff.Refused("no platform share surface")
            override suspend fun openUrl(url: String): Handoff = Handoff.Refused("no platform to open a link in")
            override fun openSettings() = Unit
        }
    }
}
