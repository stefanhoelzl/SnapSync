package app.snapsync.diagnostics

import app.snapsync.feature.support.LEDGER_EVENT
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.mock.inMemoryFiles
import app.snapsync.feature.support.configService
import app.snapsync.feature.support.galleryAccess
import app.snapsync.model.APP_LOG_FILE_NAME
import app.snapsync.model.EXTENSION_LOG_FILE_NAME
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.ledger.LedgerService
import app.snapsync.feature.diagnostics.CollectDiagnosticDump
import app.snapsync.model.AssetId
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureDate
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.Direction
import app.snapsync.model.DiagnosticEnvironment
import app.snapsync.model.EventConfig
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.GalleryAccess
import app.snapsync.services.logs.LogTailService
import app.snapsync.mock.DeviceConditionsMock
import app.snapsync.mock.inMemoryNetworkMonitor
import app.snapsync.model.AppFacts
import app.snapsync.model.DIAGNOSTIC_FAILURE_REASON_CHARS
import app.snapsync.model.DeviceConditionsReading
import app.snapsync.model.DiagnosticKeys
import app.snapsync.model.Fact
import app.snapsync.model.NetworkAccess
import app.snapsync.model.ReportContext
import app.snapsync.model.StandbyBucket
import app.snapsync.services.device.DeviceConditionsReadings
import app.snapsync.services.network.NetworkReadings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The dump assembly (capability `privacy-security`), against the honest in-memory doubles.
 *
 * The budget is the load-bearing property: an over-budget dump is rejected by the reporting server
 * and swallowed by the SDK, so it arrives nowhere and says nothing — the one failure mode the device
 * cannot observe. Every case here is really about that bound holding.
 */
class CollectDiagnosticDumpTest {

    private val budget = 1_000

    /** What the operator wrote — already trimmed and bounded by the sheet before it reaches here. */
    private val NOTE = "photos stopped arriving after I rejoined"

    /** The surface the report was written from — an opaque label the UI supplies. */
    private val SCREEN = ReportContext("Joined")

    private val APP_FACTS = AppFacts(
        deviceId = Fact.Known("A1B2C3D4-0000-4000-8000-000000000001"),
        timeZone = Fact.Known("Europe/Vienna"),
        memoryFootprintMb = Fact.Known(142),
        selectionPhotos = Fact.Unsupported,
    )

    private fun logLines(prefix: String, count: Int): String =
        (1..count).joinToString("") { "$prefix line $it padded out to a realistic width ........\n" }

    private fun collector(
        appLog: String? = null,
        extLog: String? = null,
        config: EventConfig? = null,
        permission: GalleryAccess = GalleryAccess.GRANTED,
        ledger: LedgerService = LedgerService(inMemoryDatabases()) { LEDGER_EVENT },
        downloads: DownloadService = DownloadService(inMemoryDatabases()),
        environment: DiagnosticEnvironment = DiagnosticEnvironment.UNKNOWN,
        network: NetworkAccess = NetworkAccess.Online(restricted = false),
        conditions: DeviceConditionsMock = DeviceConditionsMock(),
        appFacts: () -> AppFacts = { APP_FACTS },
    ) = CollectDiagnosticDump(
        environment = environment,
        // The REAL log tail over the two log files: the app's in its private area, the extension's in the shared one.
        logs = LogTailService(
            inMemoryFiles(
                shared = listOfNotNull(extLog?.let { EXTENSION_LOG_FILE_NAME to it.encodeToByteArray() }).toMap(mutableMapOf()),
                private = listOfNotNull(appLog?.let { APP_LOG_FILE_NAME to it.encodeToByteArray() }).toMap(mutableMapOf()),
            ),
        ),
        ledger = ledger,
        downloads = downloads,
        config = configService(config),
        permission = galleryAccess(MutableStateFlow(permission)),
        network = NetworkReadings(inMemoryNetworkMonitor(MutableStateFlow(network))),
        conditions = DeviceConditionsReadings(conditions.port()),
        appFacts = appFacts,
        uploadFacts = { mapOf("extension_registrable" to "false", "app_admission" to "Admit") },
        budgetBytes = budget,
    )

