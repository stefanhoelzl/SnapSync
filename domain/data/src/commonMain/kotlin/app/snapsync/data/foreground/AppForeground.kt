package app.snapsync.data.foreground

import app.snapsync.domain.foreground.ForegroundReturn
import app.snapsync.domain.foreground.InFront
import app.snapsync.ports.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlin.time.Instant

/**
 * The app's comings and goings from the foreground, over the process [clock]: whether it is in front now — what is
 * worth reading only while a person can see it follows it ([whileInFront]) — and each return, counted with how long the
 * app was away, which the create screen's draft follows. The lifecycle's entry points are its only writers.
 */
class AppForeground(private val clock: Clock) {
    private val position = MutableStateFlow(InFront.AWAY)
    private val latest = MutableStateFlow(ForegroundReturn.NONE)
    private val leftAt = MutableStateFlow<Instant?>(null)

    /** Whether the app is in front. */
    val inFront: StateFlow<InFront> = position.asStateFlow()

    /** The latest return to the foreground. */
    val returns: StateFlow<ForegroundReturn> = latest.asStateFlow()

    /** The app came back: one more return, and how long it was away (none on a cold launch). */
    fun entered() {
        val left = leftAt.getAndUpdate { null }
        latest.update { it.next(left, clock.now()) }
        position.value = InFront.IN_FRONT
    }

    /** The app left the foreground (a transient interruption included). */
    fun left() {
        position.value = InFront.AWAY
        leftAt.value = clock.now()
    }

    /**
     * [flow], collected while the app is in front and not at all while it is away: each arrival in front starts it
     * afresh, and leaving cancels it — so what it reads is read only while a person can see it.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun <T> whileInFront(flow: Flow<T>): Flow<T> = position.flatMapLatest { now ->
        when (now) {
            InFront.IN_FRONT -> flow
            InFront.AWAY -> emptyFlow()
        }
    }
}
