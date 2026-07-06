package app.snapsync.membership

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Attempts before [clearRequestedOffMain] gives up on a persistently failing clear. */
private const val DEFAULT_CLEAR_ATTEMPTS = 3

/**
 * Clear the ledger's orphaned `REQUESTED` rows **off the main thread**, **awaited to completion**, with
 * a small bounded retry. Disabling the upload extension wipes every in-flight OS upload job; the
 * `REQUESTED` rows those jobs left must be dropped by [clear] (`LedgerBackend.clearRequested`) **before**
 * the extension is re-enabled — otherwise the re-enabled extension's fresh `REQUESTED` rows race a
 * still-running clear and get deleted (the §7.1 bug, whose root cause was a fire-and-forget
 * `scope.launch { clearRequested() }` on the main scope).
 *
 * The clear is a **synchronous SQLite `DELETE`**, so it runs on [dispatcher] — `Dispatchers.Default` by
 * default (Kotlin/Native has **no** `Dispatchers.IO`), never the caller's `Dispatchers.Main` scope where
 * it would risk a hang under cross-process WAL contention. Returns whether the clear ultimately
 * succeeded; a persistent failure is **logged, never thrown** — best-effort, matching the surrounding
 * disable/leave teardown, so a re-enable still proceeds rather than trapping the caller.
 *
 * Pure `commonMain` logic (takes [clear] as a lambda, constructs no ledger type), so the retry behavior
 * is unit-tested on JVM and the iOS simulator; the app shell keeps only the two-call disable sequence.
 */
suspend fun clearRequestedOffMain(
    clear: suspend () -> Unit,
    attempts: Int = DEFAULT_CLEAR_ATTEMPTS,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    log: Logger = Logger.withTag("clearRequested"),
): Boolean = withContext(dispatcher) {
    repeat(attempts) { i ->
        if (runCatching { clear() }.isSuccess) return@withContext true
        log.w { "clearRequested attempt ${i + 1}/$attempts failed; retrying" }
    }
    log.e { "clearRequested gave up after $attempts attempt(s); orphaned REQUESTED rows may remain" }
    false
}
