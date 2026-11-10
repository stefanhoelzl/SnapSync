package app.snapsync.domain.delivery

import app.snapsync.domain.report.BackgroundRefresh
import app.snapsync.domain.report.DeviceConditionsReading
import app.snapsync.domain.report.Fact
import app.snapsync.domain.report.StandbyBucket
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * **The device's conditions in the delivery context** ([DeliveryFacts]): `low_power` and `background`, and when they
 * are read again — a read may hop onto the main thread, so the context reads them at most once per refresh.
 */
object ConditionFacts {
    /** Whether the device's conditions are due to be read again: never read, or last read [refresh] ago or more. */
    fun due(readAt: Instant?, now: Instant, refresh: Duration): Boolean = readAt == null || now - readAt >= refresh

    /**
     * `low_power` and `background` from a [reading] — or the [previous] ones when the reading did not come in time.
     * `background` is whether the system lets the app work in the background: Background App Refresh is available on
     * iOS; on Android the app is not in the restricted standby bucket.
     */
    fun of(reading: DeviceConditionsReading?, previous: Map<String, String>): Map<String, String> = when (reading) {
        null -> previous
        else -> lowPower(reading.powerSaving) + background(reading.backgroundRefresh, reading.standbyBucket)
    }

    private fun lowPower(saving: Fact<Boolean>): Map<String, String> = when (saving) {
        is Fact.Known -> mapOf("low_power" to DeliveryFacts.flag(saving.value))
        Fact.Unsupported, is Fact.Failed -> emptyMap()
    }

    private fun background(refresh: Fact<BackgroundRefresh>, bucket: Fact<StandbyBucket>): Map<String, String> =
        when (refresh) {
            is Fact.Known -> mapOf("background" to DeliveryFacts.flag(refresh.value == BackgroundRefresh.AVAILABLE))
            Fact.Unsupported, is Fact.Failed -> standby(bucket)
        }

    private fun standby(bucket: Fact<StandbyBucket>): Map<String, String> = when (bucket) {
        is Fact.Known -> mapOf("background" to DeliveryFacts.flag(bucket.value != StandbyBucket.RESTRICTED))
        Fact.Unsupported, is Fact.Failed -> emptyMap()
    }
}
