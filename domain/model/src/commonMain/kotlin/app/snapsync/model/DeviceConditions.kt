package app.snapsync.model

/**
 * One fact a bug report carries about the device or the app (capability `privacy-security`), as it could be read at
 * the moment the user confirmed the report.
 *
 * Three cases because a report renders them three ways: a [Known] value is written, an [Unsupported] fact — one the
 * platform does not have at all, Android's standby bucket on an iPhone — is left out, and a [Failed] read is written
 * as failed with its [Failed.reason], so a reading that could not be taken is never mistaken for one that does not
 * exist. The one renderer is `CollectDiagnosticDump`.
 */
sealed interface Fact<out T> {
    data class Known<out T>(val value: T) : Fact<T>

    data object Unsupported : Fact<Nothing>

    data class Failed(val reason: String) : Fact<Nothing>
}

/** Whether the system lets this app refresh in the background — iOS's per-app Background App Refresh. */
enum class BackgroundRefresh(val label: String) {
    AVAILABLE("available"),

    /** The user switched it off, for this app or for every app. */
    DENIED("denied"),

    /** Not the user's to change: a device-management profile, or parental controls. */
    RESTRICTED("restricted"),
}

/** Android's app standby bucket — how often the system lets this app run jobs and alarms. */
enum class StandbyBucket(val label: String) {
    ACTIVE("active"),
    WORKING_SET("working_set"),
    FREQUENT("frequent"),
    RARE("rare"),
    RESTRICTED("restricted"),
}

/** Whether the device is on external power. */
enum class Charging(val label: String) {
    CHARGING("charging"),
    FULL("full"),

    /** On power but not charging — Android's own state (a weak charger, or a battery held below full). */
    NOT_CHARGING("not_charging"),
    UNPLUGGED("unplugged"),
}

/**
 * How hot the device is, on iOS's four-step scale. Android's finer thermal statuses map onto it
 * (none/light → nominal, moderate → fair, severe → serious, critical/emergency/shutdown → critical).
 */
enum class Thermal(val label: String) {
    NOMINAL("nominal"),
    FAIR("fair"),
    SERIOUS("serious"),
    CRITICAL("critical"),
}

/**
 * What the operating system says about the device's power and background allowance — the `DeviceConditions` port's
 * one reading, taken only for a bug report (capability `privacy-security`). Each field is a [Fact]: a platform
 * answers [Fact.Unsupported] for what it has no notion of, so neither adapter invents a value.
 */
data class DeviceConditionsReading(
    /** Low Power Mode on iOS, Battery Saver on Android. */
    val powerSaving: Fact<Boolean>,
    /** iOS only. */
    val backgroundRefresh: Fact<BackgroundRefresh>,
    /** Android only. */
    val standbyBucket: Fact<StandbyBucket>,
    /** Android only: whether the user exempted this app from battery optimisation. */
    val batteryOptimizationExempt: Fact<Boolean>,
    /** 0–100. */
    val batteryPercent: Fact<Int>,
    val charging: Fact<Charging>,
    val thermal: Fact<Thermal>,
) {
    companion object {
        /** Every fact failed for one [reason] — the whole read timed out or threw. */
        fun failed(reason: String): DeviceConditionsReading = Fact.Failed(reason).let {
            DeviceConditionsReading(it, it, it, it, it, it, it)
        }
    }
}

/**
 * The facts only the app composition holds, handed to a bug report as one value (capability `privacy-security`).
 * Each is a direct read the composition maps to a [Fact]; none is decided there.
 */
data class AppFacts(
    /** The device id the app already resolved — never minted for a report. */
    val deviceId: Fact<String>,
    /** The device's zone id, e.g. `Europe/Vienna`. */
    val timeZone: Fact<String>,
    /** This process's memory footprint in whole megabytes; unsupported where the platform's is not read. */
    val memoryFootprintMb: Fact<Long>,
    /** Under limited photo access, how many photos the selection holds; unsupported under any other grant. */
    val selectionPhotos: Fact<Int>,
)

/**
 * What the screen contributes to a bug report (capability `privacy-security`): the opaque label of the surface it was
 * sent from, and [shown] — what that surface displayed, keyed as the report keys it (`shown_shared`,
 * `shown_received`). Both recorded verbatim: a report exists to catch the screen disagreeing with the stores, so it
 * carries what was rendered rather than a re-derivation.
 */
data class ReportContext(
    val screen: String,
    val shown: Map<String, String> = emptyMap(),
    /**
     * The report was opened from "Report this" beside a refusal (capability `privacy-security`): only then does it carry
     * the refused verification's facts.
     */
    val verification: Boolean = false,
)
