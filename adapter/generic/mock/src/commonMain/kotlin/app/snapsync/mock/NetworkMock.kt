package app.snapsync.mock

import app.snapsync.model.NetworkAccess
import app.snapsync.ports.NetworkMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The device's network as the operating system reports it to this app: online, offline, or withheld from the app. Each
 * collection opens on the access at that moment and sees every change the operator makes after it.
 */
class NetworkMock(access: NetworkAccess = NetworkAccess.ONLINE) {
    internal val cell = MutableStateFlow(access)

    fun port(): NetworkMonitor = InMemoryNetworkMonitor(cell)

    val operator: NetworkOperator = NetworkOperator(this)
}

class NetworkOperator internal constructor(private val mock: NetworkMock) {
    /** What the operating system reports: switch it to take the device offline, or to withhold the network from the app. */
    var access: NetworkAccess by mock.cell::value
}

internal class InMemoryNetworkMonitor(private val access: StateFlow<NetworkAccess>) : NetworkMonitor {
    override fun watch(): Flow<NetworkAccess> = access
}
