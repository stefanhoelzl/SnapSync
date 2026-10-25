package app.snapsync.model

import app.snapsync.domain.program.InvalidProgramState
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] that never catches cancellation (law "Catch sites keep cancellation", capability
 * `docs/architecture.md`), nor an [InvalidProgramState], which nothing catches (`docs/patterns.md`, "Invalid program
 * state").
 *
 * `runCatching` catches every `Throwable`, and a cancelled coroutine signals by throwing
 * [CancellationException]. Wrapped around a suspend call, it turns "my caller gave up" into an ordinary failure
 * value: the join reports FAILED for a join nobody is waiting on, the leave logs an Error — a crash-report event —
 * for a leave that was merely cancelled, and structured concurrency loses the signal it runs on. This rethrows
 * cancellation and turns everything else into a [Result], exactly as `runCatching` did.
 *
 * It is the ONE catch-all the production source may use (the catch gate in `:test:architecture` refuses bare
 * `runCatching` and `catch (… : Throwable | Exception)`), and it is safe around non-suspend code too, where it
 * behaves as `runCatching` does.
 */
inline fun <R> runCatchingCancellable(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: InvalidProgramState) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

/** The receiver form of [runCatchingCancellable], for the `x.runCatching { … }` call shape. */
inline fun <T, R> T.runCatchingCancellable(block: T.() -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: InvalidProgramState) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

/**
 * Run [block] as a **best-effort** step: a failure is logged at `Warn` with [name] and swallowed, so the caller
 * carries on; cancellation is rethrown, never logged as a failure (law "Catch sites keep cancellation", capability
 * `docs/architecture.md`). Returns whether the step completed.
 *
 * For a step whose failure must STOP the sequence, do not use this: a required step lets its failure propagate
 * (law "A multi-step use case declares which steps are required").
 */
inline fun Logger.bestEffort(name: String, block: () -> Unit): Boolean =
    runCatchingCancellable(block)
        .onFailure { w(it) { "best-effort step failed: $name" } }
        .isSuccess

/**
 * Run [block] as a **contained** step: a failure is logged at `Warn` as [failure] and swallowed, cancellation is
 * rethrown. The shape every wake's "its own work failed; its tail still runs" takes. Not `inline`, unlike
 * [bestEffort]: a composition calls it, and the failure arm then lives here, where it is tested, rather than being
 * copied into a caller that holds no branch.
 */
suspend fun Logger.contained(failure: String, block: suspend () -> Unit) {
    runCatchingCancellable { block() }.onFailure { w(it) { failure } }
}

/**
 * The handler of a root's process scope: what no coroutine handled is logged at `Error`, and the app lives — errors
 * reduce into state and never crash the shell — except an [InvalidProgramState], which is rethrown to the coroutines
 * library's last resort and ends the process with its crash report (`docs/patterns.md`, "Invalid program state").
 */
fun uncaughtInRootScope(log: Logger): CoroutineExceptionHandler = CoroutineExceptionHandler { _, failure ->
    when (failure) {
        is InvalidProgramState -> throw failure
        else -> log.e(failure) { "uncaught in app scope — logged, not fatal" }
    }
}
