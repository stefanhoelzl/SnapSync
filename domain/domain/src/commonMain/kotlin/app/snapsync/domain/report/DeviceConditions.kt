package app.snapsync.domain.report

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
 * What the operating system says about the device's power and background allowance — the `DeviceConditions` port's one
 * reading, taken only for a bug report. Each field is a [Fact]: a platform answers [Fact.Unsupported] for what it has
 * no notion of, so neither adapter invents a value.
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
