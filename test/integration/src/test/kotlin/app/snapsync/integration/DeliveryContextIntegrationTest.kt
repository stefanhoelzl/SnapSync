package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Expectations
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.BackendFault
import app.snapsync.levers.NetworkState
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * **The delivery context** every request carries (decision record `changes/delivery-record`, D8), over the real composed
 * stack: the device, its platform and its conditions as the app reads them, in the one header the service's request
 * log reads. Each fact's words are `DeliveryFactsTest`'s; this holds that the app sends them, and follows the device.
 */
class DeliveryContextIntegrationTest {

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "The service records how photos are delivered",
        scenario = "A photo travels from one member to another",
    )
    fun a_read_carries_the_device_and_the_conditions_it_reads_now() = behaviour {
        given { joinedEvent() }

        whenever {
            network.set(NetworkState.RESTRICTED)
            deviceConditions.set(
                mapOf(
                    "power_saving" to "true",
                    "background_refresh" to "unsupported",
                    "standby_bucket" to "restricted",
                ),
            )
            advance(2.minutes)
            os.foreground()
        }
        then { read(EXPECTED) }

        // A minute on, the conditions are read again: power saving unreadable is left out, a working bucket allows.
        whenever {
            deviceConditions.set(mapOf("power_saving" to "failed:timeout", "standby_bucket" to "rare"))
            advance(2.minutes)
            os.foreground()
        }
        then { read(listOf("background=1")) { "low_power" !in it } }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "The service records how photos are delivered",
        scenario = "A photo travels from one member to another",
    )
    fun a_photo_added_while_the_app_runs_tells_when_and_how_it_arrived_without_waiting_for_a_wake() = behaviour {
        given {
            joinedEvent()
            downloadStarted()
        }

        // The wake that started the download has ended; the app stays in front, and no other wake comes.
        whenever { downloadSession.finish() }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == imported("FQ") }
            event<WorldEvent.BackendReceived> { it.tellsArrival("FQ") }
        }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "The service records how photos are delivered",
        scenario = "A photo travels from one member to another",
    )
    fun a_leave_tells_the_delivery_facts_not_told_yet_before_it_leaves() = behaviour {
        given {
            joinedEvent()
            downloadStarted()
            step("FQ added, its report lost", act = {
                backend.fail(REPORT_RECEIVED, BackendFault.UNREACHABLE, times = 1)
                downloadSession.finish()
            }, until = {
                event<WorldEvent.LibraryAssetCreated> { it.asset == imported("FQ") }
                settled { true }
            })
        }

        whenever { tap(UiIntent.LeaveEvent) }
        then {
            event<WorldEvent.BackendReceived> { it.tellsArrival("FQ") }
            event<WorldEvent.BackendReceived> { it.operation == "leaveEvent" }
        }
    }

    /** A received report that tells when [asset] was downloaded and added to the library, and by which entry point. */
    private fun WorldEvent.BackendReceived.tellsArrival(asset: String) = operation == REPORT_RECEIVED &&
        body.orEmpty().let { text ->
            listOf("\"asset\":\"$asset\"", "\"downloaded\":\"", "\"added\":\"", "\"by\":\"").all { it in text }
        }

    /** A union read whose context holds every one of [facts], and passes [also] on the whole header. */
    private fun Expectations.read(facts: List<String>, also: (String) -> Boolean = { true }) {
        event<WorldEvent.BackendReceived> { received ->
            received.operation == "eventFiles" &&
                received.header(CONTEXT)?.let { context -> facts.all { it in context.split(";") } && also(context) } == true
        }
    }

    private companion object {
        const val CONTEXT = "x-snapsync-context"
        const val REPORT_RECEIVED = "reportReceived"

        /** What the composed app on the mock world says once the device turned restricted and Android-like. */
        val EXPECTED = listOf("access=full", "network=restricted", "low_power=1", "background=0")
    }
}
