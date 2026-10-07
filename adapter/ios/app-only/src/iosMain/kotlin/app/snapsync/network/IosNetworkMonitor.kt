@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.network

import app.snapsync.model.NetworkAccess
import app.snapsync.ports.NetworkMonitor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import platform.Network.nw_path_status_satisfiable
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_status_unsatisfied
import platform.Network.nw_path_unsatisfied_reason_cellular_denied
import platform.Network.nw_path_unsatisfied_reason_wifi_denied

/**
 * The iOS [NetworkMonitor]: one `nw_path_monitor` per collection, cancelled with it. App-only — the extension uploads
 * through PhotoKit, which waits for a network by itself, and has no screen to tell.
 *
 * WHAT IS CONTRACTED, AND WHAT CANNOT BE. `NetworkMonitorContract` runs live on the simulator app (online) and is
 * replayed from the SE2 online and in airplane mode. The BLOCKED mapping has no host: it needs the per-app Cellular
 * switch turned off on a phone with mobile data and no Wi-Fi (or a China-region device's WLAN switch), and no phone the
 * project drives has a SIM. That belief — iOS reports such a path unsatisfied with reason `cellularDenied` /
 * `wifiDenied` — is Apple's documented contract (`NWPath.UnsatisfiedReason`, iOS 14+), unmeasured here.
 */
class IosNetworkMonitor internal constructor(private val paths: NetworkPathApi) : NetworkMonitor {
    constructor() : this(SystemNetworkPathApi)

    override fun watch(): Flow<NetworkAccess> = callbackFlow {
        val stop = paths.start { trySend(networkAccessOf(it)) }
        awaitClose(stop)
    }.distinctUntilChanged()
}

/**
 * A path as an access. `satisfiable` — no path yet, but a connection attempt would bring one up (a dormant cellular
 * interface, an on-demand VPN) — is online: the app's next request is that attempt. `invalid` is a monitor with no
 * path, read as offline rather than guessed online.
 *
 * Restricted (capability `mobile-data`) is iOS's own pair: `expensive` — cellular, or Wi-Fi from a personal hotspot —
 * or `constrained` — Low Data Mode on the current network. They are the conditions a photo request's
 * `allowsExpensiveNetworkAccess` / `allowsConstrainedNetworkAccess` hold it back on, so the screen and the transfers
 * read the same thing.
 */
internal fun networkAccessOf(path: PathReading): NetworkAccess = when (path.status) {
    nw_path_status_satisfied, nw_path_status_satisfiable -> NetworkAccess.Online(
        restricted = path.expensive || path.constrained,
    )
    nw_path_status_unsatisfied -> when (path.reason) {
        nw_path_unsatisfied_reason_cellular_denied, nw_path_unsatisfied_reason_wifi_denied -> NetworkAccess.Blocked
        else -> NetworkAccess.Offline
    }
    else -> NetworkAccess.Offline
}
