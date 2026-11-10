package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.domain.report.DiagnosticDump
import app.snapsync.domain.report.DiagnosticKeys
import app.snapsync.levers.LogProcess
import app.snapsync.levers.NetworkState
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.PreferenceItem
import app.snapsync.levers.StoreFault
import app.snapsync.levers.WorldEvent
import app.snapsync.rig.HostReporting
import app.snapsync.ui.state.BuildLine
import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.ReportNotice
import app.snapsync.ui.state.ReportTarget
import app.snapsync.ui.state.SwitchTo
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The operator-initiated diagnostic dump over the **real** stack: the same `snapSyncApp` core the device shells call,
 * fired through the diagnostics sheet's tap (`UiIntent.SendDiagnostics`) — the same `UserCommands` bundle presentation
 * fires — landing in the host's reporter (the `crash-reporter` service, and the world's `DumpReceived`).
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

    private suspend fun Acts.appendLog(process: LogProcess, text: String) = files.appendLog(process, text)

    private suspend fun Acts.sendDiagnostics(note: String, screen: String = SCREEN) {
        tap(UiIntent.SendDiagnostics(note, screen))
    }

    /** CAM uploaded through the extension: one cycle enqueues, the platform completes, the NEXT cycle records it. */
    private suspend fun Given.camUploaded() {
        extensionUploadsOnly()
        joinedEvent()
        photos("CAM")
        uploaded("CAM")
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
    )
    fun a_confirmed_dump_carries_both_logs_and_the_live_counts() = behaviour {
        given {
            // The operator plays the OS: one cycle enqueues, the platform completes, the NEXT cycle
            // records COMPLETED — so the dump reads the counts a real device would show mid-flow.
            camUploaded()
            step("both logs written", act = {
                appendLog(LogProcess.APP, "app: cycle finished\n")
                appendLog(LogProcess.EXTENSION, "ext: enumeration 1 seen\n")
            }, until = {})
        }

        lateinit var received: Seen<WorldEvent.DumpReceived>
        whenever { sendDiagnostics(NOTE) }
        then {
            received = event<WorldEvent.DumpReceived>()
            times(1) { event<WorldEvent.DumpReceived>() }
        }

        val dump = received.value.dump
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
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
        scenario = "A device fact cannot be read",
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
        val ids = received.value.dump.snapsync
        assertTrue(
            ids["device"].orEmpty().isNotBlank(),
            "the device id the app resolved, in SnapSync's own section: $ids",
        )
        assertTrue(DiagnosticKeys.TIME_ZONE in state, "the device's zone: $state")
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
    )
    fun a_dump_says_the_device_was_offline_or_blocked_as_the_system_reported_it() = behaviour {
        given { joinedEvent() }

        lateinit var offline: Seen<WorldEvent.DumpReceived>
        lateinit var blocked: Seen<WorldEvent.DumpReceived>
        whenever {
            network.set(NetworkState.OFFLINE)
            sendDiagnostics("offline")
            network.set(NetworkState.BLOCKED)
            sendDiagnostics("blocked")
        }
        then {
            offline = event<WorldEvent.DumpReceived> { it.dump.note == "offline" }
            blocked = event<WorldEvent.DumpReceived> { it.dump.note == "blocked" }
        }

        assertEquals("offline", offline.value.dump.state[DiagnosticKeys.NETWORK])
        assertEquals("blocked", blocked.value.dump.state[DiagnosticKeys.NETWORK])
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
    )
    fun a_dump_carries_photo_access_not_yet_asked_and_then_refused() = behaviour {
        given {
            photoAccess(PhotoGrant.NOT_DETERMINED)
            inFront()
        }

        lateinit var unasked: Seen<WorldEvent.DumpReceived>
        lateinit var refused: Seen<WorldEvent.DumpReceived>
        whenever {
            sendDiagnostics("not asked yet", screen = "CreateEvent")
            library.permission(PhotoGrant.DENIED)
            sendDiagnostics("refused", screen = "CreateEvent")
        }
        then {
            unasked = event<WorldEvent.DumpReceived> { it.dump.note == "not asked yet" }
            refused = event<WorldEvent.DumpReceived> { it.dump.note == "refused" }
        }

        assertEquals("NOT_DETERMINED", unasked.value.dump.state["photo_permission"])
        assertEquals("DENIED", refused.value.dump.state["photo_permission"])
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
    )
    fun a_dump_carries_the_mobile_data_choice_as_the_member_switched_it() = behaviour {
        given { joinedEvent() }

        lateinit var off: Seen<WorldEvent.DumpReceived>
        lateinit var on: Seen<WorldEvent.DumpReceived>
        whenever {
            tap(UiIntent.MobileData(SwitchTo.OFF))
            sendDiagnostics("switched off")
            tap(UiIntent.MobileData(SwitchTo.ON))
            sendDiagnostics("switched on")
        }
        then {
            off = event<WorldEvent.DumpReceived> { it.dump.note == "switched off" }
            on = event<WorldEvent.DumpReceived> { it.dump.note == "switched on" }
        }

        assertEquals("off", off.value.dump.state[DiagnosticKeys.MOBILE_DATA])
        assertEquals("on", on.value.dump.state[DiagnosticKeys.MOBILE_DATA])
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
        scenario = "A device fact cannot be read",
    )
    fun an_unreadable_mobile_data_choice_is_marked_unreadable_and_the_report_still_goes() = behaviour {
        given {
            joinedEvent()
            step("the mobile-data choice unreadable", act = {
                preferences.fail(PreferenceItem.MOBILE_DATA, StoreFault.READ, times = UNREADABLE_READS)
            }, until = {})
        }

        lateinit var received: Seen<WorldEvent.DumpReceived>
        whenever { sendDiagnostics(NOTE) }
        then { received = event<WorldEvent.DumpReceived>() }

        assertEquals("unreadable", received.value.dump.state[DiagnosticKeys.MOBILE_DATA])
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
        scenario = "A device fact cannot be read",
    )
    fun device_conditions_that_never_answer_are_marked_failed_and_the_report_still_goes() = behaviour {
        given {
            joinedEvent()
            step("the device's conditions never answer", act = { deviceConditions.hold(true) }, until = {})
        }

        whenever { sendDiagnostics(NOTE) }
        then {
            never<WorldEvent.DumpReceived>()
            settled { true }
        }

        lateinit var received: Seen<WorldEvent.DumpReceived>
        whenever { advance(CONDITIONS_READ_BOUND) }
        then { received = event<WorldEvent.DumpReceived>() }

        val state = received.value.dump.state
        listOf(DiagnosticKeys.POWER_SAVING, DiagnosticKeys.THERMAL).forEach { fact ->
            assertTrue(state[fact].orEmpty().startsWith("failed ("), "$fact is marked failed: $state")
        }
        assertEquals("online", state[DiagnosticKeys.NETWORK], "the facts read apart from them are still read: $state")
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
    )
    fun a_dump_says_whether_the_member_saves_the_event_to_an_album() = behaviour {
        given { joinedSavingToAlbum() }

        lateinit var received: Seen<WorldEvent.DumpReceived>
        whenever { sendDiagnostics(NOTE) }
        then { received = event<WorldEvent.DumpReceived>() }

        assertEquals("true", received.value.dump.state["save_to_album"])
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
    )
    fun a_dump_carries_the_counts_the_screen_showed() = behaviour {
        given { camUploaded() }
        // Whatever the screen shows at the tap — the report's claim is "what the member saw", not a number. An opening
        // refreshes the counts, so the completed upload is on the screen before the tap, and the app is settled so no
        // later refresh can move it before the tap.
        lateinit var shown: Seen<Layer.Joined>
        whenever { os.foreground() }
        then {
            shown = screen<Layer.Joined> { (it.counts?.shared as? DirectionCount.Progress)?.done == 1 }
            settled { true }
        }
        val counts = shown.value.counts!!

        lateinit var received: Seen<WorldEvent.DumpReceived>
        whenever { sendDiagnostics(NOTE) }
        then { received = event<WorldEvent.DumpReceived>() }

        val dump = received.value.dump
        assertEquals(counts.shared.shown(), dump.state[DiagnosticKeys.SHOWN_SHARED])
        assertEquals(counts.received.shown(), dump.state[DiagnosticKeys.SHOWN_RECEIVED])
        assertEquals("1", dump.ledger["photos_completed"], "beside the store's own count, which may differ from it")
    }

    private fun DirectionCount.shown(): String = when (this) {
        DirectionCount.Off -> "off"
        is DirectionCount.Kept -> "off, $count kept"
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
                act = { appendLog(LogProcess.APP, "app: reconcile(eventId=$eventId) ok\n") },
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
        assertEquals(eventId, dump.snapsync["event"], "the event, in SnapSync's own section")
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
                appendLog(LogProcess.APP, logLine("app", 30_000))
                appendLog(LogProcess.EXTENSION, logLine("ext", 30_000))
            }, until = {})
        }

        // Over the budget, the reporting server rejects the event and the SDK swallows the error: the dump would arrive
        // nowhere and say nothing.
        whenever { sendDiagnostics(NOTE) }
        then { event<WorldEvent.DumpReceived> { it.dump.logBytes <= DiagnosticDump.LOG_BUDGET_BYTES } }
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
    fun a_sent_report_is_told_as_sent_and_the_word_clears_itself() = behaviour {
        // What the user is told once the dump is handed off: "sent", never more.
        given { joinedEvent() }

        whenever { sendDiagnostics(NOTE) }
        then {
            event<WorldEvent.DumpReceived>()
            event<WorldEvent.UiChanged> { it.ui.overlays.reportNotice == ReportNotice.SENT }
            settled { true }
        }

        whenever { advance(NOTICE_SHOWN) }
        then { event<WorldEvent.UiChanged> { it.ui.overlays.reportNotice == null } }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
        scenario = "A device fact cannot be read",
    )
    fun an_unreadable_download_store_marks_its_counts_failed_and_the_report_still_goes() = behaviour {
        given {
            joinedEvent()
            step("the download store unreadable", act = { databases.unreadable("downloads.db", on = true) }, until = {})
        }

        lateinit var received: Seen<WorldEvent.DumpReceived>
        whenever { sendDiagnostics(NOTE) }
        then {
            received = event<WorldEvent.DumpReceived>()
            event<WorldEvent.UiChanged> { it.ui.overlays.reportNotice == ReportNotice.SENT }
        }

        val ledger = received.value.dump.ledger
        listOf("downloads_imported", "downloads_assets", "downloads_in_flight").forEach { count ->
            assertTrue(ledger[count].orEmpty().startsWith("failed ("), "$count is marked failed, never zero: $ledger")
        }
        assertEquals("0", ledger["photos_pending"], "the readable store's counts are still read: $ledger")
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
        scenario = "A device fact cannot be read",
    )
    fun an_unreadable_upload_ledger_marks_the_photo_counts_failed_not_zero() = behaviour {
        given {
            camUploaded()
            step("the upload ledger unreadable", act = { databases.unreadable("ledger.db", on = true) }, until = {})
        }

        lateinit var received: Seen<WorldEvent.DumpReceived>
        whenever { sendDiagnostics(NOTE) }
        then { received = event<WorldEvent.DumpReceived>() }

        val ledger = received.value.dump.ledger
        listOf("photos_pending", "photos_completed").forEach { count ->
            assertTrue(ledger[count].orEmpty().startsWith("failed ("), "$count is marked failed, never zero: $ledger")
        }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
        scenario = "A build that cannot report keeps the report on the phone",
    )
    fun a_build_that_reports_nowhere_keeps_the_report_and_says_so() = behaviour(reporting = HostReporting.NOWHERE) {
        lateinit var opened: Seen<WorldEvent.UiChanged>
        given {
            joinedEvent()
            step("the sheet opened", act = { tap(UiIntent.ReportBugOpen) }, until = {
                opened = event<WorldEvent.UiChanged> { it.ui.overlays.reportingBug }
            })
        }
        assertEquals(ReportTarget.THIS_DEVICE, opened.value.ui.reportDestination, "the sheet says where it goes")

        whenever { sendDiagnostics(NOTE) }
        then {
            event<WorldEvent.UiChanged> { it.ui.overlays.reportNotice == ReportNotice.SAVED }
            never<WorldEvent.DumpReceived>()
            never<WorldEvent.EventReceived>()
        }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
        scenario = "The report can be neither sent nor saved",
    )
    fun a_report_that_can_be_neither_sent_nor_saved_says_so() = behaviour(reporting = HostReporting.NOWHERE) {
        // The phone refuses every file — an iPhone not unlocked since it restarted — so the report cannot be kept.
        given {
            joinedEvent()
            step("its files refused", act = { device.lock() }, until = {})
        }

        whenever { sendDiagnostics(NOTE) }
        then {
            event<WorldEvent.UiChanged> { it.ui.overlays.reportNotice == ReportNotice.NOT_SENT }
            never<WorldEvent.DumpReceived>()
        }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
        scenario = "A report holds nothing beyond the app and the device's state",
    )
    fun a_report_holds_only_the_app_s_and_the_device_s_state() = behaviour {
        given { joinedEvent() }

        lateinit var received: Seen<WorldEvent.DumpReceived>
        whenever { sendDiagnostics(NOTE) }
        then { received = event<WorldEvent.DumpReceived>() }

        val dump = received.value.dump
        val unexpected = dump.state.keys - REPORTED_STATE
        assertTrue(unexpected.isEmpty(), "the state holds only the app's and the device's facts, not: $unexpected")
        assertEquals(REPORTED_LEDGER, dump.ledger.keys, "the ledger holds its five counts")
        assertEquals(setOf("event", "device", "process"), dump.snapsync.keys, "SnapSync's own section holds its ids")
    }

    @Test
    @Verifies(spec = "app-experience", requirement = "The app menu is one tap away on every screen")
    fun the_screen_names_the_running_build() = behaviour {
        // The app menu's footer reads the build the composition was handed, not a guess.
        lateinit var shown: Seen<WorldEvent.UiChanged>
        whenever { tap(UiIntent.CreateName(Defaults.EVENT_NAME)) }
        then { shown = event<WorldEvent.UiChanged> { it.ui.build != BuildLine.UNKNOWN } }
        val build = shown.value.ui.build
        assertTrue(build.version.isNotBlank() && build.version != BuildLine.UNKNOWN.version, "the version: $build")
    }

    private companion object {
        /** Longer than the app shows a report's word for. */
        val NOTICE_SHOWN = 5.seconds

        /** Past the bound a report waits on one device read before it writes that read failed. */
        val CONDITIONS_READ_BOUND = 3.seconds

        /** More reads of the mobile-data choice than a report and what runs beside it make. */
        const val UNREADABLE_READS = 100

        /**
         * Every fact a report's state may hold — this app's own state and the device's settings and conditions. A new
         * key is tested against the requirement's bound before it joins this list: never a photo or what it shows,
         * another app's data, a location beyond the time zone, a contact, the free storage or the device's name.
         */
        val REPORTED_STATE = setOf(
            "screen", DiagnosticKeys.SHOWN_SHARED, DiagnosticKeys.SHOWN_RECEIVED,
            "app_version", "build", "os", "device", "uploaders_carried", "extension_registrable", "app_admission",
            "upload_base", "reporter_environment", "photo_permission",
            "joined", "event_name", "direction", "shares_from", "shares_until", "save_to_album",
            DiagnosticKeys.NETWORK, DiagnosticKeys.MOBILE_DATA, DiagnosticKeys.POWER_SAVING,
            DiagnosticKeys.BACKGROUND_REFRESH, DiagnosticKeys.STANDBY_BUCKET, DiagnosticKeys.BATTERY_OPTIMIZATION_EXEMPT,
            DiagnosticKeys.BATTERY_PERCENT, DiagnosticKeys.BATTERY_CHARGING, DiagnosticKeys.THERMAL,
            DiagnosticKeys.TIME_ZONE, DiagnosticKeys.MEMORY_FOOTPRINT_MB,
            DiagnosticKeys.SELECTION_PHOTOS,
        )

        /** The ledger's counts: photos, not rows, for what is shared; the download store's three for what arrives. */
        val REPORTED_LEDGER = setOf(
            "photos_pending",
            "photos_completed",
            "downloads_imported",
            "downloads_assets",
            "downloads_in_flight",
        )
    }
}
