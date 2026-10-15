package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.levers.AttestationRefusal
import app.snapsync.levers.WorldEvent
import app.snapsync.presentation.message
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.RefusalCause
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.SyncHealth
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A phone the service refuses as not genuine is told why, end to end, over the REAL composed core, driven through
 * the control protocol.
 *
 * The backend mock answers what v2 answers — `401 attestation rejected: <reason>` — and every step between it and the
 * screen is the shipped one: the attestation service reads the reason, the authenticated backend asks it for a token
 * before a tapped create or join, and the container reduces the verdict onto each surface. Before this, all of it read
 * "Couldn't connect" (Bugsink SNAPSYNC-42/43/44).
 */
class DeviceRefusalIntegrationTest {

    @Test
    @Verifies(spec = "privacy-security", requirement = "A refused phone is told why")
    @Verifies(
        spec = "create-event",
        requirement = "The front screen tells a refused phone before it tries",
        scenario = "A refused phone learns it on opening the app",
    )
    fun a_refused_phone_is_told_why_on_the_front_screen_when_the_app_opens() = behaviour {
        for (reason in DeviceRefusal.entries) {
            given {
                refused(reason)
                tokenless(reason.message)
            }
            // The refused phone holds no token, so opening the app tries to verify it — and is refused — before any tap.
            whenever {
                host.relaunch()
                os.foreground()
                os.foreground() // the launch comes to the foreground: the wake that tries to verify the phone
            }
            then { screen<Layer.CreateEvent> { it.error == reason.message } }
        }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A refused phone is told why",
        scenario = "A phone with an unlocked bootloader is told why",
    )
    fun a_phone_refused_as_modified_is_told_so_and_never_that_the_service_was_unreachable() = behaviour {
        // The service answers an unlocked bootloader as a modified device, as it answers a system its maker did not ship.
        given {
            step("the service refuses this phone as modified", act = {
                backend.refuseAttestation(AttestationRefusal.DEVICE_MODIFIED)
            }, until = {})
        }

        whenever { createEvent(Rig.EVENT_NAME, Rig.WINDOW_START, Rig.WINDOW_END) }
        then {
            screen<Layer.CreateEvent> { it.error == ScreenMessage.DEVICE_MODIFIED }
            neverScreen<Layer.CreateEvent> { it.error != null && it.error != ScreenMessage.DEVICE_MODIFIED }
        }
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "A failed create says so and changes nothing",
        scenario = "A refused phone's create says why",
    )
    fun a_refused_create_says_why_and_leaves_the_phone_in_no_event() = behaviour {
        given { refused(DeviceRefusal.DEVICE_MODIFIED) }
        // The tap itself tried to verify the phone first, so it learns the cause from this create.
        whenever { createEvent(Rig.EVENT_NAME, Rig.WINDOW_START, Rig.WINDOW_END) }
        then {
            screen<Layer.CreateEvent> { it.error == ScreenMessage.DEVICE_MODIFIED }
            // The device is still in no event.
            never<WorldEvent.EventCreated>()
            never<WorldEvent.MemberJoined>()
            neverScreen<Layer.Joined>()
        }
    }

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "The front screen tells a refused phone before it tries",
        scenario = "A refusal the service stopped making heals on the next tap",
    )
    fun a_refusal_the_service_stops_making_heals_on_the_next_tap() = behaviour {
        given {
            refused(DeviceRefusal.DEVICE_UNVERIFIABLE)
            tokenless(ScreenMessage.DEVICE_UNVERIFIABLE)
            step("reopened, and told", act = {
                host.relaunch()
                os.foreground()
            }, until = {
                screen<Layer.CreateEvent> { it.error == ScreenMessage.DEVICE_UNVERIFIABLE }
                settled { true }
            })
            step("the service stops refusing", act = { backend.refuseAttestation() }, until = {})
        }

        // No reopening: the tap re-checks, and the event is created as on any genuine phone.
        whenever { createEvent(Rig.EVENT_NAME, Rig.WINDOW_START, Rig.WINDOW_END) }
        then {
            event<WorldEvent.EventCreated>()
            screen<Layer.JoiningEvent> { it.phase is JoinPhase.Detailed }
        }
    }

    @Test
    @Verifies(spec = "join-event", requirement = "A refused phone is told why it cannot join")
    fun a_refused_join_says_why_and_its_retry_joins_once_the_service_stops_refusing() = behaviour {
        given {
            val event = registeredEvent()
            refused(DeviceRefusal.APP_NOT_GENUINE)
            step("its link opened", act = { openLink(inviteLink(event)) }, until = {
                screen<Layer.JoiningEvent> { it.phase is JoinPhase.Detailed }
            })
        }

        lateinit var refused: Seen<Layer.JoiningEvent>
        whenever { confirmJoin() }
        then {
            refused = screen<Layer.JoiningEvent> { it.phase.step == JoinPhase.Detailed.Step.DeviceRefused }
            never<WorldEvent.MemberJoined>()
            neverScreen<Layer.Joined>()
        }
        assertEquals(ScreenMessage.APP_NOT_GENUINE, (refused.value.phase as JoinPhase.Detailed).refusal)

        given { step("the service stops refusing", act = { backend.refuseAttestation() }, until = {}) }
        whenever { tap(UiIntent.RetryJoin) }
        then {
            event<WorldEvent.MemberJoined>()
            screen<Layer.Joined>()
        }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "A device that cannot be verified is shown, and never blamed on the member",
        scenario = "A refused phone's line names the cause",
    )
    fun a_joined_phone_the_service_comes_to_refuse_is_told_the_cause_on_its_status_line() = behaviour {
        given {
            joinedEvent()
            refused(DeviceRefusal.DEVICE_MODIFIED)
        }
        // The member's next photo is shared — gated calls, refused for the token the phone holds; recovering, the app
        // re-attests and is refused as not genuine. (A read of the event carries no token, so it would show nothing.)
        whenever {
            takePhoto("P1")
            os.foreground()
        }
        then {
            screen<Layer.Joined> { (it.health as? SyncHealth.Unattested)?.refusal == RefusalCause.DEVICE_MODIFIED }
        }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
        scenario = "A refused phone's offered report shows which root its proof ends at",
    )
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
        scenario = "Any other report carries no certificate facts",
    )
    fun only_a_report_offered_for_the_refusal_carries_the_certificates_the_phone_presented() = behaviour {
        // Which root a refused phone's proof ends at reaches the operator — from "Report
        // this", never from any other report.
        given {
            step("the service refuses this phone as unverifiable", act = {
                backend.refuseAttestation(AttestationRefusal.DEVICE_UNVERIFIABLE, "certificate")
            }, until = {})
            tokenless(ScreenMessage.DEVICE_UNVERIFIABLE)
        }

        lateinit var offeredDump: Seen<WorldEvent.DumpReceived>
        lateinit var menuDump: Seen<WorldEvent.DumpReceived>
        whenever {
            tap(UiIntent.ReportRefusal(ScreenMessage.DEVICE_UNVERIFIABLE))
            tap(UiIntent.SendDiagnostics("offered", "rig"))
            tap(UiIntent.SendDiagnostics("from the menu", "rig"))
        }
        then {
            offeredDump = event<WorldEvent.DumpReceived> { it.dump.note == "offered" }
            menuDump = event<WorldEvent.DumpReceived> { it.dump.note == "from the menu" }
        }

        val offered = offeredDump.value.dump.state
        assertEquals("device-unverifiable (certificate)", offered["attest_failure"])
        assertTrue("O=SnapSync Mock" in offered.getValue("attest_chain"), offered.getValue("attest_chain"))
        assertEquals("0".repeat(64), offered["attest_root_key_sha256"])
        assertTrue(menuDump.value.dump.state.keys.none { it.startsWith("attest_") }, "a menu report carries none")
    }

    /**
     * The refused phone as it is in the field: holding no token. The rig's phone attested as it started; one refused tap
     * has its token rejected, dropped, and re-attesting refused — after which it holds none, as a phone that was never
     * accepted does.
     */
    private suspend fun Given.tokenless(told: ScreenMessage) = step("a refused create", act = {
        createEvent(Rig.EVENT_NAME, Rig.WINDOW_START, Rig.WINDOW_END)
    }, until = {
        screen<Layer.CreateEvent> { it.error == told }
        settled { true }
    })

    /** The service refuses this phone for [reason] from now on. */
    private suspend fun Given.refused(reason: DeviceRefusal) = step("the service refuses this phone: $reason", act = {
        backend.refuseAttestation(AttestationRefusal.valueOf(reason.name))
    }, until = {})
}
