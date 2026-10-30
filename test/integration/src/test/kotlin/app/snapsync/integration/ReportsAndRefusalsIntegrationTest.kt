package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.AttestationRefusal
import app.snapsync.levers.NetworkState
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.model.DiagnosticKeys
import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * **What leaves the phone, and what a refused phone is told** (capability `privacy-security`), over the real composed
 * stack: a refusal named for what it is and cleared once the service stops making it, a detailed bug report that reads
 * what the screen showed and leaves only when the user sends it, and an event used without a failure that sends
 * nothing.
 */
class ReportsAndRefusalsIntegrationTest {

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A refused phone is told why",
        scenario = "A phone whose proof is not recognised is not blamed",
    )
    fun a_phone_the_service_cannot_verify_is_told_so_and_offered_a_report() = behaviour {
        given { refusedAs(AttestationRefusal.DEVICE_UNVERIFIABLE) }

        whenever { createEvent(Defaults.EVENT_NAME, Defaults.WINDOW_START, Defaults.WINDOW_END) }
        then {
            screen<Layer.CreateEvent> { it.error == ScreenMessage.DEVICE_UNVERIFIABLE && it.error?.offersReport == true }
            neverScreen<Layer.CreateEvent> { it.error != null && it.error != ScreenMessage.DEVICE_UNVERIFIABLE }
        }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A refused phone is told why",
        scenario = "An unofficial copy of the app is pointed to the store",
    )
    fun an_unofficial_copy_is_told_it_is_not_the_official_app() = behaviour {
        given { refusedAs(AttestationRefusal.APP_NOT_GENUINE) }

        whenever { createEvent(Defaults.EVENT_NAME, Defaults.WINDOW_START, Defaults.WINDOW_END) }
        then {
            screen<Layer.CreateEvent> { it.error == ScreenMessage.APP_NOT_GENUINE && it.error?.offersReport == false }
            neverScreen<Layer.CreateEvent> { it.error != null && it.error != ScreenMessage.APP_NOT_GENUINE }
        }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A refused phone is told why",
        scenario = "A refusal the service stops making clears",
    )
    fun a_refusal_the_service_stops_making_clears_and_creating_and_joining_work() = behaviour {
        given {
            refusedAs(AttestationRefusal.DEVICE_MODIFIED)
            step("told it is refused", act = {
                createEvent(
                    Defaults.EVENT_NAME,
                    Defaults.WINDOW_START,
                    Defaults.WINDOW_END,
                )
            }, until = {
                screen<Layer.CreateEvent> { it.error == ScreenMessage.DEVICE_MODIFIED }
                settled { true }
            })
        }

        whenever {
            backend.refuseAttestation()
            createEvent(Defaults.EVENT_NAME, Defaults.WINDOW_START, Defaults.WINDOW_END)
        }
        then {
            event<WorldEvent.EventCreated>()
            screen<Layer.JoiningEvent>()
        }

        whenever { confirmJoin() }
        then {
            event<WorldEvent.MemberJoined>()
            screen<Layer.Joined>()
            neverScreen<Layer.CreateEvent> { it.error == ScreenMessage.DEVICE_MODIFIED }
        }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
        scenario = "The report shows what the user saw and what slowed the device",
    )
    fun a_report_reads_the_shown_counts_and_the_conditions_slowing_the_device() = behaviour {
        given {
            step("only the app uploads", act = { devControls.pin(app = true, extension = false) }, until = {})
            joinedEvent()
            step("ten photos, three of them shared", act = {
                (0 until 10).forEach { takePhoto("P$it") }
                os.foreground()
            }, until = {
                times(4) { event<WorldEvent.SessionTransferCreated>() }
                settled { true }
            })
            step("on mobile data, power saving on", act = {
                network.set(NetworkState.RESTRICTED)
                deviceConditions.set(mapOf("power_saving" to "true"))
            }, until = { settled { true } })
            step("three of them shared, the screen read", act = {
                listOf("P0", "P1", "P2").forEach { uploadSession.complete(primaryKey(it)) }
                os.foreground()
                advance(STATUS_POLL)
            }, until = {
                screen<Layer.Joined> { it.counts?.shared == DirectionCount.Progress(3, 10) }
                settled { true }
            })
        }

        lateinit var received: Seen<WorldEvent.DumpReceived>
        whenever { tap(UiIntent.SendDiagnostics(NOTE, "Joined")) }
        then { received = event<WorldEvent.DumpReceived>() }

        val state = received.value.dump.state
        assertEquals("3/10", state[DiagnosticKeys.SHOWN_SHARED])
        assertEquals("online_restricted", state[DiagnosticKeys.NETWORK])
        assertEquals("true", state[DiagnosticKeys.POWER_SAVING])
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
        scenario = "A member with limited photo access sends a report",
    )
    fun a_report_from_a_limited_member_says_how_many_photos_the_selection_holds() = behaviour {
        val selection = (1..12).map { "S$it" }
        given {
            photoAccess(PhotoGrant.LIMITED)
            joinedEvent()
            step("twelve photos selected", act = {
                selection.forEach { library.add(it) }
                library.changeSelection(selection)
            }, until = { settled { true } })
        }

        lateinit var received: Seen<WorldEvent.DumpReceived>
        whenever { tap(UiIntent.SendDiagnostics(NOTE, "Joined")) }
        then { received = event<WorldEvent.DumpReceived>() }

        assertEquals("12", received.value.dump.state[DiagnosticKeys.SELECTION_PHOTOS])
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
        scenario = "The user cancels",
    )
    fun a_report_sheet_dismissed_sends_nothing_and_confirms_nothing() = behaviour {
        given {
            joinedEvent()
            step("the report sheet open", act = { tap(UiIntent.MenuReportBug) }, until = {
                event<WorldEvent.UiChanged> { it.ui.overlays.reportingBug }
                settled { true }
            })
        }

        whenever { tap(UiIntent.ReportBugDismiss) }
        then {
            event<WorldEvent.UiChanged> { !it.ui.overlays.reportingBug && it.ui.overlays.reportNotice == null }
            never<WorldEvent.DumpReceived>()
            never<WorldEvent.UiChanged> { it.ui.overlays.reportNotice != null }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
        scenario = "A report the app offers still waits for the user",
    )
    fun the_offered_report_opens_written_and_sends_nothing_until_confirmed() = behaviour {
        given {
            refusedAs(AttestationRefusal.DEVICE_UNVERIFIABLE)
            step("told it could not be verified", act = {
                createEvent(Defaults.EVENT_NAME, Defaults.WINDOW_START, Defaults.WINDOW_END)
            }, until = {
                screen<Layer.CreateEvent> { it.error == ScreenMessage.DEVICE_UNVERIFIABLE }
                settled { true }
            })
        }

        whenever { tap(UiIntent.ReportRefusal(ScreenMessage.DEVICE_UNVERIFIABLE)) }
        then {
            event<WorldEvent.UiChanged> {
                it.ui.overlays.reportingBug && it.ui.overlays.reportSeed == ScreenMessage.DEVICE_UNVERIFIABLE
            }
            never<WorldEvent.DumpReceived>()
            settled { true }
        }

        whenever { tap(UiIntent.ReportBugDismiss) }
        then {
            event<WorldEvent.UiChanged> { !it.ui.overlays.reportingBug }
            never<WorldEvent.DumpReceived>()
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "Device logs stay on the device",
        scenario = "No failure and no report",
    )
    fun an_event_used_without_a_failure_sends_no_log() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
        }

        whenever {
            takePhoto("P1")
            uploadAll()
            publicBackend.foreignDevice(DEV_F, listOf("FQ"))
            os.foreground()
            downloadSession.finish()
            os.background()
        }
        then {
            event<WorldEvent.BytesStored> { it.asset == "P1" }
            event<WorldEvent.LibraryAssetCreated> { it.asset.startsWith(imported("FQ")) }
            never<WorldEvent.DumpReceived>()
            never<WorldEvent.EventReceived>()
            settled { true }
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private suspend fun app.snapsync.control.behaviour.Given.refusedAs(reason: AttestationRefusal) =
        step("the service refuses this phone: $reason", act = { backend.refuseAttestation(reason) }, until = {})

    private companion object {
        const val NOTE = "photos stopped arriving"

        /** Long enough for the joined screen's status poll (every 2 s) to read again. */
        val STATUS_POLL = 5.seconds
    }
}
