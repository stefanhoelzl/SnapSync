package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import kotlin.test.Test

/** The join screen states how many members the event has before the user decides to join. */
class JoinMemberCountIntegrationTest {

    private fun Layer.JoiningEvent.members(): Int? = (phase as? JoinPhase.Detailed)?.members

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The join screen shows how many members the event has",
        scenario = "Members are shown before joining",
    )
    fun a_guest_sees_the_members_who_joined_and_have_not_left() = behaviour {
        given {
            val event = registeredEvent()
            step(
                "four devices joined it, and one of them left",
                act = {
                    DEVICES.forEachIndexed { i, device ->
                        publicBackend.foreignDevice(
                            device,
                            listOf("P$i"),
                            event = event,
                        )
                    }
                    publicBackend.foreignDeviceLeave(DEVICES.last(), event = event)
                },
                until = { settled { true } },
            )
            event
        }.let { event ->
            whenever { openLink(inviteLink(event)) }
            then { screen<Layer.JoiningEvent> { it.eventId == event && it.members() == 3 } }
        }
    }

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "The join screen shows how many members the event has",
        scenario = "The host's own join screen",
    )
    fun a_host_sees_no_member_on_the_join_screen_of_the_event_just_created() = behaviour {
        whenever { createEvent(Defaults.EVENT_NAME, Defaults.WINDOW_START, Defaults.WINDOW_END) }
        then { screen<Layer.JoiningEvent> { it.members() == 0 } }
    }

    private companion object {
        val DEVICES = (1..4).map { "def0f000-0000-4000-8000-00000000010$it" }
    }
}
