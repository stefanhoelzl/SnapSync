package app.snapsync.ports

import app.snapsync.inbound.Answered
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Inbound
import app.snapsync.model.ProcessingAnswer

/**
 * **The operating system's invocations of the upload extension**: on iOS ≥26.1 the
 * PhotoKit background-upload extension's `process()` and `notifyTermination()`. Android has none — a library change
 * reaches it as a `Wake` instead.
 *
 * One external system, deciding nothing: what an invocation runs is the composition's handler, and the adapter only
 * hands its answer back to the platform in the platform's own form. An event port, registered once by the extension's
 * composition — the extension has no host zone — before the operating system first invokes it.
 */
interface ExtensionHost : EventPort<ExtensionHandlers>

/** What the operating system's invocations of the extension tell the core. Built only by a composition. */
class ExtensionHandlers @CompositionOnly constructor(
    /** Run one invocation and say how it came out, on the invoking thread. A failure is [ProcessingAnswer.FAILED]. */
    val onProcess: Answered<Unit, ProcessingAnswer>,
    /**
     * The end of an invocation, NOT a kill. Measured on an SE2 (iOS 26.6, 2026-09-23): it arrives about 55 ms after
     * every normal return of [onProcess], and never before the kill that ends a call running past its ~60 s budget —
     * that kill sends nothing at all.
     */
    val onTerminate: Inbound<Unit, Unit>,
)
