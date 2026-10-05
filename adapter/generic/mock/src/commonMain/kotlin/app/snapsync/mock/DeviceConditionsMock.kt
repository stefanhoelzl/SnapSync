package app.snapsync.mock

import app.snapsync.model.BackgroundRefresh
import app.snapsync.model.Charging
import app.snapsync.model.DeviceConditionsReading
import app.snapsync.model.DiagnosticKeys
import app.snapsync.model.Fact
import app.snapsync.model.StandbyBucket
import app.snapsync.model.Thermal
import app.snapsync.ports.DeviceConditions
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The device's power, battery, thermal and background-allowance state as the operating system reports it to the app.
 * Opens as an unremarkable iPhone: power saving off, background refresh available, 80 % on battery, nominal, and the two
 * Android-only facts unsupported.
 */
class DeviceConditionsMock(reading: DeviceConditionsReading = TYPICAL) {
    internal val cell = MutableStateFlow(reading)

    /** While set, a read never answers — the platform call that stalls. A process's own, so not persisted. */
    internal val holding = MutableStateFlow(false)

    fun port(): DeviceConditions = InMemoryDeviceConditions(cell, holding)

    val operator: DeviceConditionsOperator = DeviceConditionsOperator(this)

    companion object {
        val TYPICAL: DeviceConditionsReading = DeviceConditionsReading(
            powerSaving = Fact.Known(false),
            backgroundRefresh = Fact.Known(BackgroundRefresh.AVAILABLE),
            standbyBucket = Fact.Unsupported,
            batteryOptimizationExempt = Fact.Unsupported,
            batteryPercent = Fact.Known(80),
            charging = Fact.Known(Charging.UNPLUGGED),
            thermal = Fact.Known(Thermal.NOMINAL),
        )
    }
}

class DeviceConditionsOperator internal constructor(private val mock: DeviceConditionsMock) {
    /** What the operating system reports — any field may be set unsupported or failed. */
    var reading: DeviceConditionsReading by mock.cell::value

    /** Leave every read unanswered until released — what a bug report's timeout is for. */
    var holding: Boolean by mock.holding::value
}

internal class InMemoryDeviceConditions(
    private val reading: MutableStateFlow<DeviceConditionsReading>,
    private val holding: MutableStateFlow<Boolean>,
) : DeviceConditions {
    override suspend fun read(): DeviceConditionsReading {
        if (holding.value) awaitCancellation()
        return reading.value
    }
}

/**
 * A [DeviceConditionsReading] as named texts, keyed by the bug report's own keys — what the mock's persisted state and
 * the rig's `device/conditions` lever both speak. A value is the field's label (`true`, `rare`, `42`, `serious`, …),
 * `unsupported`, or `failed:<reason>`.
 */
object DeviceConditionsText {
    private const val UNSUPPORTED = "unsupported"
    private const val FAILED = "failed:"

    fun encode(reading: DeviceConditionsReading): Map<String, String> = with(reading) {
        mapOf(
            DiagnosticKeys.POWER_SAVING to text(powerSaving) { it.toString() },
            DiagnosticKeys.BACKGROUND_REFRESH to text(backgroundRefresh) { it.label },
            DiagnosticKeys.STANDBY_BUCKET to text(standbyBucket) { it.label },
            DiagnosticKeys.BATTERY_OPTIMIZATION_EXEMPT to text(batteryOptimizationExempt) { it.toString() },
            DiagnosticKeys.BATTERY_PERCENT to text(batteryPercent) { it.toString() },
            DiagnosticKeys.BATTERY_CHARGING to text(charging) { it.label },
            DiagnosticKeys.THERMAL to text(thermal) { it.label },
        )
    }

    /** [base] with every field [values] names replaced; a value that does not parse throws, naming the field. */
    fun apply(base: DeviceConditionsReading, values: Map<String, String>): DeviceConditionsReading = with(base) {
        DeviceConditionsReading(
            powerSaving = fact(values, DiagnosticKeys.POWER_SAVING, powerSaving, String::toBooleanStrictOrNull),
            backgroundRefresh = fact(values, DiagnosticKeys.BACKGROUND_REFRESH, backgroundRefresh) { v ->
                BackgroundRefresh.entries.firstOrNull { it.label == v }
            },
            standbyBucket = fact(values, DiagnosticKeys.STANDBY_BUCKET, standbyBucket) { v ->
                StandbyBucket.entries.firstOrNull { it.label == v }
            },
            batteryOptimizationExempt =
                fact(values, DiagnosticKeys.BATTERY_OPTIMIZATION_EXEMPT, batteryOptimizationExempt, String::toBooleanStrictOrNull),
            batteryPercent = fact(values, DiagnosticKeys.BATTERY_PERCENT, batteryPercent) { v -> v.toIntOrNull()?.takeIf { it in 0..100 } },
            charging = fact(values, DiagnosticKeys.BATTERY_CHARGING, charging) { v -> Charging.entries.firstOrNull { it.label == v } },
            thermal = fact(values, DiagnosticKeys.THERMAL, thermal) { v -> Thermal.entries.firstOrNull { it.label == v } },
        )
    }

    /** The keys [apply] reads. */
    val keys: Set<String> get() = encode(DeviceConditionsMock.TYPICAL).keys

    private fun <T> text(fact: Fact<T>, label: (T) -> String): String = when (fact) {
        is Fact.Known -> label(fact.value)
        Fact.Unsupported -> UNSUPPORTED
        is Fact.Failed -> FAILED + fact.reason
    }

    private fun <T> fact(values: Map<String, String>, key: String, current: Fact<T>, parse: (String) -> T?): Fact<T> {
        val raw = values[key] ?: return current
        return when {
            raw == UNSUPPORTED -> Fact.Unsupported
            raw.startsWith(FAILED) -> Fact.Failed(raw.removePrefix(FAILED))
            else -> Fact.Known(requireNotNull(parse(raw)) { "$key: '$raw' is not a value, '$UNSUPPORTED' or '$FAILED<reason>'" })
        }
    }
}
