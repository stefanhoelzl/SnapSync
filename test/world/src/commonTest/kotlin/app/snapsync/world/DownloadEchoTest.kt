package app.snapsync.world

import app.snapsync.ports.TransferOutcome
import app.snapsync.model.normalizeAssetId

import kotlin.test.Test
import kotlin.test.assertTrue

/** Foreign asset flows union → stage → import → suppression; the own cycle then skips the imported asset. */
class DownloadEchoTest {

    @Test
    fun foreign_asset_downloads_imports_and_is_suppressed_from_reupload() = worldTest {
        val w = World(this)
        val eventId = "E"
        w.provision(eventId)
        w.addForeignDevice("DEV-FOREIGN", eventId, listOf(World.foreignAsset("FQ")))

        w.downloadController.reconcile(eventId)
        // Asked of the transport, not the jobs: the real `QueuedPhotoDownloadJobs` has no inspection seam,
        // and a started transfer is the stronger claim — it proves the real window and URL guard passed it
        // through to the edge, rather than that a fake recorded it.
        assertTrue(w.downloadTransport?.inFlight()?.isNotEmpty() == true)

        w.stageAllDownloads()
        assertTrue(w.importer.imported.isNotEmpty())

        val importedId = normalizeAssetId("imported-DEV-FOREIGN-FQ")
        assertTrue(w.gallery.current().any { it.assetId == importedId }) // imported into the gallery
        assertTrue(importedId in w.downloadStore.suppressedLocalIds()) // suppression handle recorded

        // The own upload cycle sees the imported asset in discovery but never creates a job for it.
        w.runUploadCycle()
        assertTrue(w.platform.created.none { it.assetId == importedId })
    }

    /**
     * The end-to-end shape of the bug, which no unit test of the predicate can show: a `502` arrives as a
     * *successful* transfer of an error body. Staged, it would become the download store's truth — the
     * import would fail against it on every reconcile forever, and the transfer would never re-run, because
     * the resource is recorded as staged. The photo would never arrive and nothing would say so.
     */
    @Test
    fun a_rejected_transfer_stages_nothing_and_stays_pending_for_retry() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addForeignDevice("DEV-F", "E", listOf(World.foreignAsset("FQ")))
        w.downloadController.reconcile("E")

        w.stageAllDownloads(TransferOutcome(statusCode = 502, expectedBytes = -1L, receivedBytes = 137L))

        assertTrue(w.importer.imported.isEmpty(), "an error body must never be imported")
        // Not terminal: the resource stayed un-staged, so a later reconcile re-downloads it. That is the
        // world's existing pending-for-retry posture, reached without any DownloadError type.
        w.downloadController.reconcile("E")
        w.stageAllDownloads() // healthy this time
        assertTrue(w.importer.imported.isNotEmpty(), "the retry must import — the bytes were re-fetched")
    }

    @Test
    fun import_failure_is_non_terminal_and_retries() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addForeignDevice("DF", "E", listOf(World.foreignAsset("FQ")))
        w.failNextImport()

        w.downloadController.reconcile("E")
        w.stageAllDownloads() // import fails (non-terminal)
        assertTrue(w.importer.imported.isEmpty())

        w.downloadController.importReady() // retries → succeeds
        assertTrue(w.importer.imported.isNotEmpty())
    }
}
