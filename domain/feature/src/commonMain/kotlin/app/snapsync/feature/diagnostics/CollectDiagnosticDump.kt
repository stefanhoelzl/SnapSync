package app.snapsync.feature.diagnostics

import app.snapsync.services.gallery.GalleryAccessState
import app.snapsync.model.AppFacts
import app.snapsync.model.DIAGNOSTIC_FAILURE_REASON_CHARS
import app.snapsync.model.DIAGNOSTIC_LOG_BUDGET_BYTES
import app.snapsync.model.DeviceConditionsReading
import app.snapsync.model.DiagnosticDump
import app.snapsync.model.DiagnosticEnvironment
import app.snapsync.model.DiagnosticKeys
import app.snapsync.model.EventConfig
import app.snapsync.model.Fact
import app.snapsync.model.GalleryAccess
import app.snapsync.model.NetworkAccess
import app.snapsync.model.ReportContext
import app.snapsync.model.runCatchingCancellable
import app.snapsync.services.config.ConfigService
import app.snapsync.services.device.DeviceConditionsReadings
import app.snapsync.services.logs.LogTailService
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.ledger.LedgerService
import app.snapsync.services.network.NetworkReadings
import app.snapsync.services.settings.MobileDataSetting
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Assemble one operator-initiated diagnostic dump (capability `privacy-security`).
 *
 * **Reads only.** Nothing here writes a ledger row, a download row, a config or an identity; the whole feature is
 * a projection of the app's state and the device's, taken at the moment the operator confirms.
 *
 * **What a report may hold** is bounded by kind, not by source (capability `privacy-security`): this app's own state
 * and the device's settings and conditions — never a photo or what it shows, another app's data, a location beyond
 * the time zone, contacts, free storage or the device's name. A new field is tested against that bound. (It replaced
 * the older "a dump reads no data the app does not already read", which kept out exactly the device conditions that
 * explain a background upload that never ran; decision record `changes/archive/2026-10-05-device-state-in-bug-reports`.)
 *
 * **Every device fact is best-effort.** Each read runs under [readTimeout]; one that throws or stalls is written
 * `failed (<reason>)` and the report goes on. A fact the platform does not have is left out.
 *
 * **No new port surface.** The ledger section is five integers that shipped code already reads —
 * `LedgerService.aggregates()` plus the download store's three counts. Row lists were considered and
 * rejected: completed rows are only readable by loading all of them, and the backlog is unbounded
 * exactly on the stuck device worth dumping from (4,000 outstanding rows is ~400 KB, over half the
 * log budget) while carrying neither state nor attempt. The log says *why*; the counts say *how many*.
 */
