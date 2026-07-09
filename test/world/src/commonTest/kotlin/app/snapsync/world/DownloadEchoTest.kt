package app.snapsync.world

import app.snapsync.gallery.normalizeAssetId

import kotlin.test.Test
import kotlin.test.assertTrue

/** Foreign asset flows union → stage → import → suppression; the own cycle then skips the imported asset. */
class DownloadEchoTest {

    @Test
    fun foreign_asset_downloads_imports_and_is_suppressed_from_reupload() = worldTest {
        val w = World()
        val eventId = "E"
        w.provision(eventId)
        w.addForeignDevice("DEV-FOREIGN", eventId, listOf(World.foreignAsset("FQ")))

        w.downloadController.reconcile(eventId)
        assertTrue(w.downloadJobs.pending().isNotEmpty())

        w.stageAllDownloads()
        assertTrue(w.importer.imported.isNotEmpty())

        val importedId = normalizeAssetId("imported-DEV-FOREIGN-FQ")
        assertTrue(w.gallery.current().any { it.assetId == importedId }) // imported into the gallery
        assertTrue(importedId in w.downloadStore.suppressedLocalIds()) // suppression handle recorded

        // The own upload cycle sees the imported asset in discovery but never creates a job for it.
        w.runUploadCycle()
        assertTrue(w.platform.created.none { it.assetId == importedId })
    }

    @Test
    fun import_failure_is_non_terminal_and_retries() = worldTest {
        val w = World()
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
