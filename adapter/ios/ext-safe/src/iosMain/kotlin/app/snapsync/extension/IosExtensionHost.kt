package app.snapsync.extension

import app.snapsync.model.BeforeListen
import app.snapsync.model.CycleResult
import app.snapsync.model.HandlerSlot
import app.snapsync.model.PlatformEntry
import app.snapsync.ports.ExtensionHandlers
import app.snapsync.ports.ExtensionHost
import co.touchlab.kermit.Logger
import kotlinx.coroutines.runBlocking

/**
 * The iOS [ExtensionHost] (capability `background-upload`): the PhotoKit background-upload extension's `process()`
 * and `notifyTermination()`, which the Swift principal class forwards to [deliverProcess] and [deliverTerminate].
 *
 * `process()` is synchronous by the operating system's own contract and the process does not outlive it, so
 * [deliverProcess] blocks its thread on the handler — the one pinned `runBlocking` in production
 * (`MainLaneContainmentTest`) — and answers the platform's raw result ([processingResultRawValue]).
 */
class IosExtensionHost(private val log: Logger) : ExtensionHost {
    private val handlers = HandlerSlot<ExtensionHandlers>("ExtensionHost", BeforeListen.Logged(log))

    override fun listen(handlers: ExtensionHandlers) = this.handlers.set(handlers)

    /**
     * One invocation: the handler's cycle, answered as the `PHBackgroundResourceUploadProcessingResult` raw value the
     * Swift principal class constructs with `init?(rawValue:)`. An invocation before the composition registered is a
     * wiring fault, answered as a failure rather than a throw across the ObjC boundary.
     */
    @PlatformEntry
    fun deliverProcess(): Int {
        val registered = handlers.orNull("process()")
        val result = if (registered == null) CycleResult.FAILED else runBlocking { registered.onProcess() }
        return result.processingResultRawValue()
    }

    /** The end of an invocation — never a kill, which sends nothing. Only recorded. */
    @PlatformEntry
    fun deliverTerminate() {
        handlers.orNull("notifyTermination()", BeforeListen.Dropped)?.onTerminate()
    }
}

/**
 * [CycleResult] → the iOS 26.1 `PHBackgroundResourceUploadProcessingResult` **raw value**. The system type is
 * Swift-only, so the Swift shell constructs it via `init?(rawValue:)` — this is the one place that decides which case
 * each result means, and it is exhaustive: a new [CycleResult] case cannot slip through untaught.
 *
 * Raw values are the swiftinterface's case order (`failure`, `processing`, `completed`), verified on device.
 * [CycleResult.SKIPPED] maps like [CycleResult.COMPLETED]: nothing to do, the system rests. [CycleResult.Paused] maps
 * like [CycleResult.PROCESSING]: the cycle touched nothing and asks to be invoked again, until the process it waits for
 * (the app, migrating the download store) has run.
 */
fun CycleResult.processingResultRawValue(): Int = when (this) {
    CycleResult.COMPLETED, CycleResult.SKIPPED -> COMPLETED_RAW
    CycleResult.PROCESSING, is CycleResult.Paused -> PROCESSING_RAW
    CycleResult.FAILED -> FAILURE_RAW
}

private const val FAILURE_RAW = 0
private const val PROCESSING_RAW = 1
private const val COMPLETED_RAW = 2
