package app.snapsync.android.device

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import app.snapsync.model.Charging
import app.snapsync.model.DeviceConditionsReading
import app.snapsync.model.Fact
import app.snapsync.model.StandbyBucket
import app.snapsync.model.Thermal
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.DeviceConditions

/**
 * The Android [DeviceConditions] (capability `privacy-security`): Battery Saver, the battery-optimisation exemption and
 * the thermal status from `PowerManager`, this app's standby bucket from `UsageStatsManager`, and the battery from
 * `BatteryManager`'s properties. None of them needs a permission — each answers about this app or the device it runs
 * on — and every one is a plain read on any thread.
 *
 * iOS's Background App Refresh has no Android counterpart and answers unsupported; the standby bucket and the exemption
 * are what decide how often Android lets this app work in the background.
 */
class AndroidDeviceConditions(context: Context) : DeviceConditions {
    private val app = context.applicationContext
    private val power: PowerManager = app.getSystemService(PowerManager::class.java)
    private val usage: UsageStatsManager = app.getSystemService(UsageStatsManager::class.java)
    private val battery: BatteryManager = app.getSystemService(BatteryManager::class.java)

    override suspend fun read(): DeviceConditionsReading = DeviceConditionsReading(
        powerSaving = fact { Fact.Known(power.isPowerSaveMode) },
        backgroundRefresh = Fact.Unsupported,
        standbyBucket = fact { bucketOf(usage.appStandbyBucket) },
        batteryOptimizationExempt = fact { Fact.Known(power.isIgnoringBatteryOptimizations(app.packageName)) },
        batteryPercent = fact { percentOf(battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)) },
        charging = fact { chargingOf(battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)) },
        thermal = fact { thermalOf(power.currentThermalStatus) },
    )
}

private inline fun <T> fact(read: () -> Fact<T>): Fact<T> =
    runCatchingCancellable { read() }.getOrElse { Fact.Failed(it.message ?: it::class.java.simpleName) }

private fun bucketOf(bucket: Int): Fact<StandbyBucket> = when (bucket) {
    UsageStatsManager.STANDBY_BUCKET_ACTIVE -> Fact.Known(StandbyBucket.ACTIVE)
    UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> Fact.Known(StandbyBucket.WORKING_SET)
    UsageStatsManager.STANDBY_BUCKET_FREQUENT -> Fact.Known(StandbyBucket.FREQUENT)
    UsageStatsManager.STANDBY_BUCKET_RARE -> Fact.Known(StandbyBucket.RARE)
    UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> Fact.Known(StandbyBucket.RESTRICTED)
    else -> Fact.Failed("an unknown standby bucket: $bucket")
}

// `Integer.MIN_VALUE` is the property's own "not supported here" answer.
private fun percentOf(capacity: Int): Fact<Int> =
    if (capacity in 0..100) Fact.Known(capacity) else Fact.Failed("the battery level is unknown ($capacity)")

private fun chargingOf(status: Int): Fact<Charging> = when (status) {
    BatteryManager.BATTERY_STATUS_CHARGING -> Fact.Known(Charging.CHARGING)
    BatteryManager.BATTERY_STATUS_FULL -> Fact.Known(Charging.FULL)
    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> Fact.Known(Charging.NOT_CHARGING)
    BatteryManager.BATTERY_STATUS_DISCHARGING -> Fact.Known(Charging.UNPLUGGED)
    else -> Fact.Failed("the battery state is unknown ($status)")
}

private fun thermalOf(status: Int): Fact<Thermal> = when (status) {
    PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> Fact.Known(Thermal.NOMINAL)
    PowerManager.THERMAL_STATUS_MODERATE -> Fact.Known(Thermal.FAIR)
    PowerManager.THERMAL_STATUS_SEVERE -> Fact.Known(Thermal.SERIOUS)
    PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN ->
        Fact.Known(Thermal.CRITICAL)
    else -> Fact.Failed("an unknown thermal status: $status")
}
