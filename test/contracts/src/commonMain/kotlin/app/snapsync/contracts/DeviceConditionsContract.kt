package app.snapsync.contracts

import app.snapsync.model.DeviceConditionsReading
import app.snapsync.model.Fact
import app.snapsync.ports.DeviceConditions
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** The states a [DeviceConditions] can be found in, as far as a clause cares: which platform's notions it carries. */
enum class DeviceConditionsState {
    /** A running app on an iPhone: Low Power Mode, Background App Refresh and the thermal state, and no standby bucket. */
    IPHONE,

    /** A running app on Android: Battery Saver, the standby bucket, the optimisation exemption and the thermal status. */
    ANDROID,
}

/**
 * What the device-condition read promises (`docs/architecture.md` — this list IS the port's specification).
 *
 * The battery is held only to its range, never to being known: a simulator has no battery and answers it failed, and a
 * clause that demanded it would make the one CI host for iOS unable to run the contract at all.
 */
object DeviceConditionsContract : Contract<DeviceConditionsState, DeviceConditions>("DeviceConditions") {

    override val clauses = clauses {

        clause("AN_IPHONE_ANSWERS_ITS_OWN_FACTS_AND_NOT_ANDROIDS", DeviceConditionsState.IPHONE) { conditions ->
            val reading = conditions.read()
            assertKnown(reading.powerSaving, "power saving")
            assertKnown(reading.backgroundRefresh, "background refresh")
            assertKnown(reading.thermal, "thermal state")
            assertIs<Fact.Unsupported>(
                reading.standbyBucket,
                "an iPhone has no standby bucket: ${reading.standbyBucket}",
            )
            assertIs<Fact.Unsupported>(
                reading.batteryOptimizationExempt,
                "an iPhone has no battery-optimisation exemption: ${reading.batteryOptimizationExempt}",
            )
            assertBatteryInRange(reading)
        }

        clause("AN_ANDROID_ANSWERS_ITS_OWN_FACTS_AND_NOT_IPHONES", DeviceConditionsState.ANDROID) { conditions ->
            val reading = conditions.read()
            assertKnown(reading.powerSaving, "battery saver")
            assertKnown(reading.standbyBucket, "standby bucket")
            assertKnown(reading.batteryOptimizationExempt, "battery-optimisation exemption")
            assertKnown(reading.thermal, "thermal status")
            assertIs<Fact.Unsupported>(
                reading.backgroundRefresh,
                "Android has no Background App Refresh: ${reading.backgroundRefresh}",
            )
            assertBatteryInRange(reading)
        }
    }

    private fun assertKnown(fact: Fact<*>, what: String) {
        if (fact !is Fact.Known) fail("a running app reads its $what: $fact")
    }

    private fun assertBatteryInRange(reading: DeviceConditionsReading) {
        (reading.batteryPercent as? Fact.Known)?.let {
            assertTrue(it.value in 0..100, "a battery level is a percentage: ${it.value}")
        }
        assertTrue(reading.charging !is Fact.Unsupported, "every platform has a charging state: ${reading.charging}")
        assertTrue(
            reading.batteryPercent !is Fact.Unsupported,
            "every platform has a battery level: ${reading.batteryPercent}",
        )
    }
}
