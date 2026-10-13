package app.snapsync.services.logs

import app.snapsync.inbound.LogLine
import app.snapsync.inbound.of
import app.snapsync.ports.LogSink
import app.snapsync.ports.ThreadLabel
import co.touchlab.kermit.Severity
import kotlin.test.Test
import kotlin.test.assertEquals

/** The process's log writers (capability `privacy-security`): one line, formatted once, to every sink. */
class SinkLogWriterTest {

    private class Recording : LogSink {
        val lines = mutableListOf<Triple<Severity, String, String>>()
        override fun write(severity: Severity, tag: String, line: String) {
            lines += Triple(severity, tag, line)
        }
    }

    /** The calling thread, labelled [label]. */
    private class Labelled(private val label: String?) : ThreadLabel {
        override fun swap(label: String?): String? = this.label
        override fun current(): String? = label
    }

    @Test
    fun every_sink_gets_the_same_line_prefixed_with_the_trigger_its_thread_is_labelled_with() {
        val file = Recording()
        val unified = Recording()
        SinkLogWriter(listOf(file, unified), Labelled("OnSilentPush"))
            .log(Severity.Warn, "reconcile failed", "download", null)
        val expected = Triple(Severity.Warn, "download", "[OnSilentPush] [Warn/download] reconcile failed")
        assertEquals(listOf(expected), file.lines)
        assertEquals(listOf(expected), unified.lines)
    }

    @Test
    fun a_line_on_an_unlabelled_thread_carries_no_prefix() {
        val sink = Recording()
        SinkLogWriter(listOf(sink), Labelled(null)).log(Severity.Info, "enumerated 3", "gallery", null)
        assertEquals("[Info/gallery] enumerated 3", sink.lines.single().third)
    }

    @Test
    fun a_structured_line_carries_its_own_trigger_and_its_fields_after_the_event() {
        val sink = Recording()
        SinkLines(listOf(sink), Labelled("OnForeground")).write(
            LogLine(Severity.Info, "download", "OnSilentPush", "reconciled", listOf("planned" of 2), null),
        )
        assertEquals("[OnSilentPush] [Info/download] reconciled planned=2", sink.lines.single().third)
    }

    @Test
    fun a_structured_line_written_without_a_trigger_takes_its_thread_s() {
        val sink = Recording()
        SinkLines(listOf(sink), Labelled("OnForeground")).write(
            LogLine(Severity.Info, "download", null, "reconciled", emptyList(), null),
        )
        assertEquals("[OnForeground] [Info/download] reconciled", sink.lines.single().third)
    }
}
