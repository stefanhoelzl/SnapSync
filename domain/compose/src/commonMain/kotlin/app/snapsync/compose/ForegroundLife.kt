package app.snapsync.compose

import app.snapsync.feature.creation.readmodel.ForegroundReturn
import app.snapsync.ports.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * The app's comings and goings from the foreground, over the process [clock] (capability `create-event`): each
 * return is counted with how long the app was away, and [returns] is what the status host reads, so the create
 * screen's draft can follow it. The lifecycle handlers are its only writers.
 */
class ForegroundLife internal constructor(private val clock: Clock) {
    private val latest = MutableStateFlow(ForegroundReturn.NONE)
    private val leftAt = MutableStateFlow<Instant?>(null)

    /** The latest return to the foreground. */
    val returns: StateFlow<ForegroundReturn> get() = latest

    /** The app left the foreground (a transient interruption included). */
    internal fun left() {
        leftAt.value = clock.now()
    }

    /** The app came back: one more return, and how long it was away (none on a cold launch). */
    internal fun cameBack() {
        val away = leftAt.getAndUpdate { null }?.let { clock.now() - it }
        latest.update { ForegroundReturn(it.count + 1, away) }
    }
}
