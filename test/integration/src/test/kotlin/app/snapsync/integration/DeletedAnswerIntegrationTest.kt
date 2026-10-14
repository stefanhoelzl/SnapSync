package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.BackendFault
import app.snapsync.ui.state.Layer
import kotlin.test.Test

/**
 * **The service's word that an event's photos were deleted, answering the member's sharing** (decision record
 * `changes/tell-service-faults`): the joined screen says the event is over and the member stays in it — while a failing
 * service, or an answer the app cannot read, never says so. Over the real composed core.
 */
class DeletedAnswerIntegrationTest {

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "The app never leaves an event on its own",
        scenario = "Sharing into a deleted event marks it over",
    )
    fun sharing_answered_with_the_photos_deleted_shows_the_event_over_and_the_member_stays() = behaviour {
        given {
            joinedEvent()
            inFront()
        }
        whenever {
            backend.fail(PUBLISH, BackendFault.ENDED_FOR_GOOD, MANY)
            takePhoto("P1")
            os.foreground()
        }
        then {
            screen<Layer.Joined> { it.deleted }
            neverScreen<Layer.CreateEvent>()
        }
    }

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "The app never leaves an event on its own",
        scenario = "A server error never ends a membership",
    )
    fun sharing_the_service_fails_or_garbles_never_says_the_event_is_over() = behaviour {
        given {
            joinedEvent()
            inFront()
        }
        whenever {
            backend.fail(PUBLISH, BackendFault.SERVER_ERROR, 1)
            backend.fail(GET_EVENT, BackendFault.MALFORMED, MANY)
            takePhoto("P1")
            os.foreground()
        }
        then {
            neverScreen<Layer.Joined> { it.deleted }
            neverScreen<Layer.CreateEvent>()
        }
    }

    private companion object {
        const val PUBLISH = "publishManifest"
        const val GET_EVENT = "getEvent"
        const val MANY = 10_000
    }
}
