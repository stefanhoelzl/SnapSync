package app.snapsync.compose

import app.snapsync.feature.status.ForegroundWatches
import app.snapsync.feature.status.NetworkWatch
import app.snapsync.feature.status.StatusCountsPoller
import app.snapsync.feature.status.readmodel.NetworkStatusSource
import app.snapsync.ports.NetworkMonitor
import app.snapsync.services.network.NetworkReadings
import kotlinx.coroutines.CoroutineScope

/**
 * The app's network watch — the app says when it cannot reach the network — over the [NetworkMonitor] port: the
 * foreground-gated watch, the bundle the lifecycle flows start and stop it in beside the counts poll, and the
 * read-model presentation and the network's return read.

 *
 * Its own class rather than `AppCore` members for the reason [shareSetLoadFor] gives: `AppCore` is measured, and the
 * `compose` tier's `LargeClass` ceiling is what keeps it from absorbing every composition in the graph.
 */
class AppNetwork internal constructor(
    scope: CoroutineScope,
    monitor: NetworkMonitor,
    statusPoller: StatusCountsPoller,
) {
    /** Follows the network only while started — the platform's monitor runs only while the app is in front. */
    internal val watch: NetworkWatch = NetworkWatch(scope, NetworkReadings(monitor))

    /** What runs only while the screen is visible — started by the Foreground flow, stopped by the Background flow. */
    internal val watches: ForegroundWatches = ForegroundWatches(statusPoller, watch)

    /** What the member is told about the network, and when a missing one returns. */
    val status: NetworkStatusSource get() = watch
}
