package app.snapsync.data.network

import app.snapsync.domain.network.NetworkAccess
import app.snapsync.ports.NetworkMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import app.snapsync.ports.NetworkAccess as PortAccess

/**
 * The device's network as the operating system reports it to the app — the [NetworkMonitor] port's cold flow, in the
 * domain's words: a collection runs the platform's monitor, and its cancellation stops it. It decides nothing: how long
 * a missing network must last before it is told is the domain's (`NetworkNotice`), and when the watch runs is the
 * entry points'.
 */
class NetworkReadings(private val monitor: NetworkMonitor) {
    fun watch(): Flow<NetworkAccess> = monitor.watch().map { it.access() }

    private fun PortAccess.access(): NetworkAccess = when (this) {
        is PortAccess.Online -> NetworkAccess.Online(restricted)
        PortAccess.Offline -> NetworkAccess.Offline
        PortAccess.Blocked -> NetworkAccess.Blocked
    }
}
