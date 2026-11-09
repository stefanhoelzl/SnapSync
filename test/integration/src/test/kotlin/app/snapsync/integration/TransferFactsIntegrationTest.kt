package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * **What a finished download's facts let through** (capability `delivery`), over the real stack: the operating system
 * reports a transfer's status and its lengths, and only positive evidence of a broken body — here a body short of the
 * length it declared — keeps it from the library; a transfer that reports no status but brought everything it promised
 * is imported.
 */
class TransferFactsIntegrationTest {

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Download problems delay photos, never corrupt or lose them",
        scenario = "A broken download is retried",
    )
    fun a_download_short_of_the_length_it_declared_imports_nothing_and_is_fetched_again() = behaviour {
        given {
            joinedEvent()
            downloadStarted()
        }

        whenever { downloadSession.finish(status = OK, expected = DECLARED, received = DECLARED / 2) }
        then {
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

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Download problems delay photos, never corrupt or lose them",
    )
    fun a_download_with_no_status_that_brought_its_whole_length_is_imported() = behaviour {
        given {
            joinedEvent()
            downloadStarted()
        }

        whenever { downloadSession.finish(status = null, expected = DECLARED, received = DECLARED) }
        then { event<WorldEvent.LibraryAssetCreated> { it.asset == imported() } }
    }

    private companion object {
        const val OK = 200

        /** The length a transfer declares, in bytes. */
        const val DECLARED = 4_096L
    }
}
