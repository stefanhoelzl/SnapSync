package app.snapsync.mock

import app.snapsync.feature.upload.AppUploadMechanism
import app.snapsync.feature.upload.WalkOutcome
import app.snapsync.model.CycleResult

/**
 * The app-driven uploader where **the operator is the engine**: its units do nothing, so nothing uploads on its own,
 * and a cycle runs when the operator invokes the upload extension. The tail runner still reaches it from every wake the
 * operator delivers, as on a device.
 *
 * What the JVM root's app composes, and what a launch-time adapters composes wherever the app's transfer session is the
 * upload-session MOCK — a session that creates nothing, so an uploader over it would only fail (`docs/testing.md`,
 * "Launch-time adapters"). Every upload then goes through the cycle over the upload-job queue.
 */
object OperatorDrivenUploads : AppUploadMechanism {
    override suspend fun topUp(stopRequested: () -> Boolean): CycleResult = CycleResult.COMPLETED

    override suspend fun walkAndPublish(stopRequested: () -> Boolean): WalkOutcome =
        WalkOutcome.Walked(CycleResult.COMPLETED, addedRows = false)

    override suspend fun cancelTransfers() = Unit
}
