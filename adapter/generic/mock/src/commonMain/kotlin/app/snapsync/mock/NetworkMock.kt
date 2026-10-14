package app.snapsync.mock

import app.snapsync.levers.NetworkLevers
import app.snapsync.levers.NetworkState
import app.snapsync.model.NetworkAccess
import app.snapsync.ports.NetworkMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The device's network as the operating system reports it to this app: online (restricted or not), offline, or withheld
 * from the app. Each
 * collection opens on the access at that moment and sees every change the operator makes after it.
 */
class NetworkMock(access: NetworkAccess = NetworkAccess.Online(restricted = false)) {
    internal val cell = MutableStateFlow(access)

    /**
     * What the operating system reports: switch it to take the device offline, to withhold the network from the app, or
     * to put it on a restricted network (mobile data, a hotspot, Low Data Mode). In-process plumbing; the channel's is
     * the [operator].
     */
    var access: NetworkAccess by cell::value

    fun port(): NetworkMonitor = InMemoryNetworkMonitor(cell)

    val operator: NetworkOperator = NetworkOperator(this)
}

class NetworkOperator internal constructor(private val mock: NetworkMock) : NetworkLevers {
    override suspend fun set(access: NetworkState) {
        mock.access = when (access) {
            NetworkState.ONLINE -> NetworkAccess.Online(restricted = false)
            NetworkState.RESTRICTED -> NetworkAccess.Online(restricted = true)
            NetworkState.OFFLINE -> NetworkAccess.Offline
            NetworkState.BLOCKED -> NetworkAccess.Blocked
        }
    }

    override suspend fun access(): NetworkState = when (val access = mock.access) {
        is NetworkAccess.Online -> if (access.restricted) NetworkState.RESTRICTED else NetworkState.ONLINE
        NetworkAccess.Offline -> NetworkState.OFFLINE
        NetworkAccess.Blocked -> NetworkState.BLOCKED
    }
}

internal class InMemoryNetworkMonitor(private val access: StateFlow<NetworkAccess>) : NetworkMonitor {
    override fun watch(): Flow<NetworkAccess> = access
}
