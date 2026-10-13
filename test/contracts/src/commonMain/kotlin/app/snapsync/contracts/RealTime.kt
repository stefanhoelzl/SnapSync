package app.snapsync.contracts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Real-time waits, for clauses whose subject runs on a real dispatcher rather than the clause's virtual clock.

/** Polls [condition] in real time until it holds or [within] expires; `true` if it held. */
internal suspend fun eventually(within: Duration = 10.seconds, condition: () -> Boolean): Boolean =
    withContext(Dispatchers.Default) {
        withTimeoutOrNull(within) {
            while (!condition()) delay(POLL)
            true
        } ?: false
    }

/** A short real-time pause, for asserting that something did NOT happen after a thing that did. */
internal suspend fun settle() = pause(SETTLE)

/**
 * The kind of binding the running clause is bound to, in its coroutine context — put there by the runner, so a clause
 * body never asks.
 */
internal class RunningBinding(val kind: BindingKind) : AbstractCoroutineContextElement(RunningBinding) {
    companion object Key : CoroutineContext.Key<RunningBinding>
}

/**
 * A real-time pause of [duration], for asserting that something did NOT happen after a thing that did — and none at all
 * against a [BindingKind.Fake]. A fake does nothing on its own: what it does, it does on the call that asked or when its
 * operator moves it, so there is nothing a pause could let happen, and waiting would only spend the build's time. A real
 * platform (live, or replaying a recording of one) keeps the whole pause.
 */
internal suspend fun pause(duration: Duration) {
    if (currentCoroutineContext()[RunningBinding]?.kind == BindingKind.Fake) return
    withContext(Dispatchers.Default) { delay(duration) }
}

private val POLL = 10.milliseconds
private val SETTLE = 200.milliseconds
