package app.snapsync.feature.upload

import app.snapsync.domain.wake.UploadsLeft
import app.snapsync.model.CycleResult
import app.snapsync.scheduling.Pass
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a wake's uploads came to, as the heartbeat's re-arm reads them. */
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
}
