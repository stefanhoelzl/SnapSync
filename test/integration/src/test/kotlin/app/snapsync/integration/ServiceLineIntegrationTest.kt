package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.BackendFault
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.ServiceNotice
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test

/**
 * **The joined screen says when SnapSync is failing while photos wait** (decision record
 * `changes/tell-service-faults`): which way it fails and that the photos are safe — never over "Up to date", never for
 * a failure from before the opening, and gone once the service works again. Over the real composed core, the outage
 * played by the backend mock's per-operation lever on every request the app sends.
 */
class ServiceLineIntegrationTest {

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "A failing service is shown while photos wait",
        scenario = "Photos wait while the service is in trouble",
    )
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen shows one status line in a fixed priority",
        scenario = "A failing service outranks progress",
    )
    fun photos_waiting_while_the_service_is_in_trouble_are_told_safe() = behaviour {
        given {
            joinedEvent()
            inFront()
        }
        whenever {
            outage(BackendFault.SERVER_ERROR)
            takePhoto("P1")
            os.foreground()
        }
        then { screen<Layer.Joined> { it.health == SyncHealth.ServiceFailing(ServiceNotice.TROUBLE) } }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "A failing service is shown while photos wait",
        scenario = "Photos wait during an update",
    )
    fun photos_waiting_to_be_received_during_an_update_are_told_back_shortly() = behaviour {
        given {
            joinedEvent()
            step("a fellow member's photo", act = { publicBackend.foreignDevice(FELLOW, listOf("F1")) }, until = {})
            step("on its way to this phone", act = { os.foreground() }, until = { settled { true } })
            step("in the background", act = { os.background() }, until = { settled { true } })
        }
        whenever {
            outage(BackendFault.MAINTENANCE)
            os.foreground()
        }
        then { screen<Layer.Joined> { it.health == SyncHealth.ServiceFailing(ServiceNotice.UPDATING) } }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "A failing service is shown while photos wait",
        scenario = "Photos wait while the service cannot be reached",
    )
    @Verifies(
        spec = "sync-status",
        requirement = "The app says when it cannot reach the network",
        scenario = "The server is down, not the network",
    )
    fun photos_waiting_while_the_service_cannot_be_reached_are_not_told_as_offline() = behaviour {
        given {
            joinedEvent()
            inFront()
        }
        whenever {
            outage(BackendFault.UNREACHABLE)
            takePhoto("P1")
            os.foreground()
        }
        then {
            screen<Layer.Joined> { it.health == SyncHealth.ServiceFailing(ServiceNotice.UNREACHABLE) }
            neverScreen<Layer.Joined> { it.health is SyncHealth.NoNetwork }
        }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "A failing service is shown while photos wait",
        scenario = "Nothing waiting, nothing said",
    )
    fun an_outage_with_nothing_waiting_leaves_up_to_date() = behaviour {
        given {
            joinedEvent()
            inFront()
            step("in the background", act = { os.background() }, until = { settled { true } })
        }
        whenever {
            outage(BackendFault.SERVER_ERROR)
            os.foreground()
        }
        // The line stays "Up to date": no other line is shown.
        then { neverScreen<Layer.Joined> { it.health != SyncHealth.InSync } }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "A failing service is shown while photos wait",
        scenario = "Opening the app retries before telling",
    )
    fun an_opening_after_a_background_failure_tells_nothing_once_its_own_attempt_succeeds() = behaviour {
        given {
            joinedEvent()
            inFront()
            step(
                "a photo waiting",
                act = {
                    takePhoto("P1")
                    os.foreground()
                },
                until = { settled { true } },
            )
            step("in the background", act = { os.background() }, until = { settled { true } })
            step(
                "a background attempt the service fails",
                act = {
                    outage(BackendFault.SERVER_ERROR)
                    heartbeat()
                },
                until = { settled { true } },
            )
            step("the service works again", act = { recovers() }, until = {})
        }
        whenever { os.foreground() }
        then { neverScreen<Layer.Joined> { it.health is SyncHealth.ServiceFailing } }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "A failing service is shown while photos wait",
        scenario = "The line clears once the service works",
    )
    fun the_line_clears_once_the_service_works_again() = behaviour {
        given {
            joinedEvent()
            inFront()
            step(
                "told the service is in trouble",
                act = {
                    outage(BackendFault.SERVER_ERROR)
                    takePhoto("P1")
                    os.foreground()
                },
                until = { screen<Layer.Joined> { it.health == SyncHealth.ServiceFailing(ServiceNotice.TROUBLE) } },
            )
        }
        whenever {
            recovers()
            os.foreground()
        }
        then { screen<Layer.Joined> { it.health !is SyncHealth.ServiceFailing } }
    }

    private companion object {
        /** A fellow member's device. */
        const val FELLOW = "11111111-1111-4111-8111-111111111111"
    }
}