    @Test
    fun `two oversized logs never exceed the budget`() = runTest {
        val dump = collector(appLog = logLines("app", 500), extLog = logLines("ext", 500)).collect(NOTE, SCREEN)

        assertTrue(
            dump.logBytes <= budget,
            "carried ${dump.logBytes} bytes of log against a $budget budget — an over-budget dump is " +
                "rejected at ingest and silently lost",
        )
    }

    @Test
    fun `a short extension log leaves its slack to the app log`() = runTest {
        val extension = "ext one line\n"
        val dump = collector(appLog = logLines("app", 500), extLog = extension).collect(NOTE, SCREEN)

        assertEquals(extension, dump.extensionLog)
        assertTrue(
            dump.appLog.encodeToByteArray().size > budget / 2,
            "the app log took only ${dump.appLog.encodeToByteArray().size} bytes: unused extension " +
                "budget must be borrowed, or the common device (extension barely runs) wastes half a dump",
        )
        assertTrue(dump.logBytes <= budget)
    }

    @Test
    fun `tails begin at a line boundary`() = runTest {
        val dump = collector(appLog = logLines("app", 500), extLog = logLines("ext", 500)).collect(NOTE, SCREEN)

        assertTrue(dump.appLog.startsWith("app line "), "app tail began mid-line: ${dump.appLog.take(40)}")
        assertTrue(dump.extensionLog.startsWith("ext line "), "extension tail began mid-line")
    }

    @Test
    fun `a log that has never been written comes back empty rather than absent`() = runTest {
        val dump = collector(appLog = "app only\n", extLog = null).collect(NOTE, SCREEN)

        assertEquals("", dump.extensionLog)
        assertEquals("app only\n", dump.appLog)
    }

    @Test
    fun `the state section names the build and tier and membership`() = runTest {
        val dump = collector(
            config = EventConfig(
                eventId = "5b6f0c62-3f4a-4a1e-9a2d-8f0a1b2c3d4e",
                name = "Anna's Birthday",
                minPhotoDate = CaptureCutoff(CaptureDate("2026-07-01T00:00:00Z")),
                maxPhotoDate = CaptureCeiling(CaptureDate("2026-07-08T00:00:00Z")),
                direction = Direction.Both,
                saveToAlbum = true,
            ),
            permission = GalleryAccess.LIMITED,
            environment = DiagnosticEnvironment(
                appVersion = "0.2",
                buildNumber = "512",
                osVersion = "iOS 26.5",
                deviceModel = "iPhone12,8",
                uploadTier = "app+extension",
                uploadBase = "https://snapsync.stho.net/api/v2",
                reporterEnvironment = "production",
            ),
        ).collect(NOTE, SCREEN)

        assertEquals("512", dump.state["build"])
        assertEquals("app+extension", dump.state["uploaders_carried"])
        assertEquals("false", dump.state["extension_registrable"], "the upload facts, not a single tier")
        assertEquals("Admit", dump.state["app_admission"])
        assertEquals(null, dump.state["upload_tier"], "both uploaders may be active: there is no single tier")
        assertEquals("https://snapsync.stho.net/api/v2", dump.state["upload_base"])
        assertEquals("LIMITED", dump.state["photo_permission"])
        assertEquals("true", dump.state["joined"])
        assertEquals("5b6f0c62-3f4a-4a1e-9a2d-8f0a1b2c3d4e", dump.state["event_id"])
        assertEquals("Both", dump.state["direction"])
    }

    @Test
    fun `the device's state is written under the report's keys`() = runTest {
        val conditions = DeviceConditionsMock()
        conditions.operator.reading = DeviceConditionsMock.TYPICAL.copy(powerSaving = Fact.Known(true))
        val dump = collector(network = NetworkAccess.Online(restricted = true), conditions = conditions).collect(NOTE, SCREEN)

        assertEquals("online_restricted", dump.state[DiagnosticKeys.NETWORK])
        assertEquals("true", dump.state[DiagnosticKeys.POWER_SAVING])
        assertEquals("available", dump.state[DiagnosticKeys.BACKGROUND_REFRESH])
        assertEquals("80", dump.state[DiagnosticKeys.BATTERY_PERCENT])
        assertEquals("unplugged", dump.state[DiagnosticKeys.BATTERY_CHARGING])
        assertEquals("nominal", dump.state[DiagnosticKeys.THERMAL])
        assertEquals("A1B2C3D4-0000-4000-8000-000000000001", dump.state[DiagnosticKeys.DEVICE_ID])
        assertEquals("Europe/Vienna", dump.state[DiagnosticKeys.TIME_ZONE])
        assertEquals("142", dump.state[DiagnosticKeys.MEMORY_FOOTPRINT_MB])
    }

