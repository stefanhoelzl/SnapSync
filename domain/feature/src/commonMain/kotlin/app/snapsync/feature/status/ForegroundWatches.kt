package app.snapsync.feature.status

/**
 * What follows the app only while its screen is visible: the status-counts poll and the
 * network watch. One value so the `Foreground` flow starts them together and the `Background` flow stops them
 * together — a watch the one starts and the other forgets would run through every suspension for a screen nobody sees.
 */
class ForegroundWatches(
    val statusPoller: StatusCountsPoller,
    val network: NetworkWatch,
) {
    fun start() {
        statusPoller.start()
        network.start()
    }

    fun stop() {
        statusPoller.stop()
        network.stop()
    }
}
