package app.snapsync.flow

import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlin.coroutines.cancellation.CancellationException

/**
 * Run a flow's independent [children] concurrently, **isolated** from one another, and return once every one of
 * them has finished (law "A trigger flow never outlives its own run", capability `module-architecture`).
 *
 * A child that throws is logged under its name and cancels nothing: its siblings run to completion. That is the
 * difference from the bare `coroutineScope { launch … }` it replaces, where one throwing child — the foreground
 * upload pump rethrows whatever its cycle threw — cancelled the status refresh, the download reconcile and the
 * settle beside it, and made the entry point throw (the `sync-status` spec: "A failure in any one refresh SHALL
 * NOT cancel its siblings"). Cancellation of the flow itself still reaches every child, and a child's own
 * cancellation is not reported as a failure.
 *
 * The ONE way a flow fans out: the flow-zone gate refuses `launch`/`async` anywhere else in `flow/`.
 */
internal suspend fun fanOut(flow: String, declare: FanOut.() -> Unit) {
    val log = Logger.withTag(flow)
    val children = FanOut().apply(declare).children
    supervisorScope {
        children.forEach { (name, child) ->
            launch {
                try {
                    child()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    log.e(t) { "$flow: `$name` failed — its siblings carry on" }
                }
            }
        }
    }
}

/** The children a [fanOut] runs, each named so a failure says which one it was. */
internal class FanOut {
    internal val children = mutableListOf<Pair<String, suspend () -> Unit>>()

    fun child(name: String, block: suspend () -> Unit) {
        children += name to block
    }
}
