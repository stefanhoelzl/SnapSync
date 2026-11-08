package app.snapsync.query.network

import app.snapsync.data.network.NetworkStatus
import app.snapsync.domain.network.NetworkAccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the app can reach the network, as the member should be told: the operating system's reading, told only once a
 * missing network has lasted a few seconds, and `Online` whenever the app is not in front — nothing renders it then.
 */
interface NetworkStatusQuery {
    /** The access to show. */
    val access: StateFlow<NetworkAccess>

    /**
     * One emission each time a missing network that was shown comes back while the app is in front — the moment the app
     * resumes its work. Never on a return to the foreground, where [access] resets to `Online` without one.
     */
    val returned: Flow<Unit>
}

/** [NetworkStatusQuery] over what the app's network watch tells. */
class NetworkStatusLookup(status: NetworkStatus) : NetworkStatusQuery {
    override val access: StateFlow<NetworkAccess> = status.access
    override val returned: Flow<Unit> = status.returned
}
