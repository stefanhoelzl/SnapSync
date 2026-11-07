@file:OptIn(CompositionOnly::class)

package app.snapsync.inbound

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A Kermit line joins the structured log's writers, and only a process that supplies sinks takes the globals over. */
class KermitBridgeTest {
    private val lines = mutableListOf<LogLine>()

    private val capturing = object : LogWriter {
        override fun write(line: LogLine) {
            lines += line
        }
    }

    @AfterTest
    fun uninstall() {
        Log.install(emptyList())
        Logger.setLogWriters(platformLogWriter())
    }

    @Test
    fun `a kermit line reaches the structured log's writers at its own level, its text the event`() {
        Log.install(listOf(capturing))
        val failure = IllegalStateException("boom")
        KermitBridge().log(Severity.Warn, "download 7 finished late", "Download", failure)

        val line = lines.single()
        assertEquals(LogLevel.WARN, line.level)
        assertEquals("Download", line.tag)
        assertEquals("download 7 finished late", line.event)
        assertEquals(emptyList(), line.fields)
        assertSame(failure, line.cause)
        assertEquals(null, line.trigger, "a writer reads the thread's label instead")
    }

    @Test
    fun `every kermit severity has its level`() {
        Log.install(listOf(capturing))
        Severity.entries.forEach { KermitBridge().log(it, "x", "t", null) }
        assertEquals(LogLevel.entries, lines.map { it.level })
    }

    @Test
    fun `a process that supplies sinks owns the globals - its writers and the bridge`() {
        ProcessLogging(listOf(capturing), sinks = 2).install()
        Logger.withTag("legacy").i { "from kermit" }

        assertEquals(listOf("from kermit"), lines.map { it.event }, "the bridge is Kermit's writer, onto ours")
        assertTrue(Logger.config.logWriterList.single() is KermitBridge)
    }

    @Test
    fun `a process that supplies no sinks leaves both globals alone`() {
        val before = Logger.config.logWriterList
        ProcessLogging(listOf(capturing), sinks = 0).install()
        Log("t").unlabelled.i("unseen")

        assertEquals(emptyList(), lines, "our writers were not installed")
        assertEquals(before, Logger.config.logWriterList, "nor was Kermit's list touched")
    }
}
