package app.snapsync.compose

import app.snapsync.domain.crash.CrashEvent
import app.snapsync.domain.crash.CrashOptions
import app.snapsync.domain.crash.CrashSections
import app.snapsync.domain.crash.CrashShaping
import app.snapsync.domain.crash.Crumb
import app.snapsync.domain.process.MemoryFootprint
import app.snapsync.domain.report.DumpHandOff
import app.snapsync.domain.report.DumpResult
import app.snapsync.domain.report.ReportDestination
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Log
import app.snapsync.inbound.LogLevel
import app.snapsync.mock.BuildInfoMock
import app.snapsync.mock.DeclaredVersion
import app.snapsync.mock.EntryEventsMock
import app.snapsync.mock.ThreadLabelMock
import app.snapsync.mock.fakeCrypto
import app.snapsync.mock.fixedClock
import app.snapsync.mock.inMemoryCrashReporter
import app.snapsync.mock.inMemoryFiles
import app.snapsync.mock.metricPayloadOf
import app.snapsync.ports.CrashReporter
import app.snapsync.ports.LogSink
import app.snapsync.ports.MetricHandlers
import app.snapsync.ports.ProcessMetricPayload
import app.snapsync.ports.ProcessMetrics
import app.snapsync.scheduling.Runtime
import app.snapsync.scheduling.RuntimePorts
import co.touchlab.kermit.Logger
import co.touchlab.kermit.platformLogWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What `snapSyncProcess` sets up (capability `privacy-security`), over the honest in-memory reporter: the channel
 * started as the process's first act where the build reports, and nothing at all where it does not; the log routed
 * into it; the process metrics listened to only with a live handler, and accounted for over the started channel.
 */
@OptIn(CompositionOnly::class)
class ProcessCompositionTest {

    private val started = MutableStateFlow(false)
    private val dumps = MutableStateFlow<List<CrashEvent>>(emptyList())
    private val events = MutableStateFlow<List<CrashEvent>>(emptyList())
    private val privateFiles = mutableMapOf<String, ByteArray>()

    /** A provider that hands over whatever it holds the moment something listens — MetricKit's one-shot shape. */
    private class HeldReports(private val held: List<ProcessMetricPayload>) : ProcessMetrics {
        var listens = 0

        @CompositionOnly
        override fun inbound(handlers: MetricHandlers) {
            listens++
            held.forEach(handlers.onReport::deliver)
        }
    }

    /** A reporter that records what reaches it, and what it was started with. */
    private class Recording : CrashReporter {
        val crumbs = mutableListOf<Crumb>()
        val captured = mutableListOf<CrashEvent>()
        val contexts = mutableMapOf<String, Map<String, String>>()
        var shaping: CrashShaping? = null

        override fun start(options: CrashOptions, shaping: CrashShaping) {
            this.shaping = shaping
        }

        override fun capture(event: CrashEvent) {
            captured += event
        }

        override fun breadcrumb(crumb: Crumb) {
            crumbs += crumb
        }

        override fun setContext(name: String, fields: Map<String, String>) {
            contexts[name] = fields
        }

        override suspend fun sendDump(dump: CrashEvent): DumpHandOff = DumpResult.Queued
    }

    private val written = mutableListOf<String>()

    private val sink = object : LogSink {
        override fun write(level: LogLevel, tag: String, line: String) {
            written += line
        }
    }

    @AfterTest
    fun handBackTheLoggingGlobals() {
        Log.install(emptyList())
        Logger.setLogWriters(platformLogWriter())
    }

    private fun ports(
        reporter: CrashReporter = inMemoryCrashReporter(started, dumps, events),
        dsn: String? = DSN,
        metrics: ProcessMetrics = NoProcessMetrics,
        sinks: List<LogSink> = emptyList(),
        build: BuildInfoMock = BuildInfoMock(dsn = dsn),
    ) = ProcessPorts(
        crashReporter = reporter,
        processMetrics = metrics,
        logSinks = sinks,
        files = inMemoryFiles(private = privateFiles),
        clock = fixedClock(kotlin.time.Instant.fromEpochSeconds(0)),
        crypto = fakeCrypto(),
        threadLabel = ThreadLabelMock(),
        entryEvents = EntryEventsMock().port(),
        build = build.port(),
    )

    /** The process's runtime, as its composition builds it: nothing here holds background time. */
    private fun runtime() = Runtime(
        null,
        Dispatchers.Unconfined,
        RuntimePorts(backgroundTime = null, ThreadLabelMock(), EntryEventsMock().port()),
    )

    @Test
    fun a_process_s_first_lines_name_it_its_build_and_the_backend_it_talks_to() {
        val build = BuildInfoMock(
            dsn = null,
            declaredVersion = DeclaredVersion("1.4"),
            buildNumber = "2512",
            processId = null,
        )
        snapSyncProcess(ports(sinks = listOf(sink), build = build), runtime())
        assertEquals(
            listOf(
                "[Info/process] === process start === process=ios build=1.4(2512)",
                "[Info/process] upload base host=${build.port().uploadHost}",
            ),
            written.take(2),
            "a process with no id of its own is named by its platform",
        )
    }

