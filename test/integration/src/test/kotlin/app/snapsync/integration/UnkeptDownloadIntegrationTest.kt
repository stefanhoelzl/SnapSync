package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.FileOp
import app.snapsync.levers.FilePlace
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * **A finished download whose bytes the disk will not take** (capability `delivery`): the operating system hands the
 * app a whole transfer, but moving it into the app's staging fails, as a full or failing disk answers. Nothing reaches
 * the library, and the photo is downloaded again at the next opening and arrives.
 */
class UnkeptDownloadIntegrationTest {

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Download problems delay photos, never corrupt or lose them",
    )
    fun a_download_the_disk_would_not_take_is_fetched_again_and_arrives() = behaviour {
        given {
            joinedEvent()
            downloadStarted()
        }

        whenever {
            files.fail(FilePlace.DOWNLOAD_STAGING, FileOp.MOVE)
            downloadSession.finish()
        }
        then {
            event<WorldEvent.FilesFaulted> { it.place == FilePlace.DOWNLOAD_STAGING && it.op == FileOp.MOVE }
            never<WorldEvent.LibraryAssetCreated>()
            settled { true }
        }

        whenever { os.foreground() }
        then {
            event<WorldEvent.DownloadStarted>()
            settled { true }
        }
        whenever { downloadSession.finish() }
        then { event<WorldEvent.LibraryAssetCreated> { it.asset == imported() } }
    }
}
