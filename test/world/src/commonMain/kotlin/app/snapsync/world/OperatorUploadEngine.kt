package app.snapsync.world

import app.snapsync.feature.upload.AppUploadMechanism
import app.snapsync.feature.upload.WalkOutcome
import app.snapsync.model.CycleResult
import kotlinx.coroutines.CompletableDeferred

/**
 * The world's app-driven [AppUploadMechanism]: its units are inert, because **the operator is the engine** — nothing
 * uploads on its own in the world (`docs/testing.md`), and a cycle happens only when the operator invokes it.
 * The composed tail runner still drives these units from every wake the world's OS entries deliver, so what is counted
 * here is what the runner asked of the uploader: a test reads which units a wake reached, in the real order. Its
 * transfer session's background events are the upload session mock's (`World.appUpload`).
 */
class OperatorUploadEngine : AppUploadMechanism {
    /** How many top-ups (②) the tail asked for. */
    var topUps: Int = 0
        private set

    /** How many walks (③) — including a selection change's own work — the tail asked for. */
    var walks: Int = 0
        private set

    /** What each top-up answers — the operator's lever for a truncated or declining pass. */
    var topUpResult: CycleResult = CycleResult.COMPLETED

    /**
     * Operator lever: park the next unit until the gate completes — how a test holds a tail in flight to deliver an
     * expiry, or a join, while it runs. Consumed by the unit it parks.
     */
    @kotlin.concurrent.Volatile
    var nextUnitGate: CompletableDeferred<Unit>? = null

    override suspend fun topUp(stopRequested: () -> Boolean): CycleResult {
        topUps++
        park()
        return topUpResult
    }

    override suspend fun walkAndPublish(stopRequested: () -> Boolean): WalkOutcome {
        walks++
        park()
        return if (stopRequested()) WalkOutcome.Abandoned else WalkOutcome.Walked(CycleResult.COMPLETED, addedRows = false)
    }

    override suspend fun cancelTransfers() = Unit

    private suspend fun park() {
        val gate = nextUnitGate ?: return
        nextUnitGate = null
        gate.await()
    }
}

