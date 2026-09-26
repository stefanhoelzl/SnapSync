package app.snapsync.feature.upload

import app.snapsync.model.CycleResult

/**
 * The app's uploader as the shell supplies it: the transport-bound half of the app-driven tier (capability
 * `background-upload`) — its two tail units and its session.
 *
 * It holds **no trigger, no OS completion handler and no heartbeat**. Which wake runs what, when the heartbeat — the
 * core's, over the `Wake` port — is re-armed and how a wake's handler is held are the core's: the tail runner and the
 * entry ports' handlers (decision record `changes/own-work-per-wake`, D1 and D5) — so a mechanism cannot
 * fail to release a handler, and cannot run a unit the tail did not ask for. Both units pass through the shared upload
 * cycle's entry gate, which decides whether this
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

    /** Cancel every in-flight transfer and delete its staged file (a leave). Touches no ledger row. */
    suspend fun cancelTransfers()
}

/**
 * What the app uploader's transport tells the core — each one call from the upload port's handlers, which the
 * composition builds and the host zone registers.
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
