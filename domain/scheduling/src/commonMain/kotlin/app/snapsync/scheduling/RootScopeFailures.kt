package app.snapsync.scheduling

import app.snapsync.domain.program.InvalidProgramState
import app.snapsync.inbound.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * **The handler of a root's process scope** — the parent of the [Runtime]'s: what no coroutine handled is logged as an
 * `Error`, and the app lives — errors reduce into state and never crash the shell — except an [InvalidProgramState],
 * which is rethrown to the coroutines library's last resort and ends the process with its crash report
 * (`docs/patterns.md`, "Invalid program state").
 */
class RootScopeFailures :
    AbstractCoroutineContextElement(CoroutineExceptionHandler),
    CoroutineExceptionHandler {
    private val log = Log("RootScope")

    override fun handleException(context: CoroutineContext, exception: Throwable) = when (exception) {
        is InvalidProgramState -> throw exception
        else -> log.unlabelled.e("uncaught in the root scope: logged, not fatal", cause = exception)
    }
}
