package app.snapsync.platform

import app.snapsync.domain.program.InvalidProgramState
import kotlin.coroutines.cancellation.CancellationException

/**
 * **The adapters' catch-all at a platform boundary whose failures the platform does not document** — [runCatching]
 * that never catches cancellation (law "Catch sites keep cancellation", `docs/architecture.md`), nor an
 * [InvalidProgramState], which nothing catches (`docs/patterns.md`, "Invalid program state").
 *
 * `runCatching` catches every `Throwable`, and a cancelled coroutine signals by throwing [CancellationException].
 * Wrapped around a suspend call, it turns "my caller gave up" into an ordinary failure value, and structured concurrency
 * loses the signal it runs on. These rethrow cancellation and turn everything else into a [Result].
 *
 * An adapter's tool only: the core cannot see this module, and answers every expected failure as a value. Where the
 * platform documents what it throws, an adapter catches that type instead; each call here is a named site in
 * `CatchSitesTest`.
 */
object PlatformCalls {
    /** [block]'s answer, or what it threw. */
    fun <R> attempt(block: () -> R): Result<R> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidProgramState) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

    /** [block]'s answer, or what it threw — for a platform call that suspends. */
    suspend fun <R> attemptSuspending(block: suspend () -> R): Result<R> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidProgramState) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
}
