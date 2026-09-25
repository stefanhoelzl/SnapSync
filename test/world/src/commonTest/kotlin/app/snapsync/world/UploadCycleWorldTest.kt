package app.snapsync.world

import app.snapsync.model.LedgerState
import app.snapsync.model.captureCutoff
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
    fun fail_drives_real_retry_that_re_creates_the_job() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("A")
        w.runUploadCycle() // REQUESTED, first creation
        assertEquals(1, w.platform.created.count { it.filename == "A-primary.jpg" })

        w.platform.failJob("A-primary.jpg", UploadError.Network)
        w.runUploadCycle() // first failure → the single free retry re-points the job, still REQUESTED
        assertEquals(LedgerState.REQUESTED, w.ledgerBackend.get("A-primary.jpg")?.state)
        assertEquals(1, w.platform.created.count { it.filename == "A-primary.jpg" })

        w.platform.failJob("A-primary.jpg", UploadError.Network)
        w.runUploadCycle() // retry-spent → back to DISCOVERED, then re-created in the same cycle
        assertEquals(LedgerState.REQUESTED, w.ledgerBackend.get("A-primary.jpg")?.state)
        assertEquals(2, w.platform.created.count { it.filename == "A-primary.jpg" })
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
    fun an_unreadable_walk_deletes_nothing_and_the_next_readable_walk_does() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("A")
        w.runUploadCycle()
        w.platform.completeJob("A-primary.jpg")
        w.runUploadCycle()

        // The asset leaves, but the walk that follows cannot read the library: an empty answer that is not
        // authoritative is no evidence (capability `photo-sharing`), so it must cost an idle pass, not a row.
        w.removeAsset("A")
        w.discovery.makeWalkUnreadable()
        w.runUploadCycle()
        assertEquals(LedgerState.COMPLETED, w.ledgerBackend.get("A-primary.jpg")?.state, "nothing deleted")

        w.runUploadCycle()
        assertNull(w.ledgerBackend.get("A-primary.jpg"), "the next readable walk is the evidence")
    }

    @Test
    fun a_raised_cutoff_keeps_the_rows_of_photos_still_in_the_library() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("A") // captured at DEFAULT_DATE, inside the default window
        w.runUploadCycle()
        w.platform.completeJob("A-primary.jpg")
        w.runUploadCycle()

        // The member narrows past the photo. The walk no longer returns it — but it is outside the walk's
        // window now, so its absence is no evidence, and the row that suppresses re-upload must survive.
        w.provision("E", minPhotoDate = captureCutoff("2026-07-01T00:00:00Z"))
        w.runUploadCycle()

        assertEquals(LedgerState.COMPLETED, w.ledgerBackend.get("A-primary.jpg")?.state)
    }

    @Test
    fun an_asset_moved_into_a_denylisted_album_is_still_present() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("A")
        w.runUploadCycle()
        w.platform.completeJob("A-primary.jpg")
        w.runUploadCycle()

        // Still in the library; the admission now excludes it, the walk still returns it. A walk narrowed by
        // the whole admission would make it look departed — the world must not do what a device cannot.
        w.placeInAlbum("WhatsApp", "A")
        w.runUploadCycle()

        assertEquals(LedgerState.COMPLETED, w.ledgerBackend.get("A-primary.jpg")?.state)
    }

    @Test
    fun a_removal_no_signal_ever_reported_is_still_retracted_by_the_walk() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("A")
        w.runUploadCycle()
        w.platform.completeJob("A-primary.jpg")
        w.runUploadCycle()

        // No removal signal is needed any more (capability `photo-sharing`, "Deletion is a presence diff over an
        // authoritative walk"): a full enumeration that no longer returns an in-window asset IS the evidence.
        // Under the change feed this deletion was lost for the event's remaining life once the token expired.
        w.removeAsset("A")
        w.runUploadCycle()

        assertNull(w.ledgerBackend.get("A-primary.jpg"), "the departed asset's row is deleted")
    }
}
