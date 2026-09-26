package app.snapsync.services.crash

import app.snapsync.compose.ProcessPorts
import app.snapsync.compose.snapSyncProcess
import app.snapsync.fake.inMemoryCrashReporter
import app.snapsync.fake.inMemoryFiles
import app.snapsync.model.CrashEvent
import app.snapsync.model.DiagnosticDump
import app.snapsync.model.DumpResult
import app.snapsync.model.NON_REDACTED_TAG
import app.snapsync.model.ProcessMetricReport
import app.snapsync.ports.LogScope
import app.snapsync.ports.MetricHandlers
import app.snapsync.ports.ProcessMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What `snapSyncProcess` sets up (capability `privacy-security`), over the honest in-memory reporter: the channel
 * started as the process's first act where the build reports, and nothing at all where it does not; the process
 * metrics listened to only with a live handler; the dump sent verbatim.
 */
class ProcessCompositionTest {

    private val id = "550e8400-e29b-41d4-a716-446655440000"
    private val started = MutableStateFlow(false)
    private val dumps = MutableStateFlow<List<CrashEvent>>(emptyList())

    /** A provider that hands over whatever it holds the moment something listens — MetricKit's one-shot shape. */
    private class HeldReports(private val held: List<ProcessMetricReport>) : ProcessMetrics {
        var listens = 0
        override fun listen(handlers: MetricHandlers) {
            listens++
            held.forEach(handlers.onReport)
        }
    }

    private fun process(dsn: String?, metrics: ProcessMetrics = ProcessMetrics.None) = snapSyncProcess(
        ProcessPorts(inMemoryCrashReporter(started, dumps), metrics, inMemoryFiles(), LogScope.NoOp, dsn),
    )

    private fun dump() = DiagnosticDump("stuck on $id", mapOf("screen" to "Joined"), emptyMap(), "app\n", "ext\n")

    @Test
    fun a_build_that_reports_starts_the_channel_first_and_routes_the_log_into_it() {
        val process = process(dsn = "https://key@ingest/1")
        assertTrue(started.value, "reporting starts as the process's first act")
        assertTrue(process.crash.isConfigured)
        assertEquals(1, process.logWriters.size, "the crash channel's log writer, for the root to install")
    }

    @Test
    fun a_build_that_reports_nowhere_starts_nothing_and_installs_nothing() = runTest {
        val process = process(dsn = null)
        assertFalse(started.value, "no destination, no channel — and no connection ever opened")
        assertFalse(process.crash.isConfigured)
        assertTrue(process.logWriters.isEmpty(), "a build that reports nowhere never constructs the writer")
        assertIs<DumpResult.NotSent>(process.crash.sendDump(dump()))
        assertTrue(dumps.value.isEmpty())
    }

    @Test
    fun the_dump_leaves_verbatim_through_the_registered_handlers() = runTest {
        val process = process(dsn = "https://key@ingest/1")
        assertEquals(DumpResult.Queued, process.crash.sendDump(dump()))
        val sent = dumps.value.single()
        assertEquals("1", sent.tags[NON_REDACTED_TAG])
        assertTrue(id in sent.message.orEmpty(), "the operator's id survived the production handler: ${sent.message}")
    }

    @Test
    fun a_held_report_is_handled_by_a_live_handler_the_moment_the_process_listens() {
        val metrics = HeldReports(listOf(ProcessMetricReport(mapOf("timeStampBegin" to "x"))))
        process(dsn = "https://key@ingest/1", metrics = metrics)
        assertEquals(1, metrics.listens, "listened to exactly once")
        assertTrue(started.value, "and the report found the channel already running")
    }

    /** A reporter that records what reaches it — to read what the log writer and the handlers hand the port. */
    private class Recording : app.snapsync.ports.CrashReporter {
        val crumbs = mutableListOf<app.snapsync.model.Crumb>()
        val events = mutableListOf<CrashEvent>()
        val contexts = mutableMapOf<String, Map<String, String>>()
        var handlers: app.snapsync.ports.CrashHandlers? = null
        override fun listen(handlers: app.snapsync.ports.CrashHandlers) {
            this.handlers = handlers
        }
        override fun start(options: app.snapsync.model.CrashOptions) = Unit
        override fun capture(event: CrashEvent) {
            events += event
        }
        override fun breadcrumb(crumb: app.snapsync.model.Crumb) {
            crumbs += crumb
        }
        override fun setContext(name: String, fields: Map<String, String>) {
            contexts[name] = fields
        }
        override suspend fun sendDump(dump: CrashEvent): DumpResult = DumpResult.Queued
    }

    private class Entry(private val name: String?) : LogScope {
        override fun enter(name: String) = false
        override fun exit(owned: Boolean) = Unit
        override fun current(): String? = name
    }

    @Test
    fun the_log_writer_hands_every_line_to_the_channel_and_an_error_as_an_event_tagged_with_its_entry_point() {
        val reporter = Recording()
        val writer = assertNotNull(CrashReporting(reporter, "https://key@ingest/1", Entry("process")).logWriter)
        writer.log(co.touchlab.kermit.Severity.Info, "enumerated 3", "gallery", null)
        writer.log(co.touchlab.kermit.Severity.Error, "reconcile($id) failed", "engine", null)
        assertEquals(listOf("[process] enumerated 3", "[process] reconcile(‹uuid›) failed"), reporter.crumbs.map { it.message })
        val event = reporter.events.single()
        assertEquals("reconcile(‹uuid›) failed", event.message)
        assertEquals("process", event.tags["entry_point"])
    }

    @Test
    fun the_registered_handlers_are_the_production_scrub() {
        val reporter = Recording()
        snapSyncProcess(ProcessPorts(reporter, ProcessMetrics.None, inMemoryFiles(), LogScope.NoOp, "https://key@ingest/1"))
        val handlers = assertNotNull(reporter.handlers, "the process listened to its reporter")
        assertEquals("id ‹uuid›", handlers.onEvent(CrashEvent(message = "id $id"))?.message)
        assertEquals(
            "id ‹uuid›",
            handlers.onBreadcrumb(app.snapsync.model.Crumb(app.snapsync.model.CrashLevel.INFO, "id $id"))?.message,
        )
    }

    @Test
    fun a_crossing_report_rides_as_the_standing_context_with_its_reasons() {
        val reporter = Recording()
        val process = snapSyncProcess(
            ProcessPorts(reporter, ProcessMetrics.None, inMemoryFiles(), LogScope.NoOp, "https://key@ingest/1"),
        )
        val crossing = ProcessMetricReport(
            mapOf("applicationExitMetrics.backgroundExitData.cumulativeAppWatchdogExitCount" to "3"),
        )
        process.processAccount.handle(crossing)
        val context = assertNotNull(reporter.contexts[app.snapsync.model.PROCESS_METRIC_CONTEXT])
        assertEquals("3", context["applicationExitMetrics.backgroundExitData.cumulativeAppWatchdogExitCount"])
        assertTrue(context.containsKey("crossing.reasons"), "why it crossed rides with it: $context")
    }

    @Test
    fun describing_the_process_starts_a_reporting_channel_itself() {
        val crash = CrashReporting(inMemoryCrashReporter(started, dumps), "https://key@ingest/1", LogScope.NoOp)
        assertFalse(started.value)
        crash.describeProcess(ProcessMetricReport(emptyMap()))
        assertTrue(started.value, "an account that outran the composition must not reach an unstarted channel")
        assertNotNull(crash.logWriter)
        assertNull(CrashReporting(inMemoryCrashReporter(), null, LogScope.NoOp).logWriter)
    }
}