class CollectDiagnosticDump(
    private val environment: DiagnosticEnvironment,
    private val logs: LogTailService,
    private val ledger: LedgerService,
    private val downloads: DownloadService,
    private val config: ConfigService,
    private val permission: GalleryAccessState,
    /** The device's network, read fresh for the report — not the screen's notice, which lags by its grace period. */
    private val network: NetworkReadings,
    /** The device's mobile-data choice — a device fact, so a report without an event carries it too. */
    private val mobileData: MobileDataSetting,
    /** The device's power, battery, thermal state and background allowance. */
    private val conditions: DeviceConditionsReadings,
    /** The facts only the composition holds — the device id, the zone, the footprint, the selection's size. */
    private val appFacts: () -> AppFacts,
    /**
     * The upload facts at the moment of the dump — whether the extension is registrable, and the app uploader's
     * admission — as labelled strings the composition derives from answers it already computes (capability
     * `privacy-security`). Both uploaders may be active at once, so there is no single tier to name
     * (decision record `changes/both-uploaders-active`).
     */
    private val uploadFacts: () -> Map<String, String>,
    private val budgetBytes: Int = DIAGNOSTIC_LOG_BUDGET_BYTES,
    /** How long one device read may take before it is written as failed. The slowest is a hop onto the main thread. */
    private val readTimeout: Duration = DEFAULT_READ_TIMEOUT,
) {

    /**
     * @param note what the operator wrote — already trimmed and length-bounded by the sheet that
     *   collected it. It is carried unchanged: this feature neither trims nor truncates, so the cap
     *   has exactly one owner (the input component) rather than two that can disagree.
     * @param context what the operator was looking at — the surface's **opaque label** and what it **showed**, both
     *   supplied by presentation. This zone does not enumerate screens — naming them here would put presentation's
     *   vocabulary in a feature — so both are recorded verbatim.
     */
    suspend fun collect(note: String, context: ReportContext): DiagnosticDump {
        val device = readDevice()
        val (appLog, extensionLog) = readLogsWithinBudget()
        return DiagnosticDump(
            note = note,
            state = stateSection(config.config.value, permission.grant.value, context) + device,
            ledger = ledgerSection(),
            appLog = appLog,
            extensionLog = extensionLog,
        )
    }

    /**
     * Both tails, sharing [budgetBytes] **greedily**: each may take half, and whatever one leaves
     * unused the other may take.
     *
     * The common device has an app log far larger than its extension log (the extension runs in short
     * bursts, or — on iOS 18–26.0 — does not exist at all). A fixed half-and-half split would throw
     * away most of the budget there, which is the case that matters most.
     */
    private suspend fun readLogsWithinBudget(): Pair<String, String> {
        val half = budgetBytes / 2
        val extension = logs.tail(LogTailService.Process.EXTENSION, half).orEmpty()
        val appShare = budgetBytes - extension.encodeToByteArray().size
        val app = logs.tail(LogTailService.Process.APP, appShare).orEmpty()
        return app to extension
    }

    /**
     * The facts a log tail may not contain — the boot lines that carry most of them roll off first,
     * because they are written once per process and the tail keeps the newest bytes.
     *
     * The membership is summarised, not dumped: which event, how it was configured, and what window
     * it shares.
     */
    private fun stateSection(
        config: EventConfig?,
        permission: GalleryAccess,
        context: ReportContext,
    ): Map<String, String> =
        buildMap {
            // What the operator was looking at. Most of it is inferable from the rest of the report —
            // but not the surfaces that are screen-local BY DESIGN (the reconfigure sheet, a pending
            // switch, which join phase): those touch no port, so they reach neither the ledger nor a
            // single log line. This field is the only place they appear.
            put("screen", context.screen)
            // What that surface showed — beside the ledger section's store counts, so the two disagree visibly.
            putAll(context.shown)
            put("app_version", environment.appVersion)
            put("build", environment.buildNumber)
            put("os", environment.osVersion)
            put("device", environment.deviceModel)
            put("uploaders_carried", environment.uploadTier)
            putAll(uploadFacts())
            put("upload_base", environment.uploadBase)
            put("reporter_environment", environment.reporterEnvironment)
            put("photo_permission", permission.name)
            put("joined", (config != null).toString())
            if (config != null) {
                put("event_id", config.eventId)
                put("event_name", config.name)
                put("direction", config.direction.name)
                put("shares_from", config.minPhotoDate.at.iso)
                put("shares_until", config.maxPhotoDate.at.iso)
                put("save_to_album", config.saveToAlbum.toString())
            }
        }

    /**
     * Counts only. The units are labelled because the two stores count different things and their
     * numbers legitimately disagree: the ledger aggregates count **photos** (a photo with any
     * outstanding resource is pending), while the log speaks of resource **rows**. Unlabelled, that
     * disagreement reads as a bug at 2am.
     */
    private suspend fun ledgerSection(): Map<String, String> {
        val aggregates = ledger.aggregates()
        // Both sides read in one round-trip each, so a dump reports one state of each store rather than a
        // composite of several — the disagreement this section's KDoc warns reads as a bug at 2am.
        val downloadCounts = downloads.counts()
        return mapOf(
            "photos_pending" to aggregates.pending.toString(),
            "photos_completed" to aggregates.completed.toString(),
            "downloads_imported" to downloadCounts.imported.toString(),
            "downloads_assets" to downloadCounts.stillArriving.toString(),
            "downloads_in_flight" to downloadCounts.inFlight.toString(),
        )
    }

    /**
     * The device facts, every one rendered by [put]: the network and the device conditions read concurrently, each
     * under [readTimeout], and the composition's facts beside them.
     */
    private suspend fun readDevice(): Map<String, String> = coroutineScope {
        val access = async { bounded("network") { network.watch().first() } }
        val reading = async { bounded("device conditions") { conditions.read() } }
        val app = runCatchingCancellable { appFacts() }.getOrElse { failure ->
            reasonOf(failure).let { AppFacts(Fact.Failed(it), Fact.Failed(it), Fact.Failed(it), Fact.Failed(it)) }
        }
        buildMap {
            put(DiagnosticKeys.NETWORK, access.await()) { it.label }
            put(DiagnosticKeys.MOBILE_DATA, mobileData.describe())
            putConditions(
                when (val read = reading.await()) {
                    is Fact.Known -> read.value
                    is Fact.Failed -> DeviceConditionsReading.failed(read.reason) // one read: its failure is every fact's
                    Fact.Unsupported -> DeviceConditionsReading.failed("unsupported")
                },
            )
            put(DiagnosticKeys.DEVICE_ID, app.deviceId) { it }
            put(DiagnosticKeys.TIME_ZONE, app.timeZone) { it }
            put(DiagnosticKeys.MEMORY_FOOTPRINT_MB, app.memoryFootprintMb) { it.toString() }
            put(DiagnosticKeys.SELECTION_PHOTOS, app.selectionPhotos) { it.toString() }
        }
    }

    private fun MutableMap<String, String>.putConditions(reading: DeviceConditionsReading) {
        put(DiagnosticKeys.POWER_SAVING, reading.powerSaving) { it.toString() }
        put(DiagnosticKeys.BACKGROUND_REFRESH, reading.backgroundRefresh) { it.label }
        put(DiagnosticKeys.STANDBY_BUCKET, reading.standbyBucket) { it.label }
        put(DiagnosticKeys.BATTERY_OPTIMIZATION_EXEMPT, reading.batteryOptimizationExempt) { it.toString() }
        put(DiagnosticKeys.BATTERY_PERCENT, reading.batteryPercent) { it.toString() }
        put(DiagnosticKeys.BATTERY_CHARGING, reading.charging) { it.label }
        put(DiagnosticKeys.THERMAL, reading.thermal) { it.label }
    }

    /**
     * The one rendering of a [Fact]: a known value as its label, an unsupported one LEFT OUT (the platform has no such
     * thing, and the report's `os` says which platform it was), a failed one as `failed (<reason>)` — so a reading that
     * could not be taken is never mistaken for one that does not exist.
     */
    private fun <T> MutableMap<String, String>.put(key: String, fact: Fact<T>, label: (T) -> String) {
        when (fact) {
            is Fact.Known -> put(key, label(fact.value))
            Fact.Unsupported -> Unit
            is Fact.Failed -> put(key, "failed (${fact.reason.take(DIAGNOSTIC_FAILURE_REASON_CHARS)})")
        }
    }

    /** [read] as a [Fact]: its value, or failed — it threw, or it took longer than [readTimeout]. */
    private suspend fun <T : Any> bounded(what: String, read: suspend () -> T): Fact<T> =
        runCatchingCancellable { withTimeoutOrNull(readTimeout) { read() } }.fold(
            onSuccess = { value -> value?.let { Fact.Known(it) } ?: Fact.Failed("the $what read timed out after $readTimeout") },
            onFailure = { Fact.Failed(reasonOf(it)) },
        )

    private fun reasonOf(failure: Throwable): String = failure.message ?: failure::class.simpleName.orEmpty()

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
