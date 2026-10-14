package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.NOT_AN_IMAGE
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import java.util.Base64
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A received file the phone's photo library cannot open: the library takes it and saves nothing, and the app neither
 * retries it nor waits on it — the event's other photos arrive and the member is up to date without it.
 */
class UnopenableReceivedFileIntegrationTest {

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Other members' photos arrive in the photo library automatically",
        scenario = "A photo the phone cannot open",
    )
    fun a_received_file_the_library_cannot_open_is_not_saved_and_holds_up_nothing() = behaviour {
        val (readable, unreadable) = given {
            joinedEvent()
            lateinit var fq: Seen<WorldEvent.DownloadStarted>
            lateinit var fr: Seen<WorldEvent.DownloadStarted>
            step(
                "a member shares FQ and FR",
                act = {
                    publicBackend.foreignDevice(DEV_F, listOf("FQ", "FR"))
                    os.foreground()
                },
                until = {
                    fq = event { assetOf(it) == "FQ" }
                    fr = event { assetOf(it) == "FR" }
                    settled { true }
                },
            )
            fq.value.description to fr.value.description
        }

        // What the transfers bring: FQ an ordinary photo, FR a file no photo library can decode.
        whenever {
            val notAnImage = Base64.getEncoder().encodeToString(NOT_AN_IMAGE)
            downloadSession.finish(description = unreadable, base64Bytes = notAnImage)
            downloadSession.finish(description = readable)
        }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == "imported-$DEV_F-FQ" }
            never<WorldEvent.LibraryAssetCreated> { it.asset.startsWith("imported-$DEV_F-FR") }
        }

        // The status catches up on its own timer; the file that will never arrive is not waited on.
        whenever { advance(2.seconds) }
        then {
            screen<Layer.Joined> { it.health == SyncHealth.InSync }
            never<WorldEvent.LibraryAssetCreated>()
        }
    }

    /** The source asset a download's description names: its fields are device, asset, resource and event, a line each. */
    private fun assetOf(started: WorldEvent.DownloadStarted): String? = started.description.lines().getOrNull(1)
}
