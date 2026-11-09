package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.BackendCall
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * **A join's first read of what the event offers, held open** (capability `receiving-photos`): while the service's
 * answer is on its way, the phone's record of the photos it received turns unreadable, so the join cannot tell which of
 * the event's photos it already holds. It receives nothing on that doubt, and the next opening brings the event's
 * photos.
 */
class HeldUnionIntegrationTest {

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Other members' photos arrive in the photo library automatically",
        scenario = "Joining a live event brings its history",
    )
    fun a_join_whose_received_photos_cannot_be_read_as_the_event_answers_brings_the_photos_at_the_next_opening() =
        behaviour {
            given {
                val event = createdEvent()
                step("$DEV_F shares FA before this member joins", act = {
                    publicBackend.foreignDevice(DEV_F, listOf("FA"), event = event)
                }, until = { settled { true } })
                step("joined, the event's photo list held", act = {
                    backend.hold(BackendCall.UNION)
                    confirmJoin()
                }, until = { event<WorldEvent.BackendHeld> { it.call == BackendCall.UNION } })
            }

            whenever {
                databases.unreadable(DOWNLOADS, on = true)
                backend.release(BackendCall.UNION)
            }
            then {
                never<WorldEvent.DownloadStarted>()
                settled { true }
            }

            whenever {
                databases.unreadable(DOWNLOADS, on = false)
                os.foreground()
            }
            then { event<WorldEvent.DownloadStarted> { it.names("FA") } }
        }

    private fun WorldEvent.DownloadStarted.names(asset: String) = description.split("\n").getOrNull(1) == asset

    private companion object {
        const val DOWNLOADS = "downloads.db"
    }
}
