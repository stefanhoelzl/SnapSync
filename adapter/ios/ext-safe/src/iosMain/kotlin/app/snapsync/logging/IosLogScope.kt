package app.snapsync.logging

import app.snapsync.ports.LogScope
import app.snapsync.ports.invocation
import co.touchlab.kermit.Logger

/**
 * The iOS binding of the `:domain` `LogScope` port (capability `diagnostic-logging`): drives the
 * process-global [LogContext] the device-log writers read. This is the ambient-context set/clear
 * seam every live iOS binary injects (world / tests inject `LogScope.NoOp`), so the global mutable
 * stays in the adapter layer while `:domain` code drives it through the port.
 */
object IosLogScope : LogScope {
    override fun enter(name: String): Boolean = LogContext.enter(name)
    override fun exit(owned: Boolean) = LogContext.exit(owned)
}

/**
 * The iOS convenience overload of [app.snapsync.ports.invocation]: an entry point or adapter that
 * links this module wraps itself with `log.invocation("name") { … }` and the ambient [LogContext] is
 * driven for it, no `LogScope` in hand. It delegates to the single port-driven implementation over
 * [IosLogScope], so there is exactly one enter/exit/log body. (`:domain` features that cannot link
 * this module take the two-argument `Logger.invocation(scope, …)` and are injected the port.)
 */
inline fun <T> Logger.invocation(
    name: String,
    params: String = "",
    result: (T) -> String = { "" },
    block: () -> T,
): T = invocation(IosLogScope, name, params, result, block)
