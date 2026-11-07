@file:OptIn(EntryPointCall::class, CompositionOnly::class)

package app.snapsync.application.process

import app.snapsync.application.EntryPointCall
import app.snapsync.data.crash.CrashChannel
import app.snapsync.data.crash.RecordingReporter
import app.snapsync.data.process.FootprintTrail
import app.snapsync.data.process.MetricPayloadReading
import app.snapsync.domain.crash.CrashSections
import app.snapsync.domain.crash.CrashShaping
import app.snapsync.domain.process.MemoryFootprint
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Log
import app.snapsync.inbound.LogLevel
import app.snapsync.inbound.LogLine
import app.snapsync.inbound.LogWriter
import app.snapsync.mock.BuildInfoMock
import app.snapsync.mock.ThreadLabelMock
import app.snapsync.mock.fixedClock
import app.snapsync.mock.inMemoryFiles
import app.snapsync.mock.metricPayloadOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** A delivered report: its standing account first, its line, then one event per reason — each logged before it is sent. */
class AccountForProcessTest {

    private val reporter = RecordingReporter()
    private val files = inMemoryFiles(private = mutableMapOf())
    private val footprints = FootprintTrail(files, fixedClock(Instant.fromEpochSeconds(0)))
    private val channel =
        CrashChannel(
            reporter,
            BuildInfoMock(dsn = "https://key@ingest/1").port(),
            ThreadLabelMock(),
            CrashShaping.STANDARD,
        )
    private val lines = mutableListOf<LogLine>()

    init {
        Log.install(
            listOf(
                object : LogWriter {
                    override fun write(line: LogLine) {
                        lines += line
                    }
                },
            ),
        )
    }

    @AfterTest
    fun uninstall() = Log.install(emptyList())

    private fun account(fields: Map<String, String>) = AccountForProcess(
        footprints,
        channel,
    )(MetricPayloadReading().read(metricPayloadOf(fields)))

    @Test
    fun `a quiet report is its line alone, every field in it, and reaches no event`() {
        account(mapOf("timeStampBegin" to "2026-09-11 00:00:00", "timeStampEnd" to "2026-09-12 00:00:00", "a" to "1"))
        val line = lines.single()
        assertEquals(LogLevel.INFO, line.level)
        assertEquals("process metrics", line.event)
        assertEquals("2026-09-11 00:00:00 .. 2026-09-12 00:00:00", line.fields.single { it.name == "period" }.value)
        assertTrue("\"a\":\"1\"" in line.fields.single { it.name == "report" }.value.toString())
        assertEquals(emptyList(), reporter.captured)
        assertEquals(
            setOf("timeStampBegin", "timeStampEnd", "a"),
            reporter.contexts.getValue(CrashSections.PROCESS_METRICS).keys,
        )
    }

    @Test
    fun `a crossing report stands with its reasons and footprints, says it arrived later, and sends each crossing after its line`() {
        footprints.record("entering background", MemoryFootprint(83_574_000))
        account(mapOf("applicationExitMetrics.backgroundExitData.cumulativeAppWatchdogExitCount" to "2"))
        val context = reporter.contexts.getValue(CrashSections.PROCESS_METRICS)
        assertEquals("backgroundExitData.cumulativeAppWatchdogExitCount", context["crossing.reasons"])
        assertTrue(context.containsKey("ownFootprint.0"), "the ended process's own last reading rides: $context")
        val reported = lines.filter { it.tag == "processMetrics" }
        assertEquals(
            listOf(
                "process metrics",
                "process metrics delivered later",
                "process metric crossed",
            ),
            reported.map {
                it.event
            },
        )
        assertEquals(
            LogLevel.WARN,
            reported.last().level,
            "never an Error: the event is the next line's, filed under its build",
        )
        assertEquals(
            "process metric crossed: backgroundExitData.cumulativeAppWatchdogExitCount",
            reporter.captured.single().message,
        )
    }
}