    @Test
    fun `the network is read fresh — so an offline device says so at once`() = runTest {
        // The screen's notice waits out a grace period before it says offline; a report read at that moment would
        // still claim a network. The report reads the platform's own first answer instead.
        val dump = collector(network = NetworkAccess.Offline).collect(NOTE, SCREEN)

        assertEquals("offline", dump.state[DiagnosticKeys.NETWORK])
    }

    @Test
    fun `a fact the platform does not have is left out`() = runTest {
        // The default device is an iPhone: no standby bucket, no optimisation exemption.
        val dump = collector().collect(NOTE, SCREEN)

        assertFalse(DiagnosticKeys.STANDBY_BUCKET in dump.state)
        assertFalse(DiagnosticKeys.BATTERY_OPTIMIZATION_EXEMPT in dump.state)
        assertFalse(DiagnosticKeys.SELECTION_PHOTOS in dump.state, "a full grant has no selection to count")
    }

    @Test
    fun `a failed fact is written failed with its reason — cut to the bound`() = runTest {
        val conditions = DeviceConditionsMock()
        conditions.operator.reading = DeviceConditionsMock.TYPICAL.copy(
            thermal = Fact.Failed("x".repeat(500)),
            standbyBucket = Fact.Known(StandbyBucket.RARE),
        )
        val dump = collector(conditions = conditions).collect(NOTE, SCREEN)

        assertEquals("failed (${"x".repeat(DIAGNOSTIC_FAILURE_REASON_CHARS)})", dump.state[DiagnosticKeys.THERMAL])
        assertEquals("rare", dump.state[DiagnosticKeys.STANDBY_BUCKET])
    }

    @Test
    fun `a device read that never answers fails its facts and the report still goes`() = runTest {
        val conditions = DeviceConditionsMock().also { it.operator.holding = true }
        val dump = collector(appLog = "app\n", conditions = conditions).collect(NOTE, SCREEN)

        val expected = "failed (the device conditions read timed out after ${CollectDiagnosticDump.DEFAULT_READ_TIMEOUT})"
        assertEquals(expected, dump.state[DiagnosticKeys.POWER_SAVING])
        assertEquals(expected, dump.state[DiagnosticKeys.THERMAL])
        assertEquals("online", dump.state[DiagnosticKeys.NETWORK], "the other reads are not held up by it")
        assertEquals("app\n", dump.appLog)
    }

    @Test
    fun `facts the composition cannot supply are written failed`() = runTest {
        val dump = collector(appFacts = { error("identity store gone") }).collect(NOTE, SCREEN)

        assertEquals("failed (identity store gone)", dump.state[DiagnosticKeys.DEVICE_ID])
        assertEquals("failed (identity store gone)", dump.state[DiagnosticKeys.TIME_ZONE])
    }

    @Test
    fun `a partial grant reports the selection's size`() = runTest {
        val dump = collector(
            permission = GalleryAccess.LIMITED,
            appFacts = { APP_FACTS.copy(selectionPhotos = Fact.Known(12)) },
        ).collect(NOTE, SCREEN)

        assertEquals("12", dump.state[DiagnosticKeys.SELECTION_PHOTOS])
    }

    @Test
    fun `what the screen showed is carried verbatim`() = runTest {
        val shown = mapOf(DiagnosticKeys.SHOWN_SHARED to "3/10", DiagnosticKeys.SHOWN_RECEIVED to "off")
        val dump = collector().collect(NOTE, ReportContext("Joined", shown))

        assertEquals("3/10", dump.state[DiagnosticKeys.SHOWN_SHARED])
        assertEquals("off", dump.state[DiagnosticKeys.SHOWN_RECEIVED])
    }

