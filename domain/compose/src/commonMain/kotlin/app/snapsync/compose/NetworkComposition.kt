package app.snapsync.compose

import app.snapsync.data.network.NetworkReadings
import app.snapsync.data.network.NetworkStatus
import app.snapsync.entrypoints.ForegroundNetwork
import app.snapsync.ports.NetworkMonitor
import app.snapsync.query.network.NetworkStatusLookup
import app.snapsync.query.network.NetworkStatusQuery
import app.snapsync.scheduling.GatedWatch
import app.snapsync.scheduling.Runtime

/**
 * The app's network watch — the app says when it cannot reach the network — over the [NetworkMonitor] port: what it
 * tells, the foreground's watch the lifecycle's entry points start and stop, and the screen's read of it.
 *
 * Its own class rather than `AppCore` members for the reason [shareSetLoadFor] gives: `AppCore` is measured, and the
 * `compose` tier's `LargeClass` ceiling is what keeps it from absorbing every composition in the graph.
 */
class AppNetwork internal constructor(runtime: Runtime, monitor: NetworkMonitor) {
    /** What the member is told about the network, and when a missing one returns. */
    internal val status: NetworkStatus = NetworkStatus()

    /** Followed only while the app is in front — the platform's monitor runs only then. */
    internal val foreground: ForegroundNetwork =
        ForegroundNetwork(GatedWatch(runtime), NetworkReadings(monitor), status)

    /** The screen's read of [status]. */
    val query: NetworkStatusQuery = NetworkStatusLookup(status)
}
