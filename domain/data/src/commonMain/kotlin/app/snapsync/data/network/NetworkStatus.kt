package app.snapsync.data.network

import app.snapsync.domain.network.NetworkAccess
import app.snapsync.domain.network.NetworkNotice
import app.snapsync.domain.network.NetworkReturn
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration

/**
 * What the member is told about the network — the app says when it cannot reach the network — and when a missing one
 * comes back. It holds what is told; when a reading is told is the domain's rule ([NetworkNotice]), and when the watch
 * that tells it runs is the entry points'. [hide] — the app left the front — tells `Online` with no return, so coming
 * back to the front never shows a notice that may no longer be true.
 */
class NetworkStatus {
    private val shown = MutableStateFlow(NetworkNotice.HIDDEN.shown)
    private val returns = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** The access to show. A missing network appears here only once it has lasted the grace; its return at once. */
    val access: StateFlow<NetworkAccess> = shown.asStateFlow()

    /** One emission each time a missing network that was shown comes back — the moment the app resumes its work. */
    val returned: Flow<Unit> = returns.asSharedFlow()

    /** How long [reading] must hold before it is told. */
    fun waitBefore(reading: NetworkAccess): Duration = NetworkNotice(shown.value).waitBefore(reading)

    /** Tell [reading]; answers whether it was the network's return, which is also emitted on [returned]. */
    fun publish(reading: NetworkAccess): NetworkReturn {
        val answer = NetworkNotice(shown.value).returns(reading)
        shown.value = reading
        return when (answer) {
            NetworkReturn.RETURNED -> answer.also { returns.tryEmit(Unit) }
            NetworkReturn.UNCHANGED -> answer
        }
    }

    /** Tell nothing any more: the watch stopped. */
    fun hide() {
        shown.value = NetworkNotice.HIDDEN.shown
    }
}
