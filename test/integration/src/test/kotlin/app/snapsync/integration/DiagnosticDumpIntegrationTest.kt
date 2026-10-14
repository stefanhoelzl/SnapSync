package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.NetworkState
import app.snapsync.levers.ReceivedDump
import app.snapsync.levers.WorldEvent
import app.snapsync.model.DIAGNOSTIC_LOG_BUDGET_BYTES
import app.snapsync.model.DiagnosticKeys
import app.snapsync.ui.state.BuildLine
import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.ReportNotice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The operator-initiated diagnostic dump over the **real** stack:
 * the same `snapSyncApp` core the device shells call, fired through `/user/sendDiagnostics` — the same
 * `UserCommands` bundle presentation fires — landing in the host's reporter (the `crash-reporter` service, and the
 * world's `DumpReceived`).
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

    private suspend fun Acts.appendLog(process: String, text: String) {
        device("logs/append", "process" to process, body = text)
    }

    private suspend fun Rig.sendDiagnostics(note: String, screen: String = SCREEN) {
        user("sendDiagnostics", "note" to note, "screen" to screen)
    }

    private suspend fun Acts.sendDiagnostics(note: String, screen: String = SCREEN) {
        user("sendDiagnostics", "note" to note, "screen" to screen)
    }

    /**
     * The dumps, once [count] have reached the reporter. The command is accepted when the tap fires; the dump is
     * assembled and sent after it, so a read straight after the command can precede it.
     */
    private suspend fun Rig.awaitDumps(count: Int): List<ReceivedDump> =
        eventually<List<ReceivedDump>>(read = { crashReporter.sent() }) { it.size >= count }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
    )
    fun a_confirmed_dump_carries_both_logs_and_the_live_counts() = rigTest {
        extensionUploadsOnly()
        createAndJoin()
        addPhoto("CAM")
        // The operator plays the OS: one cycle enqueues, the platform completes, the NEXT cycle
        // records COMPLETED — so the dump reads the counts a real device would show mid-flow.
        cycle()
        completeJobs(primaryJob("CAM"))
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
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
    )
    fun a_dump_carries_the_device_state_the_operating_system_reports() = behaviour {
        given {
            joinedEvent()
            step("the device's state", act = {
                network.set(NetworkState.RESTRICTED)
                deviceConditions.set(
                    mapOf(
                        "power_saving" to "true",
                        "background_refresh" to "unsupported",
                        "standby_bucket" to "rare",
                        "thermal" to "failed:no thermal service",
                    ),
                )
            }, until = { settled { true } })
        }

        lateinit var received: Seen<WorldEvent.DumpReceived>
        whenever { sendDiagnostics(NOTE) }
        then { received = event<WorldEvent.DumpReceived>() }

        val state = received.value.dump.state
        assertEquals("online_restricted", state[DiagnosticKeys.NETWORK])
        assertEquals("true", state[DiagnosticKeys.POWER_SAVING])
        assertEquals("rare", state[DiagnosticKeys.STANDBY_BUCKET])
        assertEquals("failed (no thermal service)", state[DiagnosticKeys.THERMAL])
        assertFalse(DiagnosticKeys.BACKGROUND_REFRESH in state, "a fact the platform lacks is left out: $state")
        assertTrue(state[DiagnosticKeys.DEVICE_ID].orEmpty().isNotBlank(), "the device id the app resolved: $state")
        assertTrue(DiagnosticKeys.TIME_ZONE in state, "the device's zone: $state")
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
    )
    fun a_dump_carries_the_counts_the_screen_showed() = rigTest {
        extensionUploadsOnly()
        createAndJoin()
        addPhoto("CAM")
        cycle()
        completeJobs(primaryJob("CAM"))
        cycle()
        // Whatever the screen shows at the tap — the report's claim is "what the member saw", not a number. An opening
        // refreshes the counts, so the completed upload is on the screen before it is read, and no later refresh can move
        // it between this read and the tap.
        foreground()
        val counts = awaitState { (it.joined?.counts?.shared as? DirectionCount.Progress)?.done == 1 }.joined!!.counts!!

        sendDiagnostics(NOTE)

        val dump = awaitDumps(1).single()
        assertEquals(counts.shared.shown(), dump.state[DiagnosticKeys.SHOWN_SHARED])
        assertEquals(counts.received.shown(), dump.state[DiagnosticKeys.SHOWN_RECEIVED])
        assertEquals("1", dump.ledger["photos_completed"], "beside the store's own count, which may differ from it")
    }

    private fun DirectionCount.shown(): String = when (this) {
        DirectionCount.Off -> "off"
        is DirectionCount.Progress -> "$done/$total"
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
    )
    fun the_dump_travels_verbatim_with_its_identifiers_intact() = behaviour {
        // The deliberate carve-out: a dump is confirmed by the operator
        // and worthless without the ids — a log where every id reads alike cannot answer WHICH event.
        // Automatic events stay redacted; only this path is exempt.
        lateinit var eventId: String
        given {
            eventId = joinedEvent()
            step(
                "its id in the app's log",
                act = { appendLog("app", "app: reconcile(eventId=$eventId) ok\n") },
                until = {},
            )
        }

        lateinit var received: Seen<WorldEvent.DumpReceived>
        whenever { sendDiagnostics(NOTE) }
        then { received = event<WorldEvent.DumpReceived>() }

        val dump = received.value.dump
        assertTrue(
            eventId in dump.appLog,
            "the event id was redacted out of the dump's log — the exemption is undone: ${dump.appLog}",
        )
        assertEquals(eventId, dump.state["event_id"])
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
        scenario = "The user sends a report",
    )
    fun a_huge_pair_of_logs_still_fits_the_budget() = behaviour {
        given {
            joinedEvent()
            step("both logs huge", act = {
                appendLog("app", logLine("app", 30_000))
                appendLog("extension", logLine("ext", 30_000))
            }, until = {})
        }

        // Over the budget, the reporting server rejects the event and the SDK swallows the error: the dump would arrive
        // nowhere and say nothing.
        whenever { sendDiagnostics(NOTE) }
        then { event<WorldEvent.DumpReceived> { it.dump.logBytes <= DIAGNOSTIC_LOG_BUDGET_BYTES } }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
    )
    fun each_confirmation_sends_exactly_one_dump() = behaviour {
        given { joinedEvent() }

        // No rate limit: each confirmed gesture sends one, and each report carries its own account — they are distinct
        // issues, not one repeated.
        whenever {
            sendDiagnostics("first report")
            sendDiagnostics("second report")
        }
        then {
            times(2) { event<WorldEvent.DumpReceived>() }
            inOrder {
                event<WorldEvent.DumpReceived> { it.dump.note == "first report" }
                event<WorldEvent.DumpReceived> { it.dump.note == "second report" }
            }
        }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
    )
    fun a_described_identifier_reaches_the_reporter_unredacted() = behaviour {
        // The description rides in the event MESSAGE, which is the one field the scrub reaches. An
        // operator quoting the event id they are stuck on is exactly the case worth protecting, and
        // losing it would be invisible: the report still sends, and only a later reader finds `‹uuid›`.
        lateinit var eventId: String
        given { eventId = joinedEvent() }

        whenever { sendDiagnostics("stuck on $eventId since Tuesday") }
        then { event<WorldEvent.DumpReceived> { it.dump.note == "stuck on $eventId since Tuesday" } }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
    )
    fun a_sent_report_is_told_as_sent_and_the_word_clears_itself() = rigTest {
        // What the user is told once the dump is handed off: "sent", never more.
        createAndJoin()

        sendDiagnostics(NOTE)

        awaitDumps(1)
        awaitState { it.ui.overlays.reportNotice == ReportNotice.SENT }
        awaitState { it.ui.overlays.reportNotice == null }
    }

    @Test
    @Verifies(spec = "app-experience", requirement = "The app menu is one tap away on every screen")
    fun the_screen_names_the_running_build() = rigTest {
        // The app menu's footer reads the build the composition was handed, not a guess.
        val build = awaitState { it.ui.build != BuildLine.UNKNOWN }.ui.build
        assertTrue(build.version.isNotBlank() && build.version != BuildLine.UNKNOWN.version, "the version: $build")
    }
}
