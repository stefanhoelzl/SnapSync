package app.snapsync.feature.upload

import app.snapsync.domain.wake.StagedImports
import app.snapsync.domain.wake.UploadsLeft
import app.snapsync.model.CycleResult
import app.snapsync.model.ImportableAsset
import app.snapsync.scheduling.Pass

// The heartbeat's re-arm (`RearmHeartbeat`) reads what a wake's uploads and the staged downloads came to in the
// domain's words; the uploads (4.4b) and the downloads (4.5) still answer in the old zones'. MIGRATING: each moves with
// its concept.

/**
 * What a wake's own uploads came to, as the heartbeat reads it. Exhaustive, so a new [CycleResult] is a compile error
 * here rather than a cadence nobody chose. A cut pass says nothing about whether work remains, so it does.
 */
fun Pass<CycleResult>.uploadsLeft(): UploadsLeft = when (this) {
    is Pass.Completed -> result.uploadsLeft()
    Pass.Cut -> UploadsLeft.WORK_LEFT
}

private fun CycleResult.uploadsLeft(): UploadsLeft = when (this) {
    CycleResult.PROCESSING, is CycleResult.Paused -> UploadsLeft.WORK_LEFT
    CycleResult.COMPLETED, CycleResult.FAILED -> UploadsLeft.CAUGHT_UP
    CycleResult.SKIPPED -> UploadsLeft.NOT_CONTRIBUTING
}

/** Whether these staged downloads still wait to be imported — work left, which keeps the heartbeat busy. */
fun List<ImportableAsset>.stagedImports(): StagedImports = when (isEmpty()) {
    true -> StagedImports.NONE
    false -> StagedImports.REMAIN
}
