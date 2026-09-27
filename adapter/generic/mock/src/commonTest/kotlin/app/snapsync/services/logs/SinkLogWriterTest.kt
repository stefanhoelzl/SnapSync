package app.snapsync.services.logs

import app.snapsync.ports.EntryContext
import app.snapsync.ports.LogSink
import co.touchlab.kermit.Severity
import kotlin.test.Test
import kotlin.test.assertEquals

/** The process's log writer (capability `privacy-security`): one line, formatted once, to every sink. */
class SinkLogWriterTest {

    private class Recording : LogSink {
        val lines = mutableListOf<Triple<Severity, String, String>>()
        override fun write(severity: Severity, tag: String, line: String) {
            lines += Triple(severity, tag, line)
        }
    }

    private class Entry(private val name: String?) : EntryContext {
        override fun enter(name: String) = false
        override fun exit(owned: Boolean) = Unit
        override fun current(): String? = name
    }

    @Test
    fun every_sink_gets_the_same_line_prefixed_with_the_entry_point_that_triggered_it() {
        val file = Recording()
        val unified = Recording()
        SinkLogWriter(listOf(file, unified), Entry("onSilentPush")).log(Severity.Warn, "reconcile failed", "download", null)
        val expected = Triple(Severity.Warn, "download", "[onSilentPush] [Warn/download] reconcile failed")
        assertEquals(listOf(expected), file.lines)
        assertEquals(listOf(expected), unified.lines)
    }

    @Test
    fun a_line_with_no_entry_point_claimed_carries_no_prefix() {
        val sink = Recording()
        SinkLogWriter(listOf(sink), Entry(null)).log(Severity.Info, "enumerated 3", "gallery", null)
        assertEquals("[Info/gallery] enumerated 3", sink.lines.single().third)
    }
}
