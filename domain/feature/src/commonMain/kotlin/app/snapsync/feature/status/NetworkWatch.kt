package app.snapsync.feature.status

import app.snapsync.feature.status.readmodel.NetworkStatusSource
import app.snapsync.model.NetworkAccess
import app.snapsync.services.network.NetworkReadings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The **foreground-gated network watch** (capability `sync-status`, "The app says when it cannot reach the network"):
 * while the app is in front — and only then — follow the operating system's network reading and decide what the member
 * is told.
 *
 * **Slow to warn, instant to clear** (decision record `changes/tell-when-offline`, D2). A missing network is published
 * only once it has held for [grace]: a lift, a Wi-Fi handover or a tunnel would otherwise flash a notice and take Create
 * away for a second. Its return is published at once, and so is a change of cause while a notice already shows — the
 * cause changed, not the presence. The grace is chosen for human perception, not derived, like the counts poller's
 * cadence.
 *
 * **Started and stopped by the lifecycle flows**, beside the counts poller ([ForegroundWatches]): [start] collects
 * [readings] — the port's cold flow, through its service — and [stop] cancels the collection, which stops the
 * platform's monitor. [stop] resets [access] to `ONLINE`, so returning to the foreground never shows a notice that may
 * no longer be true; the next collection's first reading replaces it, after the same grace.
 *
 * [returned] fires on a published return from a missing network to `ONLINE` — never on [stop]'s reset — and is what
 * resumes the app's work and reloads a join whose details could not load (`changes/tell-when-offline`, D4).
 */
class NetworkWatch(
    private val scope: CoroutineScope,
    private val readings: NetworkReadings,
    private val grace: Duration = DEFAULT_GRACE,
) : NetworkStatusSource {

    private val state = MutableStateFlow<NetworkAccess>(NetworkAccess.Online(restricted = false))
    private val returns = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var job: Job? = null

    override val access: StateFlow<NetworkAccess> = state.asStateFlow()
    override val returned: Flow<Unit> = returns.asSharedFlow()

    /** Begin watching; a no-op while a previous [start]'s watch is still live. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // Latest: a newer reading cancels a missing network's pending grace, so a return inside it publishes nothing.
            readings.watch().collectLatest { reading ->
                if (reading !is NetworkAccess.Online && state.value is NetworkAccess.Online) delay(grace)
                publish(reading)
            }
        }
    }

    /** Stop watching (backgrounding: nothing renders the notice) and forget what was shown. Idempotent. */
    fun stop() {
        job?.cancel()
        job = null
        state.value = NetworkAccess.Online(restricted = false)
    }

    private fun publish(reading: NetworkAccess) {
        val was = state.value
        state.value = reading
        if (was !is NetworkAccess.Online && reading is NetworkAccess.Online) returns.tryEmit(Unit)
    }

    companion object {
        /** How long a missing network must last before the member is told. Chosen, not derived (see the class KDoc). */
        val DEFAULT_GRACE: Duration = 5.seconds
    }
}
