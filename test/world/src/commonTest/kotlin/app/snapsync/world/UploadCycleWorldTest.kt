package app.snapsync.world

import app.snapsync.model.LedgerState
import app.snapsync.model.UploadError
import app.snapsync.ports.CycleResult

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
    fun a_reported_removal_marks_the_row_absent() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("A")
        w.runUploadCycle()
        w.platform.completeJob("A-primary.jpg")
        w.runUploadCycle()
        assertEquals(LedgerState.COMPLETED, w.ledgerBackend.get("A-primary.jpg")?.state)

        // The change feed names the departed asset — the precise signal, and the only deletion input
        // (capability `sync-ledger`). The row is MARKED, never deleted: its bytes are still on the
        // backend, so it stays true and keeps suppressing re-upload if the asset comes back.
        w.removeAsset("A")
        w.runUploadCycle()

        val row = w.ledgerBackend.get("A-primary.jpg")
        assertEquals(true, row?.absent, "the departed asset's row is marked")
        assertEquals(LedgerState.COMPLETED, row?.state, "and keeps its upload state")
    }

    @Test
    fun a_removal_the_change_feed_never_reported_leaves_the_row_alone() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("A")
        w.runUploadCycle()
        w.platform.completeJob("A-primary.jpg")
        w.runUploadCycle()

        // The token expires, so the removal is never reported. There is no full-enumeration retain-live
        // backstop any more (capability `sync-ledger`): it was fed the POLICY-ADMITTED set, so it could
        // not tell "gone from the library" from "outside the current capture window", and discarded the
        // rows that suppress re-upload whenever a member raised their cutoff.
        //
        // The accepted cost is exactly this: the asset stays listed for the event's remaining life. Its
        // bytes are still on the backend, so a member still downloads it — the photo simply stays in the
        // event, as it does when a member leaves.
        w.removeAsset("A")
        w.platform.expireToken()
        w.runUploadCycle()

        val row = w.ledgerBackend.get("A-primary.jpg")
        assertEquals(LedgerState.COMPLETED, row?.state, "the row survives — absence is not evidence")
        assertEquals(false, row?.absent, "and it is not marked, because nothing reported it gone")
    }
}
