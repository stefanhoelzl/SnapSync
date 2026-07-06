package app.snapsync.logging

/**
 * The ambient "what triggered this" context, read by the device-log writers to prefix every line
 * with `[<entryPoint>]` so downstream engine/HTTP/download lines trace back to the entry point that
 * drove them (capability `diagnostic-logging`).
 *
 * It is a **process-global** holder, deliberately NOT a `@ThreadLocal` and NOT a coroutine-context
 * element: the Kermit [co.touchlab.kermit.LogWriter.log] callback is a plain synchronous call with
 * no coroutine context and no knowledge of which thread's work triggered it. A plain global is the
 * only form the writer can read synchronously from any thread, and — being global, not per-thread —
 * the prefix survives dispatcher/thread hops within an invocation (e.g. a Ktor call on the Darwin
 * queue). The trade-off (two genuinely-overlapping invocations can mislabel a line) is accepted:
 * iOS delivers app entry points serially per process and this is a dev-only diagnostic log.
 *
 * "Outermost wins": the first [enter] within a synchronous execution span sets the context; nested
 * wrapped seams keep the outer label until it is restored. Because fire-and-forget `scope.launch`
 * bodies run after their launcher returns, instrumentation sets the context *inside* the launched
 * coroutine so it spans the actual async work.
 */
object LogContext {

    var current: String? = null
        private set

    /**
     * Set [name] as the current context only if none is set (outermost wins). Returns `true` when
     * THIS call established the context — the caller must pass that back to [exit] so only the
     * establishing call clears it.
     */
    fun enter(name: String): Boolean {
        if (current != null) return false
        current = name
        return true
    }

    /** Clear the context, but only if [owned] (i.e. this caller established it via [enter]). */
    fun exit(owned: Boolean) {
        if (owned) current = null
    }
}
