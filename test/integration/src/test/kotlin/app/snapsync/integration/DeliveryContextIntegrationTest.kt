package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Expectations
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.NetworkState
import app.snapsync.levers.WorldEvent
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * **The delivery context** every request carries (decision record `changes/delivery-record`, D8), over the real composed
 * stack: the device, its platform and its conditions as the app reads them, in the one header the service's request
 * log reads. Each fact's words are `DeliveryFactsTest`'s; this holds that the app sends them, and follows the device.
 */
class DeliveryContextIntegrationTest {

    @Test
    @Verifies(spec = "privacy-security", requirement = "The service records who reads an event's photo list")
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
    @Verifies(spec = "privacy-security", requirement = "The service records who reads an event's photo list")
    fun the_received_report_tells_when_each_photo_was_downloaded_and_added_and_by_which_entry_point() = behaviour {
        given {
            joinedEvent(startsAt = SHORT_START, endsAt = SHORT_END)
            receivedFrom(DEV_F, "FQ")
            laterInThePocket(AFTER_THE_END)
        }

        whenever { os.foreground() }
        then {
            event<WorldEvent.BackendReceived> { received ->
                received.operation == "reportReceived" && received.body.orEmpty().let { body ->
                    "\"delivered\":[{" in body && "\"asset\":\"FQ\"" in body &&
                        "\"downloaded\":\"" in body && "\"added\":\"" in body && "\"by\":\"" in body
                }
            }
        }
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

        /** A short event, and a moment past it: a received report is due once the event has ended. */
        const val SHORT_START = "2026-05-25T00:00:00"
        const val SHORT_END = "2026-06-02T00:00:00"
        const val AFTER_THE_END = "2026-06-04T12:00:00Z"

        /** What the composed app on the mock world says once the device turned restricted and Android-like. */
        val EXPECTED = listOf("access=full", "network=restricted", "low_power=1", "background=0")
    }
}
