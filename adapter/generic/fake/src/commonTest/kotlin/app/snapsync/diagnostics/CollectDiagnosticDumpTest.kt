package app.snapsync.diagnostics

import app.snapsync.fake.InMemoryDeviceLogSource
import app.snapsync.fake.InMemoryDownloadStore
import app.snapsync.fake.InMemoryLedgerStore
import app.snapsync.feature.diagnostics.CollectDiagnosticDump
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureDate
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.Direction
import app.snapsync.model.DiagnosticEnvironment
import app.snapsync.model.EventConfig
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.PermissionStatus
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.DeviceLogSource
import app.snapsync.ports.PhotoAccessStatusSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The dump assembly (capability `diagnostic-logging`), against the honest in-memory doubles.
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
    private val SCREEN = "Joined"

    private fun logLines(prefix: String, count: Int): String =
        (1..count).joinToString("") { "$prefix line $it padded out to a realistic width ........\n" }

    private fun collector(
        appLog: String? = null,
        extLog: String? = null,
        config: EventConfig? = null,
        permission: PermissionStatus = PermissionStatus.GRANTED,
        ledger: InMemoryLedgerStore = InMemoryLedgerStore(),
        downloads: InMemoryDownloadStore = InMemoryDownloadStore(),
        environment: DiagnosticEnvironment = DiagnosticEnvironment.UNKNOWN,
    ) = CollectDiagnosticDump(
        environment = environment,
        logs = InMemoryDeviceLogSource(
            buildMap {
                appLog?.let { put(DeviceLogSource.Process.APP, it) }
                extLog?.let { put(DeviceLogSource.Process.EXTENSION, it) }
            },
        ),
        ledger = ledger,
        downloads = downloads,
        config = object : ConfigSource {
            override val config: StateFlow<EventConfig?> = MutableStateFlow(config)
        },
        permission = object : PhotoAccessStatusSource {
            override val permission: StateFlow<PermissionStatus> = MutableStateFlow(permission)
        },
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
            permission = PermissionStatus.LIMITED,
            environment = DiagnosticEnvironment(
                appVersion = "0.2",
                buildNumber = "512",
                osVersion = "iOS 26.5",
                deviceModel = "iPhone12,8",
                uploadTier = "photokit",
                uploadBase = "https://snapsync.stho.net/api/v2",
                reporterEnvironment = "production",
            ),
        ).collect(NOTE, SCREEN)

        assertEquals("512", dump.state["build"])
        assertEquals("photokit", dump.state["upload_tier"])
        assertEquals("https://snapsync.stho.net/api/v2", dump.state["upload_base"])
        assertEquals("LIMITED", dump.state["photo_permission"])
        assertEquals("true", dump.state["joined"])
        assertEquals("5b6f0c62-3f4a-4a1e-9a2d-8f0a1b2c3d4e", dump.state["event_id"])
        assertEquals("Both", dump.state["direction"])
    }

    @Test
    fun `a partial grant never reports a selection size`() = runTest {
        // No shipped read makes that count available to this feature, so reporting it would mean
        // adding a seam for diagnostics alone — which `diagnostic-logging` forbids: a dump reads no
        // data the app does not already read.
        val dump = collector(permission = PermissionStatus.LIMITED).collect(NOTE, SCREEN)

        assertTrue(
            dump.state.keys.none { "selection" in it },
            "the state section named a selection: ${dump.state.keys}",
        )
    }

    @Test
    fun `an unjoined device reports no membership fields`() = runTest {
        val dump = collector(config = null).collect(NOTE, SCREEN)

        assertEquals("false", dump.state["joined"])
        assertFalse("event_id" in dump.state)
    }

    @Test
    fun `the ledger section is five labelled counts and no rows`() = runTest {
        val ledger = InMemoryLedgerStore()
        ledger.put(LedgerEntry("a.jpg", "asset-1", LedgerState.COMPLETED, attempt = 1, eventId = "e"))
        ledger.put(LedgerEntry("b.jpg", "asset-2", LedgerState.REQUESTED, attempt = 1, eventId = "e"))

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
        val dump = collector(appLog = "app\n").collect(NOTE, "Reconfigure")

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
