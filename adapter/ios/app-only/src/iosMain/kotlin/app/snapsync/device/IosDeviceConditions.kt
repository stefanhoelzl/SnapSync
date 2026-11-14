package app.snapsync.device

import app.snapsync.domain.report.BackgroundRefresh
import app.snapsync.domain.report.Charging
import app.snapsync.domain.report.DeviceConditionsReading
import app.snapsync.domain.report.Fact
import app.snapsync.platform.PlatformCalls
import app.snapsync.ports.DeviceConditions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundRefreshStatus
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState
import kotlin.math.roundToInt

/**
 * The iOS [DeviceConditions] of the app process: what any process reads ([IosProcessConditions] — Low Power Mode and
 * the thermal state, from `NSProcessInfo`), and over it the battery from `UIDevice` and Background App Refresh from
 * `UIApplication`. App-only, because those two are UIKit, which an app extension may not link.
 *
 * Android's standby bucket and battery-optimisation exemption have no iOS counterpart and answer unsupported.
 *
 * THE BATTERY. `UIDevice` reports the battery only while `batteryMonitoringEnabled` is on, and that switch is the whole
 * app's: so the read turns it on, reads, and puts it back as it found it, in one hop onto the main thread (where
 * `UIDevice` and `UIApplication` must be read), so no other main-thread code can see it switched. A device that cannot
 * say — the simulator, which has no battery — answers level `-1` and state unknown, read here as failed rather than as
 * a level of zero.
 */
class IosDeviceConditions : DeviceConditions {
    private val process = IosProcessConditions()

    override suspend fun read(): DeviceConditionsReading {
        val main = withContext(Dispatchers.Main) { MainThreadFacts.read() }
        return process.read().copy(
            backgroundRefresh = main.backgroundRefresh,
            batteryPercent = main.batteryPercent,
            charging = main.charging,
        )
    }
}

/** What only the main thread may read: Background App Refresh and the battery. */
private class MainThreadFacts(
    val backgroundRefresh: Fact<BackgroundRefresh>,
    val batteryPercent: Fact<Int>,
    val charging: Fact<Charging>,
) {
    companion object {
        fun read(): MainThreadFacts {
            val device = UIDevice.currentDevice
            val wasMonitoring = device.batteryMonitoringEnabled
            device.batteryMonitoringEnabled = true
            try {
                return MainThreadFacts(
                    backgroundRefresh = fact { refreshOf(UIApplication.sharedApplication.backgroundRefreshStatus) },
                    batteryPercent = levelOf(device.batteryLevel),
                    charging = chargingOf(device.batteryState),
                )
            } finally {
                device.batteryMonitoringEnabled = wasMonitoring
            }
        }
    }
}

private fun <T> fact(read: () -> T): Fact<T> =
    PlatformCalls.attempt {
        Fact.Known(
            read(),
        )
    }.getOrElse { Fact.Failed(it.message ?: it::class.simpleName.orEmpty()) }

// Each table names every case the SDK declares. Tables rather than `when`: the metadata compile sees these as `expect`
// enums and demands an `else`, which each target's compile then rejects as redundant — and a case Apple adds later
// misses the table and is reported as failed rather than guessed.

private val REFRESH = mapOf(
    UIBackgroundRefreshStatus.UIBackgroundRefreshStatusAvailable to BackgroundRefresh.AVAILABLE,
    UIBackgroundRefreshStatus.UIBackgroundRefreshStatusDenied to BackgroundRefresh.DENIED,
    UIBackgroundRefreshStatus.UIBackgroundRefreshStatusRestricted to BackgroundRefresh.RESTRICTED,
)

private val CHARGING = mapOf(
    UIDeviceBatteryState.UIDeviceBatteryStateCharging to Charging.CHARGING,
    UIDeviceBatteryState.UIDeviceBatteryStateFull to Charging.FULL,
    UIDeviceBatteryState.UIDeviceBatteryStateUnplugged to Charging.UNPLUGGED,
)

private fun refreshOf(status: UIBackgroundRefreshStatus): BackgroundRefresh =
    REFRESH[status] ?: error("an unknown background refresh status: $status")

private fun levelOf(level: Float): Fact<Int> =
    if (level < 0f) Fact.Failed("the battery level is unknown") else Fact.Known((level * 100).roundToInt())

// `UIDeviceBatteryStateUnknown` is the one declared case missing from the table: a device that cannot say.
private fun chargingOf(state: UIDeviceBatteryState): Fact<Charging> =
    CHARGING[state]?.let { Fact.Known(it) } ?: Fact.Failed("the battery state is unknown ($state)")
