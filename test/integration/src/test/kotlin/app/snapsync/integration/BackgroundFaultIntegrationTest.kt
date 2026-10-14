package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.BackendFault
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test
import kotlin.time.Duration.Companion.days

/**
 * **What a member relies on once SnapSync's service works again** (decision record `changes/tell-service-faults`): a
 * leave, the shared set and the "received everything" report the service failed — or answered with something the app
 * cannot read — are not counted as done, and reach it at a later wake without the member doing anything; a failed wake
 * registration only delays received photos. Each promise is shown by what reaches the service, over the real composed
 * core.
 */
class BackgroundFaultIntegrationTest {

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "An event's photos are deleted once it is finished, and after 30 days at the latest",
        scenario = "A leave the service failed to take still counts",
    )
    fun a_leave_the_service_answered_unreadably_is_sent_again_once_it_works_and_then_no_more() = behaviour {
        given {
            joinedEvent()
            inFront()
            step(
                "left while the service answers with a login page",
                act = {
                    backend.fail(LEAVE, BackendFault.MALFORMED, MANY)
                    tap(UiIntent.LeaveEvent)
                },
                until = {
                    screen<Layer.CreateEvent>()
                    settled { true }
                },
            )
        }

        whenever {
            recovers()
            os.foreground()
        }
        then { event<WorldEvent.BackendReceived> { it.operation == LEAVE } }

        // Taken now: the leave counts, and is not sent again.
        whenever { os.foreground() }
        then { never<WorldEvent.BackendReceived> { it.operation == LEAVE } }
    }

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "An event's photos are deleted once it is finished, and after 30 days at the latest",
        scenario = "A member who cannot say they are done holds deletion back",
    )
    fun a_received_report_the_service_answered_unreadably_is_told_again_once_it_works() = behaviour {
        given {
            joinedEvent()
            inFront()
            step("in the background", act = { os.background() }, until = { settled { true } })
            step("the event has ended", act = { advance(AFTER_THE_END) }, until = { settled { true } })
            step(
                "a report the service answers with a login page",
                act = {
                    backend.fail(REPORT, BackendFault.MALFORMED, MANY)
                    os.foreground()
                },
                until = { event<WorldEvent.BackendReceived> { it.operation == REPORT } },
            )
            step("in the background", act = { os.background() }, until = { settled { true } })
        }

        whenever {
            recovers()
            os.foreground()
        }
        then { event<WorldEvent.BackendReceived> { it.operation == REPORT } }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "A photo is never lost on the way",
        scenario = "Photos shared while the service fails reach the others later",
    )
    fun a_shared_set_the_service_answered_unreadably_is_published_again_once_it_works() = behaviour {
        given {
            joinedEvent()
            inFront()
            step(
                "a photo shared while the service answers with a login page",
                act = {
                    backend.fail(PUBLISH, BackendFault.MALFORMED, MANY)
                    takePhoto("P1")
                    os.foreground()
                },
                until = {
                    event<WorldEvent.BackendReceived> { it.operation == PUBLISH }
                    settled { true }
                },
            )
            // Every change the photo makes to the set is published — and answered with the login page — before the
            // service works again, so only a publish not counted as done can still reach it afterwards.
            step("in the background", act = { os.background() }, until = { settled { true } })
            step("opened again, still garbled", act = { os.foreground() }, until = { settled { true } })
            step("in the background", act = { os.background() }, until = { settled { true } })
        }

        whenever {
            recovers()
            os.foreground()
        }
        then { event<WorldEvent.BackendReceived> { it.operation == PUBLISH } }

        // Taken now: the same set is not published again.
        whenever { os.foreground() }
        then { never<WorldEvent.BackendReceived> { it.operation == PUBLISH } }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Only new photos are announced by a silent wake, and never only by it",
        scenario = "The service could not set up wakes",
    )
    fun a_failed_wake_registration_only_delays_received_photos() = behaviour {
        given {
            joinedEvent()
            inFront()
            step(
                "its wakes the service failed to set up",
                act = {
                    backend.fail(PUT_DEVICE, BackendFault.SERVER_ERROR, MANY)
                    os.pushToken(TOKEN)
                },
                until = { settled { true } },
            )
            step("in the background", act = { os.background() }, until = { settled { true } })
            step("a fellow member's photo", act = { publicBackend.foreignDevice(FELLOW, listOf("F1")) }, until = {})
        }

        whenever { os.foreground() }
        then { event<WorldEvent.DownloadStarted>() }
    }

    private companion object {
        const val LEAVE = "leaveEvent"
        const val REPORT = "reportReceived"
        const val PUBLISH = "publishManifest"
        const val PUT_DEVICE = "putDevice"
        const val MANY = 10_000
        const val TOKEN = "DEADBEEF"
        const val FELLOW = "11111111-1111-4111-8111-111111111111"

        /** From the event's day to past its end (the default window ends on 14 June). */
        val AFTER_THE_END = 14.days
    }
}
