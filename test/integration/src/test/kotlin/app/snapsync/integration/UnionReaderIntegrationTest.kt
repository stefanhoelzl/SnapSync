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
    fun a_read_woken_by_a_new_photo_tells_the_service_so() = behaviour {
        // The app's part: the read a push wakes says it was woken for a new photo.
        val event = given {
            joinedEvent().also {
                pushToken(TOKEN)
                backgroundLaunch()
                fellowMember(DEV_F, "FA") // the backend wakes this member for it
            }
        }

        whenever { os.silentPush(event) }
        then { event<WorldEvent.BackendReceived> { it.operation == UNION_READ && it.header(TRIGGER) == PUSH } }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "The service records who reads an event's photo list",
        scenario = "A member's app is woken for a new photo",
    )
    fun a_cold_push_wake_recognising_the_received_photos_tells_the_service_it_was_woken_for_one() = behaviour {
        // A cold launch's first import recognises the received photos with a whole read of its own: the push woke the
        // app for it too, so it says so — never that the app was given photo access.
        val event = given {
            joinedEvent().also {
                pushToken(TOKEN)
                backgroundLaunch()
                fellowMember(DEV_F, "FA")
            }
        }

        whenever { os.silentPush(event) }
        then {
            event<WorldEvent.BackendReceived> { it.isUnionRead(whole = true) && it.header(TRIGGER) == PUSH }
            never<WorldEvent.BackendReceived> { it.operation == UNION_READ && it.header(TRIGGER) != PUSH }
        }
    }

    private fun WorldEvent.BackendReceived.isUnionRead(whole: Boolean) =
        operation == UNION_READ && (url?.contains(DELTA_FROM) == false) == whole

    private companion object {
        const val TOKEN = "DEADBEEF"
        const val UNION_READ = "eventFiles"

        /** The query parameter a delta read of the list names its position with (`api/openapi.yaml`). */
        const val DELTA_FROM = "cursor="

        /** The request header that names why the app reads the list (`api/openapi.yaml`), and its push value. */
        const val TRIGGER = "SnapSync-Trigger"
        const val PUSH = "push"
    }
}