    @Test
    fun a_build_that_reports_starts_the_channel_first_and_routes_the_log_into_it() {
        val process = snapSyncProcess(ports(sinks = listOf(sink)), runtime())
        assertTrue(started.value, "reporting starts as the process's first act")
        assertEquals(ReportDestination.DEVELOPER, process.reportDestination, "a report is sent where it reports")
        Log("ExtensionPass").unlabelled.e("could not settle the presented jobs")
        assertEquals(
            listOf("could not settle the presented jobs"),
            events.value.map { it.message },
            "an Error is a report",
        )
    }

    @Test
    fun the_upload_extension_s_process_reports_an_error_it_logs() {
        // The extension composes the same process wiring as the app: an Error line it writes is a report.
        snapSyncProcess(ports(sinks = listOf(sink), build = BuildInfoMock(dsn = DSN, processId = EXTENSION)), runtime())
        Log("ExtensionPass").unlabelled.e("could not settle the presented jobs")
        assertTrue(started.value, "the extension's process reports")
        assertEquals(listOf("could not settle the presented jobs"), events.value.map { it.message })
    }

    @Test
    fun a_build_that_reports_nowhere_starts_nothing_and_routes_no_line_into_a_channel() {
        val process = snapSyncProcess(ports(dsn = null, sinks = listOf(sink)), runtime())
        assertFalse(started.value, "no destination, no channel — and no connection ever opened")
        assertEquals(ReportDestination.THIS_DEVICE, process.reportDestination, "a report is kept on the phone")
        assertNull(process.crash.lines, "a build that reports nowhere never constructs the crash writer")
    }

    @Test
    fun a_held_report_is_handled_by_a_live_handler_the_moment_the_process_listens() {
        val metrics = HeldReports(listOf(metricPayloadOf(mapOf("timeStampBegin" to "x"))))
        snapSyncProcess(ports(metrics = metrics), runtime())
        assertEquals(1, metrics.listens, "listened to exactly once")
        assertTrue(started.value, "and the report found the channel already running")
    }

    @Test
    fun a_crossing_is_sent_once_filed_under_the_build_the_report_describes_and_rides_with_its_reasons() {
        // SNAPSYNC-61: a dev build's foreground kill, delivered a day later by a TestFlight launch.
        val reporter = Recording()
        val process = snapSyncProcess(ports(reporter = reporter), runtime())
        process.processMetrics.deliver(
            metricPayloadOf(
                mapOf(
                    "applicationExitMetrics.foregroundExitData.cumulativeMemoryResourceLimitExitCount" to "1",
                    "appVersion" to "0.4",
                    "metaData.appBuildVersion" to "1",
                ),
            ),
        )
        val context = assertNotNull(reporter.contexts[CrashSections.PROCESS_METRICS])
        assertEquals("foregroundExitData.cumulativeMemoryResourceLimitExitCount", context["crossing.reasons"])
        val leaving = assertNotNull(
            reporter.shaping,
        ).event(reporter.captured.single().copy(release = "0.6", dist = "2369"))
        assertEquals("0.4" to "1", leaving.release to leaving.dist)
        assertEquals(
            listOf("process metric crossed: foregroundExitData.cumulativeMemoryResourceLimitExitCount"),
            leaving.fingerprint,
        )
        assertTrue(leaving.tags.keys.none { it.startsWith("described.") }, "no naming tag leaves: ${leaving.tags}")
    }

    @Test
    fun a_crossing_on_a_build_that_reports_nowhere_sends_nothing() {
        val reporter = Recording()
        snapSyncProcess(ports(reporter = reporter, dsn = null), runtime()).processMetrics.deliver(
            metricPayloadOf(
                mapOf("applicationExitMetrics.foregroundExitData.cumulativeMemoryResourceLimitExitCount" to "1"),
            ),
        )
        assertNull(reporter.shaping, "never started")
    }

    @Test
    fun a_report_carries_the_footprints_the_app_recorded_before_it_was_last_suspended() {
        val reporter = Recording()
        snapSyncProcess(ports(reporter = reporter), runtime()).footprints
            .record("entering background", MemoryFootprint(83_574_000))
        // The report arrives in a LATER process, which reads what the earlier one kept.
        snapSyncProcess(ports(reporter = reporter), runtime()).processMetrics.deliver(
            metricPayloadOf(
                mapOf("applicationExitMetrics.backgroundExitData.cumulativeMemoryPressureExitCount" to "1"),
            ),
        )
        val context = assertNotNull(reporter.contexts[CrashSections.PROCESS_METRICS])
        val newest = assertNotNull(context["ownFootprint.0"], "the reading rides: $context")
        assertTrue("entering background: footprint 83574 kB" in newest, newest)
        assertEquals("1", context["applicationExitMetrics.backgroundExitData.cumulativeMemoryPressureExitCount"])
    }

    @Test
    fun a_report_with_nothing_recorded_carries_only_itself() {
        val reporter = Recording()
        snapSyncProcess(ports(reporter = reporter), runtime()).processMetrics
            .deliver(metricPayloadOf(mapOf("timeStampBegin" to "x")))
        assertEquals(mapOf("timeStampBegin" to "x"), reporter.contexts[CrashSections.PROCESS_METRICS])
        assertEquals(emptyList(), reporter.captured, "a quiet report reaches no one")
    }

    private companion object {
        const val DSN = "https://key@ingest/1"

        /** The upload extension's bundle id: its process tag. */
        const val EXTENSION = "app.snapsync.BackgroundUpload"
    }
}
