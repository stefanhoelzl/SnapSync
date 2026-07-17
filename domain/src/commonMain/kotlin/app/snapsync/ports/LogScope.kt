package app.snapsync.ports

import co.touchlab.kermit.Logger
import kotlin.time.TimeSource

/**
 * The ambient "what triggered this" seam (capability `diagnostic-logging`): the set/clear boundary
 * that lets the device-log writers prefix every line with `[<entryPoint>]` so downstream
 * engine/HTTP/download lines trace back to the entry point that drove them.
 *
 * It is a **port** because the holder it fronts is a process-global mutable — which may not live in
 * `:domain` (law "State and authority": no global mutable state in the core, ever). The concrete
 * holder therefore lives beside the writers that read it synchronously (`:adapter:ios:ext-safe`'s
 * `LogContext` / `IosLogScope`); world and tests inject [NoOp]. This repays the step-5
 * violation-in-transit that parked the global in `model/`.
 *
 * "Outermost wins": the first [enter] within a synchronous execution span sets the context; nested
 * wrapped seams keep the outer label until it is restored. Because fire-and-forget `scope.launch`
 * bodies run after their launcher returns, instrumentation sets the context *inside* the launched
 * coroutine so it spans the actual async work.
 */
interface LogScope {

    /**
     * Set [name] as the current context only if none is set (outermost wins). Returns `true` when
     * THIS call established the context — the caller must pass that back to [exit] so only the
     * establishing call clears it.
     */
    fun enter(name: String): Boolean

    /** Clear the context, but only if [owned] (i.e. this caller established it via [enter]). */
    fun exit(owned: Boolean)

    /** The no-context implementation for world / tests (and any binary without device logging). */
    object NoOp : LogScope {
        override fun enter(name: String): Boolean = false
        override fun exit(owned: Boolean) {}
    }
}

/**
 * Wrap a platform invocation / app entry point / background trigger so it logs enter + exit with
 * its parameters, its result, and its elapsed duration, and sets the ambient [LogScope] for the
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
    scope: LogScope,
    name: String,
    params: String = "",
    result: (T) -> String = { "" },
    block: () -> T,
): T {
    val owned = scope.enter(name)
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
        scope.exit(owned)
    }
}
