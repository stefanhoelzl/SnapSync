package app.snapsync.composition.extension

import app.snapsync.composition.process.ProcessGraph
import app.snapsync.composition.process.startProcess
import app.snapsync.inbound.CompositionOnly
import app.snapsync.ports.ExtensionHandlers
import app.snapsync.scheduling.Runtime
import app.snapsync.scheduling.RuntimePorts
import kotlinx.coroutines.CoroutineDispatcher

/**
 * **The upload extension's composition** (`docs/architecture.md`, "The composition layer"; "Events arrive through
 * `inbound`"): its own runtime, the process started first ([startProcess] — crash reporting before anything else), its
 * [ExtensionGraph] over [ports], and its one entry port's handlers registered on [ExtensionPorts.host]. Building opens
 * nothing: every store opens on first use, so the cycle touches the shared database only once the operating system
 * invokes it.
 */
@OptIn(CompositionOnly::class)
fun composeExtension(
    ports: ExtensionPorts,
    /** The lane the extension's own work runs on — its root's to name, as every root names the app's. */
    lane: CoroutineDispatcher,
    /**
     * Where `process()`'s work runs while the operating system's thread waits for its answer: `null` on the phone — on
     * that thread — or a lane a host must see every task on (`Runtime`'s `answerOn`).
     */
    answerOn: CoroutineDispatcher?,
): ExtensionGraph {
    // The extension's own runtime: no scope of its root's to live under, and no background time to hold — it answers on
    // the invoking thread.
    val runtime = Runtime(
        parent = null,
        lane,
        RuntimePorts(backgroundTime = null, ports.process.threadLabel, ports.process.entryEvents),
        answerOn = answerOn,
    )
    val process = ProcessGraph(ports.process, runtime)
    val started = startProcess(ports.process, process)
    val graph = ExtensionGraph(ports, runtime, process, started)
    ports.host.inbound(ExtensionHandlers(graph.onProcess, graph.onTerminate))
    return graph
}
