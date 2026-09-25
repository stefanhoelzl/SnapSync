package app.snapsync.contracts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How long a hand-off clause waits for the platform's answer before reading `NotWithin`. */
const val HANDOFF_ANSWER_MILLIS: Long = 10_000

/**
 * Runs [block] under a bound measured on the REAL clock, and throws [WaitExpired] when it expires.
 *
 * A clause body runs inside `runTest`, whose virtual clock skips an idle wait at once — so a plain
 * `withTimeout` there would expire before a platform callback on the main queue could possibly arrive.
 * Moving off the test scheduler makes the bound a real one, and [WaitExpired] makes its expiry read
 * `NotWithin` rather than `Failed` (`docs/architecture.md`, "Outcomes are explicit and none is silent").
 */
suspend fun <T> withinRealTime(millis: Long, block: suspend () -> T): T =
    withContext(Dispatchers.Default) { withTimeoutOrNull(millis) { block() } } ?: throw WaitExpired(millis)
