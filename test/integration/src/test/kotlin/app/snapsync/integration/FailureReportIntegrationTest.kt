package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * An error the app hits on its own reaches the operator as a report (capability `privacy-security`): titled by what
 * failed, never by the values it failed with, naming the event and the device it happened to in its own section — and
 * never carrying the key of an encrypted event. Observed where the operator would see it: on what the crash reporter's
 * backend received.
 */
class FailureReportIntegrationTest {

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "Automatic failure reports are minimal and anonymous",
        scenario = "The app hits an error during an upload",
    )
    fun an_error_sharing_an_encrypted_event_s_photos_names_the_event_and_the_device_and_never_its_key() = behaviour {
        // A member of an encrypted event with a photo to share, when the service starts refusing this build: the device can
        // do nothing more, an error for the operator to see.
        val event = given {
            val event = joinedEvent()
            photos("CAM")
            step("the service refusing this build", act = { backend.minAppVersion(ABOVE_THIS_BUILD) }, until = {})
            event
        }

        lateinit var received: Seen<WorldEvent.EventReceived>
        whenever { os.foreground() }
        then { received = event<WorldEvent.EventReceived> { it.event.message == REFUSED } }

        val report = received.value.event
        assertEquals(listOf("AppVersionGate: $REFUSED"), report.fingerprint, "it groups by what failed")
        val section = report.contexts.getValue("snapsync")
        assertEquals(event, section["event"], "it names the event it happened to: $section")
        assertFalse(
            section["device"].orEmpty().let { it.isBlank() || it.startsWith("failed") },
            "and the device: $section",
        )
        assertEquals(
            ABOVE_THIS_BUILD,
            report.contexts.getValue("snapsync_log")["minimum"],
            "its fields ride beneath it",
        )
        val key = given { inviteFor(event) }.substringAfter("#k=")
        assertEquals(KEY_LENGTH, key.length, "the invite carried a key: '$key'")
        assertFalse(key in report.toString(), "the event's key is nowhere in the report: $report")
    }

    private companion object {
        const val ABOVE_THIS_BUILD = "100.0"
        const val REFUSED = "backend refuses this build"
        const val KEY_LENGTH = 43
    }
}
