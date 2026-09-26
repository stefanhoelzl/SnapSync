package app.snapsync.ports

import app.snapsync.model.PlatformEntry
import app.snapsync.model.CycleResult

/**
 * The upload extension process's **inbound** port: what the operating system tells the extension. The core
 * implements it (`compose/`'s `extensionEntries`) and the root delegates to it.
 */
interface ExtensionEntries {
    /** Run one upload cycle and report how it ended. Never throws: a failure is [CycleResult.FAILED]. */
    @PlatformEntry
    suspend fun process(): CycleResult

    /**
     * The operating system's `notifyTermination`: the end of an invocation, NOT a kill. Measured on an SE2 (iOS
     * 26.6, 2026-09-23): it arrives about 55 ms after every normal return of [process], and never before the kill
     * that ends a call running past its ~60 s budget — that kill sends nothing at all (capability
     * `background-upload`, "How the operating system invokes the extension is recorded as measured").
     */
    @PlatformEntry
    fun onTerminate()
}
