package app.snapsync.logging

import app.snapsync.ports.EntryContext
import app.snapsync.ports.invocation
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity

/**
 * The iOS binding of the `:domain` `EntryContext` port (capability `privacy-security`): drives the
 * process-global [LogContext] the device-log writers read. This is the ambient-context set/clear
 * seam every live iOS binary injects (world / tests inject `EntryContext.NoOp`), so the global mutable
 * stays in the adapter layer while `:domain` code drives it through the port.
 */
object IosEntryContext : EntryContext {
    override fun enter(name: String): Boolean = LogContext.enter(name)
    override fun exit(owned: Boolean) = LogContext.exit(owned)
    override fun current(): String? = LogContext.current
}

/**
 * The thread-scoped binding of the `EntryContext` port: the prefix reaches only lines logged on the thread
 * the entry point was called on (capability `privacy-security`).
 *
 * ⚠️ **Only for a body that does not suspend and launches nothing whose lines should inherit the
 * prefix.** That is what makes it exact: a synchronous call occupies its thread, so nothing else logs
 * there meanwhile. Work it hands to another thread logs unprefixed — never mislabelled, but untraced.
 * Anything that fans out asynchronously belongs on [IosEntryContext].
 *
 * It exists because the process-wide claim mislabels concurrent work that has no entry point of its
 * own — measured, when a MetricKit delivery labelled seven launch lines as its own (see [LogContext]).
 */
object IosThreadEntryContext : EntryContext {
    override fun enter(name: String): Boolean = LogContext.enterThread(name)
    override fun exit(owned: Boolean) = LogContext.exitThread(owned)
    override fun current(): String? = LogContext.current
}

/**
 * The iOS convenience overload of [app.snapsync.ports.invocation]: an entry point or adapter that
 * links this module wraps itself with `log.invocation("name") { … }` and the ambient [LogContext] is
 * driven for it, no `EntryContext` in hand. It delegates to the single port-driven implementation over
 * [IosEntryContext], so there is exactly one enter/exit/log body. (`:domain` features that cannot link
 * this module take the two-argument `Logger.invocation(scope, …)` and are injected the port.)
 */
inline fun <T> Logger.invocation(
    name: String,
    params: String = "",
    severity: Severity = Severity.Info,
    result: (T) -> String = { "" },
    block: () -> T,
): T = invocation(IosEntryContext, name, params, severity, result, block)
