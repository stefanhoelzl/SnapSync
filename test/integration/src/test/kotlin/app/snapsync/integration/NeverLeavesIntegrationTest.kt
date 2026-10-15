package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Expectations
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SharingNote
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test

/**
 * **The app never leaves an event on its own** (capability `event-lifetime`), over the real composition: a finished
 * event stays on the joined screen, up to date; a deleted one stays too, shown as over; and a service that merely fails
 * to find the event before its photos may be deleted is disbelieved — the member stays, and sharing goes on.
 *
 * A leave the app made would reach the backend as `leaveEvent` and put the create screen up: each test watches for
 * both. What a deletion does to the library is `JoinGateIntegrationTest`'s and `BackDatedEventIntegrationTest`'s.
 */
class NeverLeavesIntegrationTest {

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "The app never leaves an event on its own",
        scenario = "A finished event stays on screen",
    )
    fun a_finished_event_is_still_joined_and_up_to_date_when_the_member_next_opens_the_app() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent(startsAt = SHORT_START, endsAt = SHORT_END)
            received(DEV_F, "FQ", final = true)
            step("the fellow member holds everything", act = { publicBackend.foreignDeviceReceived(DEV_F) }, until = {})
            laterInThePocket(AFTER_THE_END)
            step("this device's share settled", act = { cycle() }, until = { settled { true } })
            step("what it received reported", act = { os.foreground() }, until = {
                event<WorldEvent.BackendReceived> { it.operation == "reportReceived" }
                settled { true }
            })
            // The event is finished now — its end passed, and everyone in it shared and received everything — and the
            // phone spends a day in the pocket.
            laterInThePocket(A_DAY_LATER)
        }

        whenever {
            host.relaunch()
            os.foreground()
        }
        then {
            screen<Layer.Joined> {
                it.ended && it.health == SyncHealth.InSync && it.sharingNote == SharingNote.EveryoneShared && !it.deleted
            }
            noLeave()
        }
    }

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "The app never leaves an event on its own",
        scenario = "A deleted event is not left silently",
    )
    fun a_deleted_event_keeps_the_member_and_says_it_is_over() = behaviour {
        val event = given {
            val event = joinedEvent()
            received(DEV_F, "FQ")
            event
        }

        whenever {
            backend.complete(event)
            os.foreground()
        }
        then {
            screen<Layer.Joined> { it.membership.eventId == event && it.deleted }
            noLeave()
        }
    }

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "The app never leaves an event on its own",
        scenario = "A premature \"gone\" is disbelieved",
    )
    fun an_event_the_service_cannot_find_before_its_last_day_stays_joined_and_shared_to() = behaviour {
        given {
            extensionUploadsOnly()
            val event = joinedEvent()
            // The event's record is gone, but the photos may live for weeks yet: one witness, not two.
            step("the service no longer finds it", act = { backend.sweep(event) }, until = {})
        }

        whenever { os.foreground() }
        then {
            neverScreen<Layer.Joined> { it.deleted }
            noLeave()
        }

        whenever {
            takePhoto("A")
            cycle()
        }
        then { event<WorldEvent.UploadJobCreated> { it.job == primaryJob("A") } }
    }

    /** Nothing left the event: no leave reached the backend, and the create screen never showed. */
    private fun Expectations.noLeave() {
        never<WorldEvent.BackendReceived> { it.operation == "leaveEvent" }
        neverScreen<Layer.CreateEvent>()
    }

    private companion object {
        /** A short range around the start of every behaviour's clock (1 June noon). */
        const val SHORT_START = "2026-05-25T00:00:00"
        const val SHORT_END = "2026-06-02T00:00:00"

        /** Past [SHORT_END], and its open-until date with it; long before its photos may be deleted. */
        const val AFTER_THE_END = "2026-06-04T12:00:00Z"
        const val A_DAY_LATER = "2026-06-05T12:00:00Z"
    }
}
