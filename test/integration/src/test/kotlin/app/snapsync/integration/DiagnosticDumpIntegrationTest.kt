package app.snapsync.integration

import app.snapsync.model.DIAGNOSTIC_LOG_BUDGET_BYTES
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The operator-initiated diagnostic dump over the **real** stack (capability `diagnostic-logging`):
 * the same `snapSyncApp` core the device shells call, fired through `/user/sendDiagnostics` — the same
 * `UserCommands` bundle presentation fires — landing in the host's reporter (`diagnostics/sent`).
 *
 * What this proves that the unit tests cannot: the command is *wired* — from the bundle, through the
 * composed assembly, to the port — and that a dump crossing the real graph carries the real counts
 * and travels **verbatim**. The last part is the one that would fail silently in production: a scrub
 * applied on this path empties the dump with no error anywhere.
 */
class DiagnosticDumpIntegrationTest {

    /** What the operator wrote — the sheet trimmed and bounded it before the command ever fired. */
    private val NOTE = "photos stopped arriving after I rejoined"

    /** The surface it was written from, as the screen supplies it. */
    private val SCREEN = "Joined"

    private fun logLine(prefix: String, n: Int) =
        (1..n).joinToString("") { "$prefix line $it ..............................................\n" }

    private suspend fun Rig.appendLog(process: String, text: String) {
        device("logs/append", "process" to process, body = text)
    }

    private suspend fun Rig.sendDiagnostics(note: String, screen: String = SCREEN) {
        user("sendDiagnostics", "note" to note, "screen" to screen)
    }

    /** Every dump the reporter received, in order. */
    private suspend fun Rig.dumpsSent(): List<Dump> =
        deviceJson("diagnostics/sent").getValue("dumps").jsonArray.map { Dump(it.jsonObject) }

    /**
     * The dumps, once [count] have reached the reporter. The command is accepted when the tap fires; the dump is
     * assembled and sent after it, so a read straight after the command can precede it.
     */
    private suspend fun Rig.awaitDumps(count: Int): List<Dump> =
        eventually<List<Dump>>(read = { dumpsSent() }) { it.size >= count }

    private class Dump(json: JsonObject) {
        val note = json.getValue("note").jsonPrimitive.content
        val state = json.getValue("state").jsonObject.mapValues { it.value.jsonPrimitive.content }
        val ledger = json.getValue("ledger").jsonObject.mapValues { it.value.jsonPrimitive.content }
        val appLog = json.getValue("appLog").jsonPrimitive.content
        val extensionLog = json.getValue("extensionLog").jsonPrimitive.content
        val logBytes = json.getValue("logBytes").jsonPrimitive.int
    }

    @Test
    fun a_confirmed_dump_carries_both_logs_and_the_live_counts() = rigTest {
        createAndJoin()
        addPhoto("CAM")
        // The operator plays the OS: one cycle enqueues, the platform completes, the NEXT cycle
        // records COMPLETED — so the dump reads the counts a real device would show mid-flow.
        cycle()
        completeJobs(primaryKey("CAM"))
        cycle()
        appendLog("app", "app: cycle finished\n")
        appendLog("extension", "ext: enumeration 1 seen\n")

        sendDiagnostics(NOTE)

        val dump = awaitDumps(1).single()
        // The one section a log tail can never supply: what the operator was doing.
        assertEquals(NOTE, dump.note)
        assertEquals(SCREEN, dump.state["screen"])
        assertEquals("app: cycle finished\n", dump.appLog)
        assertEquals("ext: enumeration 1 seen\n", dump.extensionLog)
        // The five counts, read live off the same stores the cycle just wrote.
        assertEquals("1", dump.ledger["photos_completed"])
        assertEquals("0", dump.ledger["photos_pending"])
        assertEquals("true", dump.state["joined"])
    }

    @Test
    fun the_dump_travels_verbatim_with_its_identifiers_intact() = rigTest {
        // The deliberate carve-out (capability `crash-reporting`): a dump is confirmed by the operator
        // and worthless without the ids — a log where every id reads alike cannot answer WHICH event.
        // Automatic events stay redacted; only this path is exempt.
        val eventId = createAndJoin()
        appendLog("app", "app: reconcile(eventId=$eventId) ok\n")

        sendDiagnostics(NOTE)

        val dump = awaitDumps(1).single()
        assertTrue(
            eventId in dump.appLog,
            "the event id was redacted out of the dump's log — the exemption is undone: ${dump.appLog}",
        )
        assertEquals(eventId, dump.state["event_id"])
    }

    @Test
    fun a_huge_pair_of_logs_still_fits_the_budget() = rigTest {
        createAndJoin()
        appendLog("app", logLine("app", 30_000))
        appendLog("extension", logLine("ext", 30_000))

        sendDiagnostics(NOTE)

        val dump = awaitDumps(1).single()
        assertTrue(
            dump.logBytes <= DIAGNOSTIC_LOG_BUDGET_BYTES,
            "carried ${dump.logBytes} bytes: over the budget the reporting server rejects the event " +
                "and the SDK swallows the error, so the dump arrives nowhere and says nothing",
        )
    }

    @Test
    fun each_confirmation_sends_exactly_one_dump() = rigTest {
        createAndJoin()

        sendDiagnostics("first report")
        sendDiagnostics("second report")

        val dumps = awaitDumps(2)
        assertEquals(2, dumps.size, "no rate limit: each confirmed gesture sends one")
        assertEquals(
            listOf("first report", "second report"),
            dumps.map { it.note },
            "each report carries its own account — they are distinct issues, not one repeated",
        )
    }

    @Test
    fun a_described_identifier_reaches_the_reporter_unredacted() = rigTest {
        // The description rides in the event MESSAGE, which is the one field the scrub reaches. An
        // operator quoting the event id they are stuck on is exactly the case worth protecting, and
        // losing it would be invisible: the report still sends, and only a later reader finds `‹uuid›`.
        val eventId = createAndJoin()

        sendDiagnostics("stuck on $eventId since Tuesday")

        assertEquals("stuck on $eventId since Tuesday", awaitDumps(1).single().note)
    }
}
