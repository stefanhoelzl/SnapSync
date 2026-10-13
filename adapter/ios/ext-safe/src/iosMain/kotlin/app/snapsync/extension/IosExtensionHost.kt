package app.snapsync.extension

import app.snapsync.inbound.CompositionOnly
import app.snapsync.model.BeforeListen
import app.snapsync.model.HandlerSlot
import app.snapsync.model.PlatformEntry
import app.snapsync.model.ProcessingAnswer
import app.snapsync.ports.ExtensionHandlers
import app.snapsync.ports.ExtensionHost
import co.touchlab.kermit.Logger

/**
 * The iOS [ExtensionHost]: the PhotoKit background-upload extension's `process()`
 * and `notifyTermination()`, which the Swift principal class forwards to [deliverProcess] and [deliverTerminate].
 *
 * `process()` is synchronous by the operating system's own contract and the process does not outlive it, so
 * [deliverProcess] asks the handler for its answer on its own thread — the core's answered entry point blocks it — and
 * answers the platform's raw result ([processingResultRawValue]).
 */
class IosExtensionHost(private val log: Logger) : ExtensionHost {
    private val handlers = HandlerSlot<ExtensionHandlers>("ExtensionHost", BeforeListen.Logged(log))

    @CompositionOnly
    override fun inbound(handlers: ExtensionHandlers) = this.handlers.set(handlers)

    /**
     * One invocation: the handler's answer, passed through as the `PHBackgroundResourceUploadProcessingResult` raw value the
     * Swift principal class constructs with `init?(rawValue:)`. An invocation before the composition registered is a
     * wiring fault, answered as a failure rather than a throw across the ObjC boundary.
     */
    @PlatformEntry
    fun deliverProcess(): Int {
        val registered = handlers.orNull("process()")
        val answer = registered?.onProcess?.answer(Unit) ?: ProcessingAnswer.FAILED
        return answer.processingResultRawValue()
    }

    /** The end of an invocation — never a kill, which sends nothing. Only recorded. */
    @PlatformEntry
    fun deliverTerminate() {
        handlers.orNull("notifyTermination()", BeforeListen.Dropped)?.onTerminate?.deliver(Unit)
    }
}

/**
 * [ProcessingAnswer] → the iOS 26.1 `PHBackgroundResourceUploadProcessingResult` **raw value**, case for case. The
 * system type is Swift-only, so the Swift shell constructs it via `init?(rawValue:)` from exactly these integers.
 * Raw values are the swiftinterface's case order (`failure`, `processing`, `completed`), verified on device.
 */
fun ProcessingAnswer.processingResultRawValue(): Int = when (this) {
    ProcessingAnswer.COMPLETED -> COMPLETED_RAW
    ProcessingAnswer.PROCESSING -> PROCESSING_RAW
    ProcessingAnswer.FAILED -> FAILURE_RAW
}

private const val FAILURE_RAW = 0
private const val PROCESSING_RAW = 1
private const val COMPLETED_RAW = 2
