package app.snapsync.format

import app.snapsync.domain.process.ProcessMetricReport
import app.snapsync.domain.report.DiagnosticDump
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a report is written as where it is kept as text. */
class ReportTextTest {

    private fun parsed(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `a kept bug report holds the five sections, identifiers intact`() {
        val id = "550e8400-e29b-41d4-a716-446655440000"
        val dump = DiagnosticDump("stuck on $id", mapOf("event_id" to id), mapOf("photos_pending" to "1"), "a\n", "e\n")
        val saved = parsed(ReportText.saved(dump))
        assertEquals(setOf("note", "state", "ledger", "app_log", "ext_log"), saved.keys)
        assertEquals("stuck on $id", ((saved["note"] as JsonObject)["text"] as JsonPrimitive).content)
        assertEquals(id, ((saved["state"] as JsonObject)["event_id"] as JsonPrimitive).content)
    }

    @Test
    fun `a metric report's line carries every field, sorted, so a quiet report is recoverable from it`() {
        val text = ReportText.metrics(ProcessMetricReport(mapOf("zulu" to "1", "alpha" to "2", "mike" to "3")))
        assertTrue(
            text.indexOf("\"alpha\"") < text.indexOf("\"mike\"") && text.indexOf("\"mike\"") < text.indexOf("\"zulu\""),
        )
    }

    @Test
    fun `the fields round-trip even when values contain spaces`() {
        // The measured failure: a space-joined line could not be split back, because platform values carry spaces.
        val fields = mapOf(
            "memoryMetrics.peakMemoryUsage" to "118417 kB",
            "timeStampBegin" to "2026-09-13 00:00:00",
            "quoted" to "a \"b\" c",
        )
        val parsed = parsed(
            ReportText.metrics(ProcessMetricReport(fields)),
        ).mapValues { (it.value as JsonPrimitive).content }
        assertEquals(fields, parsed)
    }
}
