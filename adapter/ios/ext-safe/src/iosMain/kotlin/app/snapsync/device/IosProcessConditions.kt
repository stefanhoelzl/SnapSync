package app.snapsync.device

import app.snapsync.domain.report.DeviceConditionsReading
import app.snapsync.domain.report.Fact
import app.snapsync.domain.report.Thermal
import app.snapsync.platform.PlatformCalls
import app.snapsync.ports.DeviceConditions
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoThermalState
import platform.Foundation.isLowPowerModeEnabled
import platform.Foundation.thermalState

/**
 * The iOS [DeviceConditions] any process may read: Low Power Mode and the thermal state, from `NSProcessInfo`. The
 * upload extension's own — Background App Refresh and the battery are `UIApplication`'s and `UIDevice`'s, which an app
 * extension may not link — and what the app's `IosDeviceConditions` adds those to.
 *
 * What this process cannot read answers `Fact.Unsupported`, as a fact the platform has no notion of does: the delivery
 * context leaves it out either way (`ConditionFacts`), and the same device's app requests carry it. Android's standby
 * bucket and battery-optimisation exemption have no iOS counterpart at all.
 */
class IosProcessConditions : DeviceConditions {
    override suspend fun read(): DeviceConditionsReading {
        val process = NSProcessInfo.processInfo
        return DeviceConditionsReading(
            powerSaving = fact { process.isLowPowerModeEnabled() },
            backgroundRefresh = Fact.Unsupported,
            standbyBucket = Fact.Unsupported,
            batteryOptimizationExempt = Fact.Unsupported,
            batteryPercent = Fact.Unsupported,
            charging = Fact.Unsupported,
            thermal = fact { thermalOf(process.thermalState) },
        )
    }
}

private fun <T> fact(read: () -> T): Fact<T> =
    PlatformCalls.attempt { Fact.Known(read()) }.getOrElse { Fact.Failed(it.message ?: it::class.simpleName.orEmpty()) }

// Every case the SDK declares. A table rather than `when`: the metadata compile sees it as an `expect` enum and demands
// an `else`, which each target's compile then rejects as redundant — and a case Apple adds later misses the table and
// is reported as failed rather than guessed.
private val THERMAL = mapOf(
    NSProcessInfoThermalState.NSProcessInfoThermalStateNominal to Thermal.NOMINAL,
    NSProcessInfoThermalState.NSProcessInfoThermalStateFair to Thermal.FAIR,
    NSProcessInfoThermalState.NSProcessInfoThermalStateSerious to Thermal.SERIOUS,
    NSProcessInfoThermalState.NSProcessInfoThermalStateCritical to Thermal.CRITICAL,
)

private fun thermalOf(state: NSProcessInfoThermalState): Thermal =
    THERMAL[state] ?: error("an unknown thermal state: $state")
