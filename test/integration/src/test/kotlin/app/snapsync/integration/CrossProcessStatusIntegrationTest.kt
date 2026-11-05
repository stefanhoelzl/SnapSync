package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * What the upload extension — another process — records reaches the status line of the app in front, with nothing in
 * the app reading on its own account: no foreground entry, no tap, only the time the spec allows ("within about two
 * seconds", requirement "The status stays current while the app is open").
 *
 * The JVM host runs the app and the extension as two processes over the same databases, each with its own process's
 * view of them, so the extension's commit reaches the app as another process's commit — as the phone's does. The
 * clock moves by the spec's bound only: whatever brings the commit to the screen must do it within that.
 */
class CrossProcessStatusIntegrationTest {

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The status stays current while the app is open",
        scenario = "A background upload finishes while the app is open",
    )
    fun an_upload_the_extension_records_reaches_the_screen_in_front() = behaviour {
        given {
            step(
                "iOS's own uploader carried",
                act = {
                    extensionRegistry.carried(true)
                    host.relaunch(scene = true)
                },
                until = { settled { true } },
            )
            joinedEvent()
            step(
                "a photo the extension has handed to the OS",
                act = {
                    takePhoto("A")
                    cycle()
                },
                until = {
                    event<WorldEvent.UploadJobCreated>()
                    settled { true }
                },
            )
            // The foreground's own reads count the photo and see its upload under way: from here on, only the
            // extension's record of the finished upload can move the screen.
            step("in front, the upload under way", act = { os.foreground() }, until = {
                screen<Layer.Joined> { it.health is SyncHealth.Syncing }
                settled { true }
            })
        }

        // The OS finishes the job, and the extension's next pass records it: a commit in the extension's process only.
        whenever {
            uploadQueue.completeJob()
            cycle()
            advance(STATUS_BOUND)
        }
        then { screen<Layer.Joined> { it.health == SyncHealth.InSync } }
    }

    private companion object {
        /** The spec's "within about two seconds". */
        val STATUS_BOUND = 2.seconds
    }
}
