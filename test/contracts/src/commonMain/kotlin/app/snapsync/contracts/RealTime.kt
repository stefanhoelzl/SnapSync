package app.snapsync.contracts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
internal suspend fun settle() = withContext(Dispatchers.Default) { delay(SETTLE) }

private val POLL = 10.milliseconds
private val SETTLE = 200.milliseconds
