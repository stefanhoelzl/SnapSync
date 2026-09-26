package app.snapsync.world

import app.snapsync.model.AssetId
import app.snapsync.model.CycleResult
import app.snapsync.model.GalleryAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `World.relaunch()` is process death and a cold launch (`docs/testing.md`, "The world relaunches
 * its app over its durable state"). These pin the classification the relaunch rests on: every durable cell
 * survives into the new app, and every piece of process memory is fresh. A cell classified wrongly would make
 * every relaunch test lie, in the direction of whichever way it was wrong.
 */
class RelaunchWorldTest {

    @Test
    fun the_durable_state_survives_a_relaunch() = worldTest {
        val w = World(this)
        w.permission.set(GalleryAccess.GRANTED)
        val event = w.provisionMinted()
        w.addOwnAsset("A")
        assertEquals(CycleResult.COMPLETED, w.runUploadCycle())
        w.platform.completeJob(w.platform.liveJobKeys().single())
        w.addForeignDeviceMinted("DEV-F", listOf(World.foreignAsset("FQ")), event)
        w.downloadController.reconcile(event)
        val session = assertNotNull(w.downloadTransport).inFlight().map { it.description }
        assertTrue(session.isNotEmpty(), "precondition: this launch started a download")
        val rows = w.ledgerBackend.manifestRows()

        w.relaunch()

        assertEquals(event, w.configSource.config.value?.eventId, "the membership (an App-Group file) survives")
        assertEquals(rows, w.ledgerBackend.manifestRows(), "the ledger (an App-Group database) survives")
        assertTrue(w.downloadStore.pendingDownloads().isNotEmpty(), "the download store survives")
        assertTrue(w.gallery.current().any { it.assetId == AssetId("A") }, "the photo library survives")
        assertEquals(GalleryAccess.GRANTED, w.permission.permission.value, "the grant survives")
        val objects = w.neutral.objectsOf(w.ownDeviceId)
        assertTrue(objects is Answer.Available && objects.value.isNotEmpty(), "the backend survives")

        // The OS download session survives: the relaunched app's transport receives the dead process's transfers.
        w.download.handBack(bareCompletion {})
        val adopted = assertNotNull(w.downloadTransport, "the relaunched app realized a transport").inFlight().map { it.description }
        assertEquals(session, adopted, "the relaunched app's transport holds the dead process's transfers")
        session.forEach { assertNotNull(w.downloadTransport).finish(it) }
        w.core.downloadJobs.awaitOutstandingStagings()
        assertTrue(w.downloadStore.pendingDownloads().isEmpty(), "the relaunched app received the session's transfers")
    }

    @Test
    fun process_memory_is_fresh_after_a_relaunch() = worldTest {
        val w = World(this)
        val core = w.core
        val host = w.statusHost
        val cycle = w.cycle
        w.core.versionGate.refused("9.9")

        w.relaunch()

        assertNotSame(core, w.core, "a new core")
        assertNotSame(host, w.statusHost, "a new status host")
        assertNotSame(cycle, w.cycle, "a new cycle, over the new core")
        assertNull(w.core.versionGate.refusal.value, "the version gate is process memory")
        assertNull(w.downloadTransport, "no transport until the new app realizes one")
    }
}

/** An operating-system completion handler with no expiry signal, as a background-session relaunch hands one over. */
private fun bareCompletion(onComplete: () -> Unit): app.snapsync.ports.Completion =
    object : app.snapsync.ports.Completion {
        override fun complete() = onComplete()
        override fun onExpired(action: () -> Unit) = Unit
    }
