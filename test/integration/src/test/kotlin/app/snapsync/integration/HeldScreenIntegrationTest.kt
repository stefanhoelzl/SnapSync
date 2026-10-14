package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.BackendCall
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test

/**
 * The screens the app shows only while it waits (`docs/testing.md`, "Every reviewed state is reachable"): each is
 * reached by holding what it waits on, and each moves on when that is released. The mocks answer at once, so without
 * a hold these screens exist for a moment no reviewer — and no screenshot — can catch.
 */
class HeldScreenIntegrationTest {

    @Test
    @Verifies(spec = "join-event", requirement = "The join screen verifies the event before offering to join")
    fun a_held_details_load_keeps_the_join_gate_loading_until_released() = behaviour {
        val link = given {
            val link = inviteFor(registeredEvent())
            step("the event's details held", act = { backend.hold(BackendCall.EVENT) }, until = {})
            link
        }

        whenever { openLink(link) }
        then {
            screen<Layer.JoiningEvent> { it.phase == JoinPhase.Loading }
            event<WorldEvent.BackendHeld> { it.call == BackendCall.EVENT }
            neverScreen<Layer.JoiningEvent> { it.phase != JoinPhase.Loading }
        }

        whenever { backend.release(BackendCall.EVENT) }
        then { screen<Layer.JoiningEvent> { it.phase is JoinPhase.Detailed } }
    }

    @Test
    @Verifies(spec = "join-event", requirement = "Joining happens only on confirmation and needs a connection")
    fun a_held_enrolment_keeps_the_join_gate_committing_until_released() = behaviour {
        val event = given {
            val event = registeredEvent()
            val link = inviteFor(event)
            step(
                "its join gate open, ready to join",
                act = { openLink(link) },
                until = { screen<Layer.JoiningEvent> { it.step() == JoinPhase.Detailed.Step.Ready } },
            )
            step("the enrolment held", act = { backend.hold(BackendCall.JOIN) }, until = {})
            event
        }

        whenever { confirmJoin() }
        then {
            screen<Layer.JoiningEvent> { it.step() == JoinPhase.Detailed.Step.Committing }
            event<WorldEvent.BackendHeld> { it.call == BackendCall.JOIN }
            neverScreen<Layer.Joined>()
        }

        whenever { backend.release(BackendCall.JOIN) }
        then { screen<Layer.Joined> { it.membership.eventId == event } }
    }

    @Test
    @Verifies(spec = "create-event", requirement = "The creator joins through the same join screen as every guest")
    fun a_held_create_keeps_the_create_in_flight_until_released() = behaviour {
        given { step("the create held", act = { backend.hold(BackendCall.CREATE) }, until = {}) }

        whenever {
            createEvent(Rig.EVENT_NAME, Rig.WINDOW_START, Rig.WINDOW_END)
        }
        then {
            screen<Layer.CreatingEvent>()
            event<WorldEvent.BackendHeld> { it.call == BackendCall.CREATE }
            neverScreen<Layer.JoiningEvent>()
        }

        whenever { backend.release(BackendCall.CREATE) }
        then { screen<Layer.JoiningEvent> { it.phase is JoinPhase.Detailed } }
    }

    @Test
    @Verifies(spec = "sync-status", requirement = "\"Up to date\" is never claimed before the app has looked")
    fun a_held_enumeration_keeps_the_joined_screen_loading_until_released() = behaviour {
        given {
            step("the library's walks held", act = { library.holdEnumeration(on = true) }, until = {})
            createdEvent()
            step(
                "joined, nothing counted",
                act = { confirmJoin() },
                until = { screen<Layer.Joined> { it.health == SyncHealth.Loading } },
            )
        }

        whenever { os.foreground() }
        then {
            event<WorldEvent.LibraryEnumerationHeld>()
            neverScreen<Layer.Joined> { it.health != SyncHealth.Loading }
        }

        whenever {
            library.holdEnumeration(on = false)
            os.foreground()
        }
        then { screen<Layer.Joined> { it.health == SyncHealth.InSync } }
    }

    private fun Layer.JoiningEvent.step(): JoinPhase.Detailed.Step? = (phase as? JoinPhase.Detailed)?.step
}
