package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.BackendFault
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.UiIntent
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * **A create the service fails** (decision record `changes/tell-service-faults`): each way SnapSync's service can fail
 * is told below Create in its own words, nothing is created or joined, and only the host's tap tries again — over the
 * real composed core, the service's failure played by the backend mock's per-operation lever.
 */
class CreateFaultIntegrationTest {

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "A failed create says so and changes nothing",
        scenario = "A service in trouble is reported as such",
    )
    @Verifies(
        spec = "app-experience",
        requirement = "A failing service is told as what it is",
        scenario = "A failing service is told as trouble",
    )
    @Verifies(
        spec = "app-experience",
        requirement = "Failures are told calmly, never as a red field",
        scenario = "A submission is refused",
    )
    fun a_create_the_service_fails_says_it_is_having_trouble() = behaviour {
        given { inFront() }
        whenever {
            backend.fail(CREATE, BackendFault.SERVER_ERROR)
            createTheEvent()
        }
        then {
            screen<Layer.CreateEvent> { it.error == ScreenMessage.SERVICE_TROUBLE }
            neverScreen<Layer.JoiningEvent>()
        }
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "A failed create says so and changes nothing",
        scenario = "An update in progress is reported as brief",
    )
    @Verifies(
        spec = "app-experience",
        requirement = "A failing service is told as what it is",
        scenario = "An update is told as brief",
    )
    fun a_create_during_an_update_says_so_and_waits_for_the_host() = behaviour {
        given {
            inFront()
            step(
                "a create during an update",
                act = {
                    backend.fail(CREATE, BackendFault.MAINTENANCE)
                    createTheEvent()
                },
                until = { screen<Layer.CreateEvent> { it.error == ScreenMessage.SERVICE_UPDATING && it.form.canCreate } },
            )
        }

        // Back shortly — but nothing is created until the host taps Create again.
        whenever { advance(10.minutes) }
        then {
            never<WorldEvent.BackendReceived> { it.operation == CREATE }
            neverScreen<Layer.JoiningEvent>()
        }

        whenever { createAgain() }
        then { screen<Layer.JoiningEvent> { it.phase is JoinPhase.Detailed } }
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "A failed create says so and changes nothing",
        scenario = "An unreachable server is reported as such",
    )
    @Verifies(
        spec = "app-experience",
        requirement = "A failing service is told as what it is",
        scenario = "No answer is told as unreachable",
    )
    fun a_create_that_gets_no_answer_says_the_service_cannot_be_reached() = behaviour {
        given { inFront() }
        whenever {
            backend.fail(CREATE, BackendFault.UNREACHABLE)
            createTheEvent()
        }
        then {
            screen<Layer.CreateEvent> { it.error == ScreenMessage.SERVICE_UNREACHABLE }
            neverScreen<Layer.JoiningEvent>()
        }
    }

    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "A failing service is told as what it is",
        scenario = "A Wi-Fi login page is not an answer",
    )
    fun a_create_answered_by_a_wifi_login_page_is_no_answer_and_creates_nothing() = behaviour {
        given { inFront() }
        whenever {
            backend.fail(CREATE, BackendFault.MALFORMED)
            createTheEvent()
        }
        then {
            screen<Layer.CreateEvent> { it.error == ScreenMessage.SERVICE_UNREACHABLE }
            neverScreen<Layer.JoiningEvent>()
        }
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "A failed create says so and changes nothing",
        scenario = "A failed create keeps what the host entered",
    )
    fun a_failed_create_keeps_the_name_and_range_and_one_tap_retries_with_them() = behaviour {
        given {
            inFront()
            step(
                "a failed create",
                act = {
                    backend.fail(CREATE, BackendFault.SERVER_ERROR)
                    createTheEvent()
                },
                until = { screen<Layer.CreateEvent> { it.error != null && it.form.name == Rig.EVENT_NAME } },
            )
        }

        whenever { createAgain() }
        then {
            screen<Layer.JoiningEvent> { layer ->
                val phase = layer.phase as? JoinPhase.Detailed
                val range = layer.range
                phase?.eventName == Rig.EVENT_NAME && range != null &&
                    range.windowStart == LocalDateTime.parse(Rig.WINDOW_START) &&
                    range.windowEnd == LocalDateTime.parse(Rig.WINDOW_END)
            }
        }
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "A failed create says so and changes nothing",
        scenario = "The failure message stays until the next attempt",
    )
    fun the_failure_message_stays_while_the_host_types_and_goes_when_they_tap_create() = behaviour {
        given {
            inFront()
            step(
                "a failed create",
                act = {
                    backend.fail(CREATE, BackendFault.SERVER_ERROR)
                    createTheEvent()
                },
                until = { screen<Layer.CreateEvent> { it.error == ScreenMessage.SERVICE_TROUBLE } },
            )
        }

        whenever { tap(UiIntent.CreateName("Ski Trip")) }
        then {
            screen<Layer.CreateEvent> { it.form.name == "Ski Trip" && it.error == ScreenMessage.SERVICE_TROUBLE }
            neverScreen<Layer.CreateEvent> { it.error == null }
        }

        whenever { createAgain() }
        then { screen<Layer.CreatingEvent>() }
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "Creating an event that is already over asks how long guests have to join",
        scenario = "A retry after a failure asks again",
    )
    fun a_retry_of_a_failed_back_dated_create_asks_again_how_long_guests_have() = behaviour(
        startAt = AFTER_THE_WINDOW,
    ) {
        given {
            inFront()
            step(
                "a back-dated create, 7 days chosen, that the service could not be reached for",
                act = {
                    backend.fail(CREATE, BackendFault.UNREACHABLE)
                    createTheEvent()
                },
                until = { screen<Layer.CreateEvent> { it.error == ScreenMessage.SERVICE_UNREACHABLE } },
            )
        }

        whenever { createAgain() }
        then { screen<Layer.CreateEvent> { it.form.keepOpen != null } }

        whenever { createAgain(7) }
        then { screen<Layer.JoiningEvent> { it.phase is JoinPhase.Detailed } }
    }

    private companion object {
        const val CREATE = "createEvent"

        /** A week after the default window ended: a create of it asks how long guests have to join. */
        const val AFTER_THE_WINDOW = "2026-06-21T12:00:00Z"
    }
}

/** The host creates the default event, as the create form is filled — a range already over answered with 7 days. */
suspend fun Acts.createTheEvent() {
    createEvent(Rig.EVENT_NAME, Rig.WINDOW_START, Rig.WINDOW_END)
}