    /**
     * The state section's share of the whole-event sum (`DiagnosticDump.kt`'s table): ≤ 4,000 bytes with the note.
     * Built at its worst — every fact failed at the longest reason, a 100-char event name, a 200-char note — so a later
     * field that would push the event over the reporting server's ceiling fails here rather than on a phone.
     */
    @Test
    fun `the state section at its worst stays inside its row of the event budget`() = runTest {
        val longest = "y".repeat(500)
        val conditions = DeviceConditionsMock().also { it.operator.reading = DeviceConditionsReading.failed(longest) }
        val failed = Fact.Failed(longest)
        val dump = collector(
            config = EventConfig(
                eventId = "5b6f0c62-3f4a-4a1e-9a2d-8f0a1b2c3d4e",
                name = "n".repeat(100),
                minPhotoDate = CaptureCutoff(CaptureDate("2026-07-01T00:00:00Z")),
                maxPhotoDate = CaptureCeiling(CaptureDate("2026-07-08T00:00:00Z")),
                direction = Direction.Both,
                saveToAlbum = true,
            ),
            conditions = conditions,
            appFacts = { AppFacts(failed, failed, failed, failed) },
            environment = DiagnosticEnvironment(
                appVersion = "10.20", buildNumber = "99999", osVersion = "iOS 26.6.2 (Build 23G100)",
                deviceModel = "iPhone17,2", uploadTier = "app+extension",
                uploadBase = "https://snapsync.stho.net/api/v2", reporterEnvironment = "production",
            ),
        ).collect("z".repeat(200), ReportContext("Joined", mapOf(DiagnosticKeys.SHOWN_SHARED to "99999/99999")))

        val bytes = dump.note.encodeToByteArray().size +
            (dump.state + dump.ledger).entries.sumOf { (k, v) -> k.encodeToByteArray().size + v.encodeToByteArray().size + 6 }
        assertTrue(bytes <= 4_000, "the note, state and ledger came to $bytes bytes against their 4,000-byte row")
    }

    @Test
    fun `an unjoined device reports no membership fields`() = runTest {
        val dump = collector(config = null).collect(NOTE, SCREEN)

        assertEquals("false", dump.state["joined"])
        assertFalse("event_id" in dump.state)
    }

    @Test
    fun `the ledger section is five labelled counts and no rows`() = runTest {
        val ledger = LedgerService(inMemoryDatabases()) { LEDGER_EVENT }
        ledger.recordUnlessSettled(LedgerEntry("a.jpg", AssetId("asset-1"), LedgerState.COMPLETED))
        ledger.recordUnlessSettled(LedgerEntry("b.jpg", AssetId("asset-2"), LedgerState.REQUESTED))

        val dump = collector(ledger = ledger).collect(NOTE, SCREEN)

        assertEquals(
            setOf(
                "photos_pending", "photos_completed",
                "downloads_imported", "downloads_assets", "downloads_in_flight",
            ),
            dump.ledger.keys,
            "the ledger section grew beyond the five existing counts — row lists are unbounded on the " +
                "stuck device worth dumping from",
        )
        assertEquals("1", dump.ledger["photos_pending"])
        assertEquals("1", dump.ledger["photos_completed"])
    }

    @Test
    fun `the state section names the screen the report came from`() = runTest {
        // The one fact no other section can carry: a screen-local surface touches no port, so it
        // reaches neither the ledger nor a log line.
        val dump = collector(appLog = "app\n").collect(NOTE, ReportContext("Reconfigure"))

        assertEquals("Reconfigure", dump.state["screen"])
    }

    @Test
    fun `the note is carried unchanged`() = runTest {
        // Neither trimmed nor truncated here: the input component owns the bound, and two owners of
        // one number disagree eventually. Whatever the sheet sent is what the report carries.
        val written = "  spaces and a 240-char-ish sentence that the sheet already decided to allow  "
        val dump = collector(appLog = "app\n").collect(written, SCREEN)

        assertEquals(written, dump.note)
    }

    @Test
    fun `the note does not eat the log budget`() = runTest {
        // The budget bounds LOG bytes. If the note were ever subtracted from it, a long note would
        // silently shorten the tails — the diagnostic quietly degrading the diagnostic.
        val long = "x".repeat(400)
        val withNote = collector(appLog = logLines("app", 500), extLog = logLines("ext", 500))
            .collect(long, SCREEN)
        val withoutNote = collector(appLog = logLines("app", 500), extLog = logLines("ext", 500))
            .collect("", SCREEN)

        assertEquals(withoutNote.appLog, withNote.appLog)
        assertEquals(withoutNote.extensionLog, withNote.extensionLog)
        assertTrue(withNote.logBytes <= budget)
    }
}
