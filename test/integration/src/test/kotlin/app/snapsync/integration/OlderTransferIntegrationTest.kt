package app.snapsync.integration

import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * **A received photo an earlier build started downloading arrives after the update** (`PENDING_CLEANUPS.md`,
 * "Download rows with an empty event"): a transfer started before transfers named their event names none, and in an
 * encrypted event it is opened with the key of the event joined now — the one it was fetched for — and imported.
 */
class OlderTransferIntegrationTest {

    @Test
    fun an_encrypted_events_download_an_earlier_build_started_is_opened_and_imported_after_the_update() = behaviour {
        given {
            joinedEvent()
            downloadStarted()
            step("the device as an earlier build left it, updated, and opened", act = {
                olderInstall.installedAt(downloadsBehind = 1)
                host.relaunch()
                os.foreground()
            }, until = { settled { true } })
        }

        whenever { downloadSession.finish() }
        then { event<WorldEvent.LibraryAssetCreated> { it.asset == imported() } }
    }
}
