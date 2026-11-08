package app.snapsync.domain.report

/** The state-section keys a report's device and screen facts are written under. */
object DiagnosticKeys {
    const val NETWORK = "network"

    /** The device's mobile-data choice: `on`, `off` or `unreadable`. */
    const val MOBILE_DATA = "mobile_data"
    const val POWER_SAVING = "power_saving"
    const val BACKGROUND_REFRESH = "background_refresh"
    const val STANDBY_BUCKET = "standby_bucket"
    const val BATTERY_OPTIMIZATION_EXEMPT = "battery_optimization_exempt"
    const val BATTERY_PERCENT = "battery_percent"
    const val BATTERY_CHARGING = "battery_charging"
    const val THERMAL = "thermal"
    const val TIME_ZONE = "time_zone"
    const val MEMORY_FOOTPRINT_MB = "memory_footprint_mb"
    const val SELECTION_PHOTOS = "selection_photos"
    const val SHOWN_SHARED = "shown_shared"
    const val SHOWN_RECEIVED = "shown_received"
}
