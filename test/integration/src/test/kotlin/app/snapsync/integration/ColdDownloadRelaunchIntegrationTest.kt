package app.snapsync.integration

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonArray
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
 * Over the protocol: the first app starts the transfers the way a foreground reconcile does, so the transfers
 * and the planned rows are the real ones. `/device/relaunch` is its death; the relaunched app inherits the
 * durable rows and the operating system's download session, and receives the completions for transfers it
 * never started — through `onBackgroundTransfers` on the download session, with nothing reading its state (so
 * nothing touching its host) until the assertions.
 */
class ColdDownloadRelaunchIntegrationTest {
    @Test
    fun a_download_relaunch_imports_what_the_session_staged() = rigTest {
        // The process that started the transfers, then died.
        createAndJoin()
        foreignDevice("DEV-F", "FQ")
        val before = libraryTotal()
        reconcile()

        device("relaunch")

        // The relaunched process: nothing built but what the shell's relaunch entry point touches. The OS hands
        // it the download session's events. The entry holds the OS's completion handler until the session reports
        // its events drained, so it is answered concurrently with the OS delivering the transfers — which reach
        // the relaunched app once the entry has realized its transport (until then a delivery finds no session
        // to deliver into, and is simply repeated).
        coroutineScope {
            val wake = async { os("app", "onBackgroundTransfers", DOWNLOAD_SESSION) }
            eventually(read = { stage(); libraryTotal() }) { it == before + 1 } // the staged asset was imported
            // The world's session never reports its events drained, so its handler would be held until the
            // background time's expiry;
            // the OS's side of the wake is not what this test is about.
            wake.cancel()
        }

        assertEquals(before + 1, libraryTotal(), "the staged asset was imported into the library, once")
        assertTrue(stagingFiles().isEmpty(), "every resource the session delivered was staged, imported and released")
    }

    private suspend fun Rig.stagingFiles() = deviceJson("staging").getValue("files").jsonArray

    private companion object {
        /** Any transfer channel that is not the upload session's is the download session's. */
        const val DOWNLOAD_SESSION = "app.snapsync.download.session"
    }
}
