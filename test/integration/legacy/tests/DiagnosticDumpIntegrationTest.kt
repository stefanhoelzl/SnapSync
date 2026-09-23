package app.snapsync.integration

import app.snapsync.model.DIAGNOSTIC_LOG_BUDGET_BYTES
import app.snapsync.ports.DeviceLogSource
import app.snapsync.world.World
import app.snapsync.world.worldTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The operator-initiated diagnostic dump over the **real** stack (capability `diagnostic-logging`):
 * the same `snapSyncApp` core the device shells call, fired through the same `UserCommands` bundle
 * presentation fires, landing in the world's reporter.
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

    @Test
    fun a_confirmed_dump_carries_both_logs_and_the_live_counts() = worldTest {
        val w = World(this)
        w.provision("11111111-2222-4333-8444-555555555555")
        w.addOwnAsset("CAM")
        // The operator plays the OS: one cycle enqueues, the platform completes, the NEXT cycle
        // records COMPLETED — so the dump reads the counts a real device would show mid-flow.
        w.runUploadCycle()
        w.platform.completeJob("CAM-primary.jpg")
        w.runUploadCycle()
        w.deviceLogs.value = mapOf(
            DeviceLogSource.Process.APP to "app: cycle finished\n",
            DeviceLogSource.Process.EXTENSION to "ext: enumeration 1 seen\n",
        )

        val send = assertNotNull(
            w.core.userCommands.sendDiagnostics,
            "the world composes a configured reporter, so the command must exist",
        )
        send(NOTE, SCREEN)

        val dump = w.diagnosticsSent.value.single()
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
    fun the_dump_travels_verbatim_with_its_identifiers_intact() = worldTest {
        // The deliberate carve-out (capability `crash-reporting`): a dump is confirmed by the operator
        // and worthless without the ids — a log where every id reads alike cannot answer WHICH event.
        // Automatic events stay redacted; only this path is exempt.
        val eventId = "11111111-2222-4333-8444-555555555555"
        val w = World(this)
        w.provision(eventId)
        w.deviceLogs.value = mapOf(
            DeviceLogSource.Process.APP to "app: reconcile(eventId=$eventId) ok\n",
        )

        assertNotNull(w.core.userCommands.sendDiagnostics).invoke(NOTE, SCREEN)

        val dump = w.diagnosticsSent.value.single()
        assertTrue(
            eventId in dump.appLog,
            "the event id was redacted out of the dump's log — the exemption is undone: ${dump.appLog}",
        )
        assertEquals(eventId, dump.state["event_id"])
    }

    @Test
    fun a_huge_pair_of_logs_still_fits_the_budget() = worldTest {
        val w = World(this)
        w.provision("11111111-2222-4333-8444-555555555555")
        w.deviceLogs.value = mapOf(
            DeviceLogSource.Process.APP to logLine("app", 30_000),
            DeviceLogSource.Process.EXTENSION to logLine("ext", 30_000),
        )

        assertNotNull(w.core.userCommands.sendDiagnostics).invoke(NOTE, SCREEN)

        val dump = w.diagnosticsSent.value.single()
        assertTrue(
            dump.logBytes <= DIAGNOSTIC_LOG_BUDGET_BYTES,
            "carried ${dump.logBytes} bytes: over the budget the reporting server rejects the event " +
                "and the SDK swallows the error, so the dump arrives nowhere and says nothing",
        )
    }

    @Test
    fun each_confirmation_sends_exactly_one_dump() = worldTest {
        val w = World(this)
        w.provision("11111111-2222-4333-8444-555555555555")

        val send = assertNotNull(w.core.userCommands.sendDiagnostics)
        send("first report", SCREEN)
        send("second report", SCREEN)

        assertEquals(2, w.diagnosticsSent.value.size, "no rate limit: each confirmed gesture sends one")
        assertEquals(
            listOf("first report", "second report"),
            w.diagnosticsSent.value.map { it.note },
            "each report carries its own account — they are distinct issues, not one repeated",
        )
    }

    @Test
    fun a_described_identifier_reaches_the_reporter_unredacted() = worldTest {
        // The description rides in the event MESSAGE, which is the one field the scrub reaches. An
        // operator quoting the event id they are stuck on is exactly the case worth protecting, and
        // losing it would be invisible: the report still sends, and only a later reader finds `‹uuid›`.
        val eventId = "11111111-2222-4333-8444-555555555555"
        val w = World(this)
        w.provision(eventId)

        assertNotNull(w.core.userCommands.sendDiagnostics)
            .invoke("stuck on $eventId since Tuesday", SCREEN)

        assertEquals("stuck on $eventId since Tuesday", w.diagnosticsSent.value.single().note)
    }
}
