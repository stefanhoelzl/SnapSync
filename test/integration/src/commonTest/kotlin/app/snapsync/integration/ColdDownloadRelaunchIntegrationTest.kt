package app.snapsync.integration

import app.snapsync.world.World
import app.snapsync.world.worldTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A process the OS relaunches **only** to deliver download-session events (capability `photo-download`, "A
 * staged resource reaches the controller on every entry point").
 *
 * That process builds the download jobs and nothing else: the shell's relaunch entry point adopts the
 * session's events, and no UI host, flow or controller is ever touched. The staging callback used to be a
 * `var` the composition assigned while building the controller, so on exactly this path it was still `null`
 * and every staged resource was dropped without a log line — the bytes orphaned in staging, the OS handler
 * released at once with nothing to wait for.
 *
 * Two worlds model the two processes. The first starts the transfers the way a foreground reconcile does, so
 * the transfer descriptions and the planned rows are the real ones. The second is the relaunched process: it
 * inherits the store's rows (on device they live in the App-Group database) and receives the completions for
 * transfers it never started.
 */
class ColdDownloadRelaunchIntegrationTest {

    private val foreignDevice = "DEV-F"
    private val foreignAsset = "FQ"

    @Test
    fun a_download_relaunch_imports_what_the_session_staged() = worldTest {
        // The process that started the transfers, then died.
        val before = World(this)
        before.provision("E")
        before.addForeignDevice(foreignDevice, "E", listOf(World.foreignAsset(foreignAsset)))
        before.downloadController.reconcile("E")
        val descriptions = before.downloadTransport!!.inFlight().map { it.description }
        val inherited = before.downloadStore.pendingDownloads()
        assertTrue(descriptions.isNotEmpty(), "precondition: the first process started transfers")

        // The relaunched process: same membership and store rows, and nothing built but what the shell's
        // relaunch entry point touches.
        val after = World(this)
        after.provision("E")
        inherited.forEach { p ->
            after.downloadStore.plan(p.ref, World.DEFAULT_DATE, listOf(p.resource))
            after.downloadStore.markEnqueued(p.ref, p.resource.resourceKey)
        }
        after.core.downloadJobs.adoptBackgroundEvents {}

        descriptions.forEach { after.downloadTransport!!.finish(it) }
        after.core.downloadJobs.awaitOutstandingImports()

        assertEquals(
            emptyList(),
            after.downloadStore.pendingDownloads(),
            "every resource the session delivered was recorded as staged",
        )
        assertTrue(
            after.gallery.current().any { it.assetId.contains(foreignAsset) },
            "the staged asset was imported into the library",
        )
    }
}
