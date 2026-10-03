package app.snapsync.ports

import app.snapsync.model.NetworkAccess
import kotlinx.coroutines.flow.Flow

/**
 * Whether this app can reach the network, as the operating system sees it — ONE external system, the platform's
 * network path. iOS answers with `nw_path_monitor`, Android with the default-network callback and its blocked status.
 *
 * [watch] is COLD: the platform's monitor runs only while a collection is active, and stops when it is cancelled, so
 * a caller that watches only in the foreground keeps no monitor alive in the background. Each collection's FIRST value
 * is the access at that moment — never a provisional guess a later value corrects — and every later one a change.
 */
interface NetworkMonitor : Port {
    fun watch(): Flow<NetworkAccess>
}
