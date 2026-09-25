package app.snapsync.model

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] that never catches cancellation (law "Catch sites keep cancellation", capability
 * `docs/architecture.md`).
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
    } catch (e: Throwable) {
        Result.failure(e)
    }

/** The receiver form of [runCatchingCancellable], for the `x.runCatching { … }` call shape. */
inline fun <T, R> T.runCatchingCancellable(block: T.() -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
