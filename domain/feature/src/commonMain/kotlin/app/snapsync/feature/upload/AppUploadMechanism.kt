package app.snapsync.feature.upload

import app.snapsync.ports.BackgroundScheduler
import app.snapsync.ports.CycleResult

/**
 * The app's uploader as the shell supplies it: the transport-bound half of the app-driven tier (capability
 * `background-upload`) — its two tail units, its heartbeat, and its session.
 *
 * It holds **no trigger and no OS completion handler**. Which wake runs what, when the heartbeat is re-armed and how a
 * wake's handler is held are the core's — the tail runner and the inbound port's implementation (decision record
 * `changes/own-work-per-wake`, D1 and D5) — so a mechanism cannot fail to release a handler, and cannot run a unit the
 * tail did not ask for. Both units pass through the shared upload cycle's entry gate, which decides whether this
 * process may create (capability `background-upload`), so a unit a declining membership reaches still returns.
 */
interface AppUploadMechanism {
    /** The tail's ② — the top-up from the ledger; `stopRequested` is checked between two job creations. */
    suspend fun topUp(stopRequested: () -> Boolean): CycleResult

    /**
     * The tail's ③ — the walk and the manifest publish, abandoned on a stop (capability `sync-status`, "The discovery
     * walk is atomic under a stop"). Also a selection change's own work under a partial grant, where the walk is the
     * selection snapshot.
     */
    suspend fun walkAndPublish(stopRequested: () -> Boolean): WalkOutcome

    /** The `BGProcessingTask` heartbeat: the tail runner re-arms it, and a disarm cancels it. */
    val heartbeat: BackgroundScheduler

    /** Cancel every in-flight transfer and delete its staged file (a leave). Touches no ledger row. */
    suspend fun cancelTransfers()

    /**
     * Bring the background transfer session up, so the operating system delivers the events it holds for it — the
     * completions the transport records as they arrive, then the report that every event was delivered
     * ([AppUploadEvents.eventsDrained]).
     */
    fun reattach()
}

/**
 * What the app uploader's transport tells the core. The root binds each to one call into the composed core at
 * construction (`docs/architecture.md`, "Callbacks are bound at construction"): the transport exists before the
 * core that answers it, which is the construction cycle this interface crosses.
 */
interface AppUploadEvents {
    /**
     * A transfer reached its terminal outcome — **already recorded** by the transport's guarded write — and freed a
     * slot. The core requests the tail's top-up alone, and only while the app may create (capability
     * `background-upload`, "The delegate records the terminal fact before it returns").
     */
    fun uploadCompleted()

    /**
     * The background session reported every event it had delivered (`urlSessionDidFinishEvents`): the relaunch's own
     * work — recording the terminals — is done, so the core releases the handlers the wake handed over.
     */
    fun eventsDrained()
}
