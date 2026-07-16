package app.snapsync.world

import app.snapsync.engine.LedgerState
import app.snapsync.engine.UploadError
import app.snapsync.upload.CycleResult

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Upload-job lifecycle over the REAL engine + cycle: complete/ack, fail/retry, job-limit, full-enum. */
class UploadCycleWorldTest {

    @Test
    fun complete_deposits_object_and_ledger_records_completed() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("A") // key A-primary.jpg
        assertEquals(CycleResult.COMPLETED, w.runUploadCycle())
        assertTrue("A-primary.jpg" in w.platform.liveJobKeys())

        w.platform.completeJob("A-primary.jpg")
        assertTrue("A-primary.jpg" in w.store.objectsOf(w.ownDeviceId)) // store-direct deposit

        w.runUploadCycle() // acknowledge → ledger COMPLETED
        assertEquals(LedgerState.COMPLETED, w.ledgerBackend.get("A-primary.jpg")?.state)
    }

    @Test
    fun fail_drives_real_retry_with_incremented_attempt() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("A")
        w.runUploadCycle() // REQUESTED, attempt 0
        assertEquals(0, w.ledgerBackend.get("A-primary.jpg")?.attempt)

        w.platform.failJob("A-primary.jpg", UploadError.Network)
        w.runUploadCycle() // retry → REQUESTED, attempt 1
        assertEquals(LedgerState.REQUESTED, w.ledgerBackend.get("A-primary.jpg")?.state)
        assertEquals(1, w.ledgerBackend.get("A-primary.jpg")?.attempt)
    }

    @Test
    fun job_limit_defers_the_cycle_without_advancing() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("A")
        w.addOwnAsset("B")
        w.jobLimit = 1
        assertEquals(CycleResult.PROCESSING, w.runUploadCycle())
        assertEquals(1, w.platform.liveJobKeys().size)
    }

    @Test
    fun full_enumeration_reconciles_removed_asset() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("A")
        w.runUploadCycle()
        w.platform.completeJob("A-primary.jpg")
        w.runUploadCycle()
        assertEquals(LedgerState.COMPLETED, w.ledgerBackend.get("A-primary.jpg")?.state)

        // Remove the asset and force a full enumeration → retainAssets prunes its row.
        w.removeAsset("A")
        w.platform.expireToken()
        w.runUploadCycle()
        assertNull(w.ledgerBackend.get("A-primary.jpg"))
    }
}
