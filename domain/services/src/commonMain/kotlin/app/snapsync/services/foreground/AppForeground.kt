@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.services.foreground

import app.snapsync.model.ForegroundReturn
import app.snapsync.model.awayFor
import app.snapsync.ports.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Instant

/**
 * The app's comings and goings from the foreground, over the process [clock]: whether it is in front now — read by
 * work worth doing only while a person can see it (the counts a wake's steps refresh, the memory readings a bug report
 * carries) — and each return, counted with how long the app was away, which the status host reads so the create
 * screen's draft can follow it. The lifecycle's entry points are its only writers.
 */
class AppForeground(private val clock: Clock) {
    private val now = AtomicBoolean(false)
    private val latest = MutableStateFlow(ForegroundReturn.NONE)
    private val leftAt = MutableStateFlow<Instant?>(null)

    /** Whether the app is in the foreground. */
    val isForeground: Boolean get() = now.load()

    /** The latest return to the foreground. */
    val returns: StateFlow<ForegroundReturn> get() = latest

    /** The app came back: one more return, and how long it was away (none on a cold launch). */
    fun entered() {
        now.store(true)
        val away = awayFor(leftAt.getAndUpdate { null }, clock.now())
        latest.update { ForegroundReturn(it.count + 1, away) }
    }

    /** The app left the foreground (a transient interruption included). */
    fun left() {
        now.store(false)
        leftAt.value = clock.now()
    }
}
