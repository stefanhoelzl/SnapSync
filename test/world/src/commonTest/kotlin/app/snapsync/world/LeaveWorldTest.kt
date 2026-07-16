package app.snapsync.world

import app.snapsync.gallery.normalizeAssetId

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `World.leave()` is the faithful in-place clear: it runs the real `onLeaveOrSwitch()` and clears the
 * join, but **retains** imported foreign photos — so re-provisioning the same event still suppresses
 * them (real cross-event dedup), and the own cycle never re-uploads them.
 */
class LeaveWorldTest {

    @Test
    fun leave_keeps_imported_photo_and_clears_the_join() = worldTest {
        val w = World(this)
        val eventId = "E"
        w.provision(eventId)
        w.addForeignDevice("DEV-FOREIGN", eventId, listOf(World.foreignAsset("FQ")))
        w.downloadController.reconcile(eventId)
        w.stageAllDownloads()

        val importedId = normalizeAssetId("imported-DEV-FOREIGN-FQ")
        assertTrue(importedId in w.downloadStore.suppressedLocalIds()) // imported before leave

        w.leave()

        // The join is cleared...
        assertNull(w.configSource.config.value)
        assertNull(w.marker.read())
        // ...but the imported photo survives (terminal / delete-proof).
        assertTrue(w.gallery.current().any { it.assetId == importedId })
        assertTrue(importedId in w.downloadStore.suppressedLocalIds())
    }

    @Test
    fun reprovision_after_leave_still_suppresses_the_import() = worldTest {
        val w = World(this)
        val eventId = "E"
        w.provision(eventId)
        w.addForeignDevice("DEV-FOREIGN", eventId, listOf(World.foreignAsset("FQ")))
        w.downloadController.reconcile(eventId)
        w.stageAllDownloads()
        val importedId = normalizeAssetId("imported-DEV-FOREIGN-FQ")

        w.leave()
        w.provision(eventId) // re-join the same event

        w.runUploadCycle()
        assertFalse(w.platform.created.any { it.assetId == importedId }) // not re-uploaded
    }
}
