package app.snapsync.services.network

import app.snapsync.model.NetworkAccess
import app.snapsync.ports.NetworkMonitor
import kotlinx.coroutines.flow.Flow

/**
 * The device's network as the operating system reports it to the app (capability `sync-status`) — the
 * [NetworkMonitor] port, for the feature that decides what the member is told and may not see a port
 * (`docs/architecture.md`, "a feature sees services, never ports").
 *
 * It decides nothing: how long a missing network must last before it is shown, and when the watch runs, are
 * `feature/status`'s `NetworkWatch`. [watch] is the port's cold flow unchanged — a collection runs the platform's
 * monitor, and its cancellation stops it.
 */
class NetworkReadings(private val monitor: NetworkMonitor) {
    fun watch(): Flow<NetworkAccess> = monitor.watch()
}
