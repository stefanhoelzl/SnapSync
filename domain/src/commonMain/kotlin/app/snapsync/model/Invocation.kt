package app.snapsync.model

import co.touchlab.kermit.Logger
import kotlin.time.TimeSource

/**
 * Wrap a platform invocation / app entry point / background trigger so it logs enter + exit with
 * its parameters, its result, and its elapsed duration, and sets the ambient [LogContext] for the
 * duration so downstream lines trace back to it (capability `diagnostic-logging`, D3).
 *
 * - `→ <name>(<params>)` on entry, `← <name> = <result> (<ms>ms)` on success, and a warn
 *   `✗ <name> threw (<ms>ms)` on throw (the throwable is re-thrown unchanged).
 * - [params] is an already-built short string and [result] a short-string renderer — the CALL SITE
 *   controls verbosity, so we never blanket-`toString()` a large or expensive object.
 * - Not marked `suspend`: it is `inline`, so [block] is inlined into the caller and may suspend when
 *   the call site is a coroutine, while non-suspend entry points use the very same function.
 *
 * NOTE on async: for fire-and-forget work, wrap the body *inside* `scope.launch { … }`, not the
 * synchronous launcher — otherwise the context is restored before the async work runs.
 */
inline fun <T> Logger.invocation(
    name: String,
    params: String = "",
    result: (T) -> String = { "" },
    block: () -> T,
): T {
    val owned = LogContext.enter(name)
    val start = TimeSource.Monotonic.markNow()
    i { "→ $name" + if (params.isEmpty()) "" else "($params)" }
    try {
        val value = block()
        val ms = start.elapsedNow().inWholeMilliseconds
        val rendered = result(value)
        i { "← $name" + (if (rendered.isEmpty()) "" else " = $rendered") + " (${ms}ms)" }
        return value
    } catch (t: Throwable) {
        val ms = start.elapsedNow().inWholeMilliseconds
        w(t) { "✗ $name threw (${ms}ms)" }
        throw t
    } finally {
        LogContext.exit(owned)
    }
}
