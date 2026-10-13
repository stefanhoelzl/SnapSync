package app.snapsync.data.version

import app.snapsync.data.backend.VersionObserver
import app.snapsync.domain.trust.BackendAnswer
import app.snapsync.domain.version.VersionRefusal
import app.snapsync.domain.version.VersionVerdict
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Whether the backend is refusing this build as too old, and the version it named — a read-model cell, written by the
 * one place every backend answer passes through ([observe], called by the exchange for every route, the `/attest/…`
 * issuers included), so no service has to remember to report a refusal.
 *
 * A coordination primitive, not authority: nothing durable is written, deliberately — the answer is the backend's
 * current opinion of this build, and the next request re-establishes it. Persisting it could only leave a device stuck
 * on an update screen after the backend's minimum came back down. `null` means not currently refused, which covers
 * "served" and "has not called yet" alike.
 */
class AppVersionGate : VersionObserver {
    private val state = MutableStateFlow<VersionRefusal?>(null)
    private val log = Log("AppVersionGate")

    /** The current refusal, or `null` while this build is being served. */
    val refusal: StateFlow<VersionRefusal?> = state.asStateFlow()

    override suspend fun observe(answer: BackendAnswer) = when (val verdict = VersionVerdict.of(answer)) {
        is VersionVerdict.Refuse -> refused(verdict.refusal)
        VersionVerdict.Clear -> served()
        VersionVerdict.NoVerdict -> Unit
    }

    /**
     * Reported at **Error**, so it reaches the crash channel: this device now does nothing at all, and an operator who
     * cannot see that cannot tell a bad release from a quiet week. On the transition only, so a device left on the
     * update screen does not file one report per request.
     */
    private suspend fun refused(refusal: VersionRefusal) = when (state.getAndUpdate { refusal } == refusal) {
        true -> Unit
        false -> log.e("backend refuses this build", "minimum" of (refusal.minimumVersion ?: "unstated"))
    }

    private suspend fun served() = when (state.getAndUpdate { null } == null) {
        true -> Unit
        false -> log.i("backend serves this build again")
    }
}
