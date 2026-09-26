package app.snapsync.logging

/**
 * The ambient "what triggered this" holder, read by the device-log writers ([FileLogSink] /
 * [PublicNSLogSink], which sit beside it) to prefix every line with `[<entryPoint>]` so downstream
 * engine/HTTP/download lines trace back to the entry point that drove them (capability
 * `privacy-security`). Driven through the `:domain` `EntryContext` port (see [IosEntryContext]) so the
 * process-global mutable lives here in the adapter — where a platform global is legitimate — and not
 * in the core (law "State and authority").
 *
 * It holds **two** claims, and [current] resolves the thread's own before the process-wide one.
 *
 * **Process-wide** ([enter], bound as [IosEntryContext]) is the default, and it is deliberately NOT a
 * `@ThreadLocal` and NOT a coroutine-context element: the Kermit [co.touchlab.kermit.LogWriter.log]
 * callback is a plain synchronous call with no coroutine context and no knowledge of which thread's
 * work triggered it. A global is the only form the writer can read from any thread, and — being
 * global, not per-thread — the prefix survives dispatcher/thread hops within an invocation (e.g. a
 * Ktor call on the Darwin queue).
 *
 * Its inaccuracy is measured, not assumed: entry points are **not** delivered serially with the rest
 * of the process's work. On 2026-09-14 a MetricKit delivery arrived while launch work was still
 * logging, and seven launch lines (`gallery`, `Http`, `PushRegistration`, `SnapSyncRoot`) carried
 * `[didReceiveMetricPayloads]`. A process-wide claim labels every concurrent line that has no entry
 * point of its own. Accepted for a dev-only log — for the entry points that cannot avoid it.
 *
 * **Thread-scoped** ([enterThread], bound as [IosThreadEntryContext]) is for the ones that can. A
 * synchronous call occupies its thread, so every line on that thread during the call is its own and
 * no line elsewhere is: the claim is exact by construction. It is only for bodies that do not suspend
 * and launch nothing whose lines should inherit the prefix — such work would log unprefixed, which is
 * the safe way to be wrong.
 *
 * "Outermost wins", across both kinds: the first enter on an execution span sets the context, nested
 * wrapped seams keep the outer label, and on a thread holding a thread-scoped claim NO enter claims
 * anything — otherwise a process-wide seam reached from inside it would take the global slot and
 * bring the bleed straight back. Because fire-and-forget `scope.launch` bodies run after their
 * launcher returns, instrumentation sets the context *inside* the launched coroutine so it spans the
 * actual async work.
 *
 * Decision record: `changes/archive/2026-09-14-thread-scoped-log-prefix`.
 */
object LogContext {

    private var processWide: String? = null

    /** The prefix a line logged right now, on this thread, carries. */
    val current: String?
        get() = ThreadClaim.name ?: processWide

    /**
     * Claim the context process-wide, only if nothing is claimed here (outermost wins). Returns `true`
     * when THIS call established it — the caller must pass that back to [exit] so only the
     * establishing call clears it.
     */
    fun enter(name: String): Boolean {
        if (ThreadClaim.name != null || processWide != null) return false
        processWide = name
        return true
    }

    /** Clear the process-wide claim, but only if [owned] (this caller established it via [enter]). */
    fun exit(owned: Boolean) {
        if (owned) processWide = null
    }

    /**
     * Claim the context for the calling thread only, unless this thread already holds one. A
     * process-wide claim held elsewhere does not block it: this call is a separate trigger occupying
     * this thread, and its lines are its own.
     */
    fun enterThread(name: String): Boolean {
        if (ThreadClaim.name != null) return false
        ThreadClaim.name = name
        return true
    }

    /** Clear this thread's claim, but only if [owned] (this caller established it via [enterThread]). */
    fun exitThread(owned: Boolean) {
        if (owned) ThreadClaim.name = null
    }
}

/**
 * One copy per thread. `@ThreadLocal` rather than a map keyed by `pthread_self()`: no lock on every log
 * line, and nothing to leak for a thread that dies while claimed.
 */
@kotlin.native.concurrent.ThreadLocal
private object ThreadClaim {
    var name: String? = null
}
