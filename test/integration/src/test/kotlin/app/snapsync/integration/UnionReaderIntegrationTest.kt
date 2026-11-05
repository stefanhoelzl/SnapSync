package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * **Why the app reads the photo list, as the service receives it**: each read carries its reason on the request, and
 * the service records it for the operator. The app's part is the reason it sends; keeping the record is the api's.
 */
@Verifies(spec = "privacy-security", requirement = "The service records who reads an event's photo list")
class UnionReaderIntegrationTest {

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "The service records who reads an event's photo list",
        scenario = "A member's app is woken for a new photo",
    )
    // The app's part: the read a push wakes says it was woken for a new photo.
    fun a_read_woken_by_a_new_photo_tells_the_service_so() = behaviour {
        val event = given {
            joinedEvent().also {
                pushToken(TOKEN)
                backgroundLaunch()
                fellowMember(DEV_F, "FA") // the backend wakes this member for it
            }
        }

        whenever { os.silentPush(event) }
        // The wake's own read. A cold launch's first import then recognises the received photos with a read of its
        // own, which names its own reason; this scenario is about the read the wake makes.
        then { event<WorldEvent.BackendReceived> { it.operation == UNION_READ && it.header(TRIGGER) == PUSH } }
    }

    private companion object {
        const val TOKEN = "DEADBEEF"
        const val UNION_READ = "eventFiles"

        /** The request header that names why the app reads the list (`api/openapi.yaml`), and its push value. */
        const val TRIGGER = "SnapSync-Trigger"
        const val PUSH = "push"
    }
}
