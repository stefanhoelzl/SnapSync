package app.snapsync.feature.diagnostics

import app.snapsync.model.DIAGNOSTIC_LOG_BUDGET_BYTES
import app.snapsync.model.DiagnosticDump
import app.snapsync.model.DiagnosticEnvironment
import app.snapsync.model.EventConfig
import app.snapsync.model.PermissionStatus
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.DeviceLogSource
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.LedgerStore
import app.snapsync.ports.PhotoAccessStatusSource

/**
 * Assemble one operator-initiated diagnostic dump (capability `diagnostic-logging`).
 *
 * **Reads only.** Nothing here writes a ledger row, a download row, or a config; the whole feature is
 * a projection of state the app already holds, taken at the moment the operator confirms.
 *
 * **No new port surface.** The ledger section is five integers that shipped code already reads —
 * `LedgerStore.aggregates()` plus the download store's three counts. Row lists were considered and
 * rejected: completed rows are only readable by loading all of them, and the backlog is unbounded
 * exactly on the stuck device worth dumping from (4,000 outstanding rows is ~400 KB, over half the
 * log budget) while carrying neither state nor attempt. The log says *why*; the counts say *how many*.
 */
class CollectDiagnosticDump(
    private val environment: DiagnosticEnvironment,
    private val logs: DeviceLogSource,
    private val ledger: LedgerStore,
    private val downloads: DownloadStore,
    private val config: ConfigSource,
    private val permission: PhotoAccessStatusSource,
    private val budgetBytes: Int = DIAGNOSTIC_LOG_BUDGET_BYTES,
) {

    /**
     * @param note what the operator wrote — already trimmed and length-bounded by the sheet that
     *   collected it. It is carried unchanged: this feature neither trims nor truncates, so the cap
     *   has exactly one owner (the input component) rather than two that can disagree.
     * @param screen what the operator was looking at, as an **opaque label supplied by the UI**. This
     *   zone does not enumerate screens — naming them here would put presentation's vocabulary in a
     *   feature — so whatever the caller passes is recorded verbatim.
     */
    suspend fun collect(note: String, screen: String): DiagnosticDump {
        val (appLog, extensionLog) = readLogsWithinBudget()
        return DiagnosticDump(
            note = note,
            state = stateSection(config.config.value, permission.permission.value, screen),
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
        val extension = logs.tail(DeviceLogSource.Process.EXTENSION, half).orEmpty()
        val appShare = budgetBytes - extension.encodeToByteArray().size
        val app = logs.tail(DeviceLogSource.Process.APP, appShare).orEmpty()
        return app to extension
    }

    /**
     * The facts a log tail may not contain — the boot lines that carry most of them roll off first,
     * because they are written once per process and the tail keeps the newest bytes.
     *
     * The membership is summarised, not dumped: which event, how it was configured, and what window
     * it shares. Under a partial photo grant the **size of the selection is deliberately absent** —
     * no shipped read makes that count available to this feature (the snapshot lives in `compose/`),
     * so reporting it would mean adding a seam for diagnostics alone, which this capability forbids:
     * a dump reads no data the app does not already read.
     */
    private fun stateSection(
        config: EventConfig?,
        permission: PermissionStatus,
        screen: String,
    ): Map<String, String> =
        buildMap {
            // What the operator was looking at. Most of it is inferable from the rest of the report —
            // but not the surfaces that are screen-local BY DESIGN (the reconfigure sheet, a pending
            // switch, which join phase): those touch no port, so they reach neither the ledger nor a
            // single log line. This field is the only place they appear.
            put("screen", screen)
            put("app_version", environment.appVersion)
            put("build", environment.buildNumber)
            put("os", environment.osVersion)
            put("device", environment.deviceModel)
            put("upload_tier", environment.uploadTier)
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
        return mapOf(
            "photos_pending" to aggregates.pending.toString(),
            "photos_completed" to aggregates.completed.toString(),
            "downloads_imported" to downloads.importedCount().toString(),
            "downloads_assets" to downloads.assetCount().toString(),
            "downloads_in_flight" to downloads.inFlightCount().toString(),
        )
    }
}
