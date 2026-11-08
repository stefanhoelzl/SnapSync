package app.snapsync.data.logs

import app.snapsync.inbound.LogLevel
import app.snapsync.inbound.LogLine
import app.snapsync.inbound.of
import app.snapsync.mock.ThreadLabelMock
import app.snapsync.ports.LogSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every line reaches every sink once, in the device log's text, labelled by its trigger or the thread's. */
class SinkLinesTest {

    private class Recorded : LogSink {
        val written = mutableListOf<Triple<LogLevel, String, String>>()

        override fun write(level: LogLevel, tag: String, line: String) {
            written += Triple(level, tag, line)
        }
    }

    private val file = Recorded()
    private val unified = Recorded()
    private val label = ThreadLabelMock().apply { swap("OnForeground") }

    private fun line(level: LogLevel, trigger: String?, cause: Throwable? = null) =
        LogLine(level, "download", trigger, "reconciled", listOf("planned" of 2), cause)

    @Test
    fun `a line is rendered once and handed to every sink, its own trigger first`() {
        SinkLines(listOf(file, unified), label).write(line(LogLevel.INFO, "OnSilentPush"))

        val expected = Triple(LogLevel.INFO, "download", "[OnSilentPush] [Info/download] reconciled planned=2")
        assertEquals(listOf(expected), file.written)
        assertEquals(listOf(expected), unified.written)
    }

    @Test
    fun `a line with no trigger of its own carries the thread's`() {
        SinkLines(listOf(file), label).write(line(LogLevel.WARN, trigger = null))
        assertEquals("[OnForeground] [Warn/download] reconciled planned=2", file.written.single().third)
    }

    @Test
    fun `a cause follows the line`() {
        SinkLines(listOf(file), ThreadLabelMock()).write(line(LogLevel.ERROR, null, IllegalStateException("boom")))
        val written = file.written.single().third
        assertTrue(
            written.startsWith("[Error/download] reconciled planned=2 | java.lang.IllegalStateException: boom"),
            written,
        )
    }

    @Test
    fun `every level is written as the device log has always written it`() {
        LogLevel.entries.forEach { SinkLines(listOf(file), ThreadLabelMock()).write(line(it, null)) }
        assertEquals(
            listOf("Debug", "Info", "Warn", "Error"),
            file.written.map { it.third.substringAfter('[').substringBefore('/') },
        )
    }
}
