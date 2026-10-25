package app.snapsync.sentry

import app.snapsync.contracts.CrashReporterContract
import app.snapsync.contracts.FatalRoute
import app.snapsync.domain.program.InvalidProgramState
import app.snapsync.entry.NoEntryEvents
import app.snapsync.ports.ThreadLabel
import app.snapsync.scheduling.Delivery
import app.snapsync.scheduling.InboundEntryPoint
import app.snapsync.scheduling.Runtime
import app.snapsync.scheduling.RuntimePorts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

// The roads an `InvalidProgramState` takes to the end of a process, through the production runtime itself — what a
// crash child of the process-death binding runs (`CrashReporterContract`, `AFTER_A_PROCESS_DEATH`).

/** Throws in work nobody awaits: the runtime's process scope, whose handler ends the process. */
internal fun endOnUnawaitedWork() = runBlocking {
    process().detached("OnContractDeath") {
        throw InvalidProgramState(CrashReporterContract.becauseOf(FatalRoute.UNAWAITED_WORK))
    }
}

/** Throws in a delivery the runtime answers on this thread, as the platform's own callback is answered. */
internal fun endOnPlatformDelivery() = OnContractDelivery(process()).deliver(Unit)

private class OnContractDelivery(runtime: Runtime) : InboundEntryPoint<Unit>(runtime) {
    override fun Delivery.run(arg: Unit) {
        throw InvalidProgramState(CrashReporterContract.becauseOf(FatalRoute.PLATFORM_DELIVERY))
    }
}

/** The process's runtime, as a shipped composition builds it: no background time to hold, nothing observing its entries. */
private fun process() = Runtime(
    parent = null,
    lane = Dispatchers.Default,
    RuntimePorts(backgroundTime = null, threadLabel = Unlabelled, entryEvents = NoEntryEvents),
)

private object Unlabelled : ThreadLabel {
    override fun swap(label: String?): String? = null

    override fun current(): String? = null
}
