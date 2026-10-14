package app.snapsync.data.service

import app.snapsync.data.backend.VersionObserver
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.trust.BackendAnswer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether SnapSync's service is failing this process, as the joined screen's status line tells it while photos
 * wait — a read-model cell written by the one place every
 * backend answer passes through ([observe]), so no feature reports a failure of its own and no two surfaces can
 * disagree about one outage (decision record `changes/tell-service-faults`, D4).
 *
 * [fault] is the condition the latest answer told of: a failing answer sets it, any answer from a working service —
 * a refusal included — clears it. [opening] forgets it, so an opening of the app never shows a failure from before
 * it: only once the attempt the opening triggers has failed too. Nothing durable is written — the next answer
 * re-establishes it.
 */
class ServiceConditions : VersionObserver {
    private val state = MutableStateFlow<ServiceFault?>(null)

    /** How the service is failing now, or `null` while it answers — and before anything has asked it. */
    val fault: StateFlow<ServiceFault?> = state.asStateFlow()

    override suspend fun observe(answer: BackendAnswer) {
        state.value = ServiceFault.of(answer)
    }

    /** The app is opened: a failure from before the opening is not this opening's to tell. */
    fun opening() {
        state.value = null
    }
}
