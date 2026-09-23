package app.snapsync.integration

import app.snapsync.model.LedgerState
import app.snapsync.world.World
import app.snapsync.world.worldTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The ledger is the current membership's share set**, over the real stack (capabilities `sync-ledger`,
 * `upload-state-reconciliation`, `leave-event`): a join loads it from the device's stored-file listing, a
 * leave clears it, and a switch does both — without re-uploading anything the backend already holds, and
 * without a failed listing ever blocking a join.
 *
 * The world's operator `provision()` runs the same composed load `flow/Provision` runs, and its `leave()`
 * clears the upload ledger as the leave use-case does, so these exercise the production decisions.
 */
class ShareSetIntegrationTest {

    /** Upload [assets] to completion: the backend holds their bytes, the ledger their COMPLETED rows. */
    private suspend fun World.shareToCompletion(vararg assets: String) {
        assets.forEach { addOwnAsset(it) }
        runUploadCycle()
        ledgerBackend.requestedKeys().forEach { platform.completeJob(it) }
        platform.drainTerminals()
        runUploadCycle()
    }

    @Test
    fun leave_then_rejoin_the_same_event_re_uploads_nothing() = worldTest {
        val w = World(this)
        w.provision("E")
        w.shareToCompletion("A")
        val jobsBefore = w.platform.created.size

        w.leave()
        assertTrue(w.ledgerBackend.manifestRows().isEmpty(), "the leave cleared the upload ledger")

        w.provision("E")
        assertEquals(LedgerState.COMPLETED, w.ledgerBackend.get("A-primary.jpg")?.state, "the join loaded it back")
        w.runUploadCycle()

        assertEquals(jobsBefore, w.platform.created.size, "nothing the backend already holds is uploaded again")
    }

    @Test
    fun a_switch_replaces_the_share_set_and_re_uploads_nothing_already_stored() = worldTest {
        val w = World(this)
        w.provision("E1")
        w.shareToCompletion("A")
        // Work the first membership recorded but never finished: it belongs to E1's share set only.
        w.addOwnAsset("B")
        w.runUploadCycle()
        val jobsBefore = w.platform.created.size

        w.provision("E2") // a switch: a different event while joined

        assertEquals(LedgerState.COMPLETED, w.ledgerBackend.get("A-primary.jpg")?.state, "stored bytes stay done")
        assertEquals(null, w.ledgerBackend.get("B-primary.jpg"), "E1's unfinished row is not carried over")
        w.runUploadCycle()
        assertTrue(
            w.platform.created.drop(jobsBefore).none { it.filename == "A-primary.jpg" },
            "the stored photo is not uploaded again under the new event",
        )
    }

    @Test
    fun a_re_provision_of_the_joined_event_does_not_reset_its_ledger() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("A")
        w.runUploadCycle() // A is REQUESTED: work in flight
        val before = w.ledgerBackend.manifestRows().associateBy { it.key }

        w.provision("E")

        assertEquals(before, w.ledgerBackend.manifestRows().associateBy { it.key }, "a Stay loads nothing")
    }

    @Test
    fun a_join_whose_listing_fails_still_joins_and_uploads() = worldTest {
        val w = World(this)
        w.provision("E1")
        w.shareToCompletion("A")
        w.leave()

        w.backendOffline = true
        w.provision("E2")
        w.backendOffline = false

        assertEquals("E2", w.configSource.config.value?.eventId, "the failed listing blocked nothing")
        assertTrue(w.ledgerBackend.manifestRows().isEmpty(), "the ledger starts empty rather than stale")
        w.runUploadCycle()
        assertTrue(
            w.platform.created.any { it.filename == "A-primary.jpg" },
            "the cost of the failed load: the photo is uploaded again, idempotently",
        )
    }
}
