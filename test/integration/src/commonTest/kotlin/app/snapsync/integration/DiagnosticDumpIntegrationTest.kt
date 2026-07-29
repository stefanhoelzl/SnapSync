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
        send()

        val dump = w.diagnosticsSent.value.single()
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

        assertNotNull(w.core.userCommands.sendDiagnostics).invoke()

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

        assertNotNull(w.core.userCommands.sendDiagnostics).invoke()

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
        send()
        send()

        assertEquals(2, w.diagnosticsSent.value.size, "no rate limit: each confirmed gesture sends one")
    }
}
