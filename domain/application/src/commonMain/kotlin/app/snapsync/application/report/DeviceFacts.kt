package app.snapsync.application.report

import app.snapsync.data.gallery.GrantRead
import app.snapsync.data.mobiledata.MobileDataStore
import app.snapsync.data.network.NetworkReadings
import app.snapsync.data.report.DeviceConditionsRead
import app.snapsync.data.sharing.SelectionSnapshots
import app.snapsync.domain.mobiledata.StoredChoice
import app.snapsync.domain.network.NetworkAccess
import app.snapsync.domain.report.DeviceConditionsReading
import app.snapsync.domain.report.DiagnosticKeys
import app.snapsync.domain.report.Fact
import app.snapsync.domain.report.ReportFacts
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The device's facts a bug report carries, read now: the network — read fresh, not the screen's notice, which lags by
 * its grace period — and the device's conditions, each bounded by [readTimeout], so one that stalls is written failed
 * and the others are still read; the mobile-data choice, the time zone, the footprint and, under a partial grant, the
 * selection's size beside them.
 */
class DeviceFacts(
    private val network: NetworkReadings,
    private val mobileData: MobileDataStore,
    private val conditions: DeviceConditionsRead,
    private val selection: SelectionSnapshots,
    private val grant: GrantRead,
    /** How long one device read may take before it is written failed. The slowest is a hop onto the main thread. */
    private val readTimeout: Duration = DEFAULT_READ_TIMEOUT,
) {
    suspend fun read(): Map<String, String> {
        val access = network()
        val reading = conditions()
        return buildMap {
            put(DiagnosticKeys.NETWORK, access) { it.label }
            put(DiagnosticKeys.MOBILE_DATA, mobileData.stored().label)
            putConditions(reading)
            put(DiagnosticKeys.TIME_ZONE, conditions.zone())
            put(DiagnosticKeys.MEMORY_FOOTPRINT_MB, ReportFacts.megabytes(conditions.footprint())) { it.toString() }
            val selected = ReportFacts.selectionPhotos(grant.grant(), selection.selectedPhotos)
            put(DiagnosticKeys.SELECTION_PHOTOS, selected) { it.toString() }
        }
    }

    /** The platform's first answer, or failed when it gives none in time. */
    private suspend fun network(): Fact<NetworkAccess> =
        withTimeoutOrNull(readTimeout) { network.watch().firstOrNull() }?.let { Fact.Known(it) }
            ?: Fact.Failed(stalled("network"))

    /** One read: when it stalls, every fact of it is failed. */
    private suspend fun conditions(): DeviceConditionsReading =
        withTimeoutOrNull(readTimeout) { conditions.read() }
            ?: DeviceConditionsReading.failed(stalled("device conditions"))

    private fun stalled(what: String) = "the $what read timed out after $readTimeout"

    private fun MutableMap<String, String>.putConditions(reading: DeviceConditionsReading) {
        put(DiagnosticKeys.POWER_SAVING, reading.powerSaving) { it.toString() }
        put(DiagnosticKeys.BACKGROUND_REFRESH, reading.backgroundRefresh) { it.label }
        put(DiagnosticKeys.STANDBY_BUCKET, reading.standbyBucket) { it.label }
        put(DiagnosticKeys.BATTERY_OPTIMIZATION_EXEMPT, reading.batteryOptimizationExempt) { it.toString() }
        put(DiagnosticKeys.BATTERY_PERCENT, reading.batteryPercent) { it.toString() }
        put(DiagnosticKeys.BATTERY_CHARGING, reading.charging) { it.label }
        put(DiagnosticKeys.THERMAL, reading.thermal) { it.label }
    }

    companion object {
        val DEFAULT_READ_TIMEOUT: Duration = 2.seconds
    }
}

/** How the report names a network access. */
private val NetworkAccess.label: String
    get() = when (this) {
        is NetworkAccess.Online -> if (restricted) "online_restricted" else "online"
        NetworkAccess.Offline -> "offline"
        NetworkAccess.Blocked -> "blocked"
    }

/** How the report names what the mobile-data choice holds: `on` (nothing stored is on), `off`, or `unreadable`. */
private val StoredChoice.label: String
    get() = when (this) {
        StoredChoice.ABSENT, StoredChoice.ON -> "on"
        StoredChoice.OFF -> "off"
        StoredChoice.OTHER, StoredChoice.UNREADABLE -> "unreadable"
    }
