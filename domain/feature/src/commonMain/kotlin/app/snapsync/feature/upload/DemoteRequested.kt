package app.snapsync.feature.upload

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Attempts before [demoteRequestedOffMain] gives up on a persistently failing demote. */
private const val DEFAULT_DEMOTE_ATTEMPTS = 3

/**
 * Demote the ledger's orphaned `REQUESTED` rows to `DISCOVERED` **off the main thread**, **awaited to
 * completion**, with a small bounded retry. Disabling the upload extension wipes every in-flight OS upload
 * job; the `REQUESTED` rows those jobs left must be demoted by [demote] (`LedgerStore.demoteRequested`)
 * **before** the extension is re-enabled — otherwise the re-enabled extension's fresh `REQUESTED` rows race
 * a still-running repair and get demoted too (the §7.1 bug, whose root cause was a fire-and-forget
 * `scope.launch { … }` of the former delete on the main scope).
 *
 * The demote is a **synchronous SQLite `UPDATE`**, so it runs on [dispatcher] — `Dispatchers.Default` by
 * default (Kotlin/Native exposes no **public** `Dispatchers.IO`: it is `internal` as of coroutines
 * 1.10.2, established by compile; expiry is a release that publishes it). It therefore does not hold the
 * caller's **serial** composition lane while it waits, and cannot hang under cross-process WAL
 * contention on a lane other work is queued behind. Returns whether the demote ultimately
 * succeeded; a persistent failure is **logged, never thrown** — best-effort, so a re-enable still proceeds
 * rather than trapping the caller, and the next mechanism start repeats the repair.
 *
 * Pure `commonMain` logic (takes [demote] as a lambda, constructs no ledger type), so the retry behavior
 * is unit-tested on JVM and the iOS simulator.
 */
suspend fun demoteRequestedOffMain(
    demote: suspend () -> Unit,
    attempts: Int = DEFAULT_DEMOTE_ATTEMPTS,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    log: Logger = Logger.withTag("demoteRequested"),
): Boolean = withContext(dispatcher) {
    repeat(attempts) { i ->
        if (runCatching { demote() }.isSuccess) return@withContext true
        log.w { "demoteRequested attempt ${i + 1}/$attempts failed; retrying" }
    }
    log.e { "demoteRequested gave up after $attempts attempt(s); orphaned REQUESTED rows remain until the next start" }
    false
}
