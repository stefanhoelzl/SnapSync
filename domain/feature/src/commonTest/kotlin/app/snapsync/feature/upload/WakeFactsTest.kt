package app.snapsync.feature.upload

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.wake.StagedImports
import app.snapsync.domain.wake.UploadsLeft
import app.snapsync.model.AssetRef
import app.snapsync.model.CycleResult
import app.snapsync.model.ImportableAsset
import app.snapsync.scheduling.Pass
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a wake's uploads and the staged downloads came to, as the heartbeat's re-arm reads them. */
class WakeFactsTest {

    @Test
    fun `only processing and a pause leave work, a cut wake does too, and a skipped one contributed nothing`() {
        for (result in CycleResult.all) {
            val expected = when (result) {
                CycleResult.PROCESSING, is CycleResult.Paused -> UploadsLeft.WORK_LEFT
                CycleResult.COMPLETED, CycleResult.FAILED -> UploadsLeft.CAUGHT_UP
                CycleResult.SKIPPED -> UploadsLeft.NOT_CONTRIBUTING
            }
            assertEquals(expected, Pass.Completed(result).uploadsLeft(), "$result")
        }
        assertEquals(UploadsLeft.WORK_LEFT, Pass.Cut.uploadsLeft())
    }

    @Test
    fun `staged downloads still to import are work left`() {
        assertEquals(StagedImports.NONE, emptyList<ImportableAsset>().stagedImports())
        val asset = ImportableAsset(
            AssetRef(DeviceId.parse("11111111-0000-4000-8000-000000000002")!!, AssetId.parse("Q")!!),
            "2026-07-16T12:00:00Z",
        )
        assertEquals(StagedImports.REMAIN, listOf(asset).stagedImports())
    }
}
