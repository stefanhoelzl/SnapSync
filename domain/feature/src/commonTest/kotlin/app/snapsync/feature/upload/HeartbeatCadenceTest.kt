package app.snapsync.feature.upload

import app.snapsync.model.CycleResult
import app.snapsync.model.WakeCadence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The heartbeat's cadence rule, row by row (capability `receiving-photos`; decision record
 * `changes/timely-background-receiving`, D1): not joined → none; work left → busy; a full-grant contributor with no
 * library-change wake before the end → busy; every other joined device → idle.
 */
class HeartbeatCadenceTest {

    private val open = CadenceFacts(joined = true, ended = false, shares = true, fullGrant = true, osUploaderConfirmed = false)

    private fun cadence(
        facts: CadenceFacts = open,
        leftWork: Boolean = false,
        importsRemain: Boolean = false,
        contributes: Boolean = true,
        libraryWatched: Boolean = false,
    ) = heartbeatCadence(facts, leftWork, importsRemain, contributes, libraryWatched)

    @Test
    fun `a device that is not joined keeps no heartbeat whatever is left`() {
        assertNull(cadence(facts = open.copy(joined = false), leftWork = true, importsRemain = true))
    }

    @Test
    fun `work left keeps the heartbeat busy for every kind of member`() {
        for (facts in listOf(open, open.copy(fullGrant = false), open.copy(ended = true), open.copy(osUploaderConfirmed = true))) {
            assertEquals(WakeCadence.BUSY, cadence(facts = facts, leftWork = true), "uploads left, $facts")
            assertEquals(WakeCadence.BUSY, cadence(facts = facts, importsRemain = true, contributes = false), "imports left, $facts")
        }
    }

    @Test
    fun `a full-grant contributor with no library-change wake looks for its own photos until the end`() {
        assertEquals(WakeCadence.BUSY, cadence())
        assertEquals(WakeCadence.IDLE, cadence(facts = open.copy(ended = true)), "after the end nothing new can enter")
    }

    @Test
    fun `a library-change wake or a confirmed OS uploader lets a caught-up contributor idle`() {
        assertEquals(WakeCadence.IDLE, cadence(libraryWatched = true), "Android's content-URI watch")
        assertEquals(WakeCadence.IDLE, cadence(facts = open.copy(osUploaderConfirmed = true)), "iOS's extension")
    }

    @Test
    fun `receive-only held back and partial-grant members idle`() {
        assertEquals(WakeCadence.IDLE, cadence(contributes = false), "receive-only or held back: the cycle declined")
        assertEquals(WakeCadence.IDLE, cadence(facts = open.copy(shares = false)), "receive-only, whatever the cycle said")
        assertEquals(WakeCadence.IDLE, cadence(facts = open.copy(fullGrant = false)), "a camera photo never joins a selection")
    }

    @Test
    fun `only processing and a pause leave work`() {
        assertTrue(CycleResult.PROCESSING.leftWork)
        for (result in listOf(CycleResult.COMPLETED, CycleResult.FAILED, CycleResult.SKIPPED)) assertFalse(result.leftWork)
        for (result in CycleResult.all.filterIsInstance<CycleResult.Paused>()) assertTrue(result.leftWork)
    }
}
