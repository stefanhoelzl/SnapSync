package app.snapsync.domain.report

import app.snapsync.domain.crash.ReportedIds
import app.snapsync.domain.event.EventId
import kotlin.test.Test
import kotlin.test.assertEquals

/** A report as one event: titled by what the user wrote, every section verbatim, ids and all. */
class DiagnosticDumpTest {

    private val id = "550e8400-e29b-41d4-a716-446655440000"
    private val dump = DiagnosticDump(
        note = "stuck on $id — see https://snapsync.stho.net/join/$id#k=abc",
        ids = ReportedIds(EventId.parse(id), Fact.Failed("no device id stored yet"), "app.snapsync"),
        state = mapOf("screen" to "Joined"),
        ledger = mapOf("photos_pending" to "1"),
        appLog = "app $id\n",
        extensionLog = "ext\n",
    )

    @Test
    fun `the report is one event titled by the description, verbatim`() {
        val event = dump.event()
        assertEquals("Bug Report: ${dump.note}", event.message, "the user's own text, as they wrote it")
        assertEquals(
            mapOf(
                "note" to mapOf("text" to dump.note),
                "snapsync" to mapOf("event" to id, "device" to "failed (no device id stored yet)", "process" to "app.snapsync"),
                "state" to dump.state,
                "ledger" to dump.ledger,
                "app_log" to mapOf("text" to dump.appLog),
                "ext_log" to mapOf("text" to dump.extensionLog),
            ),
            event.contexts,
        )
        assertEquals(emptyMap(), event.tags, "nothing claims an exemption: nothing is scrubbed")
    }

    @Test
    fun `the log bytes count both tails and not the note`() {
        assertEquals(dump.appLog.encodeToByteArray().size + 4, dump.logBytes)
    }
}

/** Bytes of log [this] carries — what `LOG_BUDGET_BYTES` bounds; the note is not log. */
private val DiagnosticDump.logBytes: Int
    get() = appLog.encodeToByteArray().size + extensionLog.encodeToByteArray().size
