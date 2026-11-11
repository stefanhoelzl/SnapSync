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

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "The service records who reads an event's photo list",
        scenario = "A member's app is woken for a new photo",
    )
    fun the_read_a_push_caused_names_the_position_it_announced_and_when_the_app_acted() = behaviour {
        // The delivery record's half of the push: which announcement this read answers, so the service can tell a
        // push it sent from one that never arrived (decision record `changes/delivery-record`, D3).
        val event = given {
            joinedEvent().also {
                pushToken(TOKEN)
                backgroundLaunch()
                fellowMember(DEV_F, "FA")
            }
        }

        whenever { os.silentPush(event) }
        then {
            event<WorldEvent.BackendReceived> {
                it.operation == UNION_READ && it.header(TRIGGER) == PUSH &&
                    it.header(ANNOUNCED)?.toLongOrNull() != null &&
                    it.header(NOTIFIED)?.matches(WHOLE_SECOND_UTC) == true
            }
        }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "The service records who reads an event's photo list",
        scenario = "A member's app is woken for a new photo",
    )
    fun the_read_a_push_caused_echoes_the_position_the_push_named() = behaviour {
        // A push naming its position by the caller — as a deployed backend's wake does, which no mock handed over.
        val event = given {
            joinedEvent().also {
                pushToken(TOKEN)
                backgroundLaunch()
                fellowMember(DEV_F, "FA")
            }
        }

        whenever { os.silentPush(event, announced = NAMED_POSITION) }
        then {
            event<WorldEvent.BackendReceived> {
                it.operation == UNION_READ && it.header(ANNOUNCED) == "$NAMED_POSITION"
            }
        }
    }

    private fun WorldEvent.BackendReceived.isUnionRead(whole: Boolean) =
        operation == UNION_READ && (url?.contains(DELTA_FROM) == false) == whole

    private companion object {
        const val TOKEN = "DEADBEEF"

        /** A position no mocked wake announced: the one a caller names. */
        const val NAMED_POSITION = 99L
        const val UNION_READ = "eventFiles"

        /** The query parameter a delta read of the list names its position with (`api/openapi.yaml`). */
        const val DELTA_FROM = "cursor="

        /** The request header that names why the app reads the list (`api/openapi.yaml`), and its push value. */
        const val TRIGGER = "SnapSync-Trigger"
        const val PUSH = "push"

        /** The delivery facts of a read a push caused (`api/openapi.yaml`), and the one time shape the wire speaks. */
        const val ANNOUNCED = "x-snapsync-announced"
        const val NOTIFIED = "x-snapsync-notified"
        val WHOLE_SECOND_UTC = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")
    }
}
