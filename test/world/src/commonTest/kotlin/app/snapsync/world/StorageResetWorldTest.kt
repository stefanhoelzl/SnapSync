package app.snapsync.world

import app.snapsync.engine.LedgerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end over the REAL stack (reconciler → cycle → manifest → union): a storage reset followed by
 * a re-join re-uploads everything, instead of hanging (capability `event-rejoin-reconciliation`). This
 * test would FAIL before the empty-listing guard was removed — the reconcile would defer forever on the
 * empty listing while the ledger still held `COMPLETED` rows.
 */
class StorageResetWorldTest {

    private suspend fun World.backUpAndAck(key: String) {
        runUploadCycle() // discover → create job
        platform.completeJob(key) // store-direct deposit
        runUploadCycle() // ack → COMPLETED + manifest written
    }

    @Test
    fun storage_reset_then_new_event_re_uploads_everything() = worldTest {
        val w = World(this)
        w.provision("E1")
        w.addOwnAsset("A") // key A-primary.jpg
        w.backUpAndAck("A-primary.jpg")

        // Uploaded: object stored, ledger COMPLETED, and the event union sees it.
        assertTrue("A-primary.jpg" in w.store.objectsOf(w.ownDeviceId))
        assertEquals(LedgerState.COMPLETED, w.ledgerBackend.get("A-primary.jpg")?.state)
        assertEquals(1, w.store.union("E1")?.size)

        // Operator wipes storage: the byte partition is gone, but the ledger still says COMPLETED.
        w.store.wipeBytes(w.ownDeviceId)
        assertTrue(w.store.objectsOf(w.ownDeviceId).isEmpty())
        assertEquals(0, w.store.union("E1")?.size) // union drops it (bytes missing) → "Syncing"

        // Re-join a NEW event. The reconcile sees the empty listing and re-baselines the ledger to empty
        // (instead of deferring), so the full re-enumeration re-discovers and re-uploads A.
        w.provision("E2")
        w.runUploadCycle() // reconcile re-baselines + cycle re-creates the job
        assertTrue("A-primary.jpg" in w.platform.liveJobKeys(), "A must be re-enqueued for upload")

        w.platform.completeJob("A-primary.jpg")
        w.runUploadCycle() // ack + manifest for E2

        // Healed: object re-deposited, ledger COMPLETED again, and the new event's union sees it.
        assertTrue("A-primary.jpg" in w.store.objectsOf(w.ownDeviceId))
        assertEquals(LedgerState.COMPLETED, w.ledgerBackend.get("A-primary.jpg")?.state)
        assertEquals(1, w.store.union("E2")?.size)
    }
}
