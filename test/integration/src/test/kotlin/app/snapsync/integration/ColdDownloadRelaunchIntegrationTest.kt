package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.TransferEnd
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * A process the OS relaunches **only** to deliver download-session events.
 *
 * That process builds the download jobs and nothing else: the shell's relaunch entry point adopts the
 * session's events, and no UI host, flow or controller is ever touched. The staging callback used to be a
 * `var` the composition assigned while building the controller, so on exactly this path it was still `null`
 * and every staged resource was dropped without a log line — the bytes orphaned in staging, the OS handler
 * released at once with nothing to wait for.
 *
 * Over the protocol: the first app starts the transfers the way a foreground reconcile does, so the transfers
 * and the planned rows are the real ones. A background launch is its death and a relaunch with no scene; the
 * relaunched app inherits the durable rows and the operating system's download session, and receives the
 * completions for transfers it never started — through `onBackgroundTransfers` on the download session, with
 * nothing touching its host.
 */
@Verifies(spec = "delivery", requirement = "Photos travel without the app being opened")
class ColdDownloadRelaunchIntegrationTest {
    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Photos travel without the app being opened",
        scenario = "Downloads finish in the background",
    )
    fun a_download_relaunch_imports_what_the_session_staged() = behaviour {
        // The process that started the transfers, then died; the OS relaunches it with no scene.
        val before = given {
            joinedEvent()
            downloadStarted().also { backgroundLaunch() }
        }
        val photos = before.state.libraryPhotos.orEmpty().size
        val storage = checkNotNull(before.state.appStorageBytes)

        // The relaunched process: nothing built but what the shell's relaunch entry point touches. The OS hands it the
        // download session's events, which bring the session up, and delivers the transfer it never started. The world's
        // session never reports its events drained, so the OS's side of the wake is not what this test is about.
        whenever {
            os.backgroundTransfers(DOWNLOAD_SESSION)
            downloadSession.finish()
        }
        then {
            event<WorldEvent.DownloadEnded> { it.outcome.how == TransferEnd.How.DELIVERED }
            // the staged asset was imported into the library, once
            times(1) { event<WorldEvent.LibraryAssetCreated> { it.asset == imported() } }
            never<WorldEvent.UiChanged>() // nothing touched its host
            // every resource the session delivered was staged, imported and released: the app holds no more than before
            settled {
                it.state.libraryPhotos.orEmpty().size == photos + 1 && checkNotNull(it.state.appStorageBytes) <= storage
            }
        }
    }
}
