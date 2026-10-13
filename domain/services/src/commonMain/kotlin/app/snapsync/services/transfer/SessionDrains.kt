package app.snapsync.services.transfer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * A transfer session's drain reports, as its adapter counts them: what a relaunch for the session's events waits for.
 * The adapter numbers each report and tells each relaunch how many came before it, so a relaunch waits for the first
 * report after its own arrival — however the two deliveries race to the core.
 */
class SessionDrains {
    private val reported = MutableStateFlow(0)

    /** The session's [drains]th drain report arrived. */
    fun reported(drains: Int) = reported.update { maxOf(it, drains) }

    /** Returns once a drain report after the [before]th has arrived. */
    suspend fun awaitAfter(before: Int) {
        reported.first { it > before }
    }
}
