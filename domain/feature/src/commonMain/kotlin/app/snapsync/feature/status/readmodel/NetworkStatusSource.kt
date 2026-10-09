package app.snapsync.feature.status.readmodel

import app.snapsync.model.NetworkAccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the app can reach the network, as the member should be told — the app says when it cannot reach the
 * network: the operating system's reading, debounced, and `ONLINE` whenever the app is not in front — nothing
 * renders it then.
 */
interface NetworkStatusSource {
    /** The access to show. A missing network appears here only once it has lasted a few seconds; its return at once. */
    val access: StateFlow<NetworkAccess>

    /**
     * One emission each time a missing network that was shown comes back while the app is in front — the moment the
     * app resumes its work. Never on a return to the foreground, where [access] resets to `ONLINE` without one.
     */
    val returned: Flow<Unit>
}
