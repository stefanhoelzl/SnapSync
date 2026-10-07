package app.snapsync.integration

import app.snapsync.model.DeviceRefusal
import app.snapsync.model.JoinPhase
import app.snapsync.model.Layer
import app.snapsync.model.ScreenMessage
import app.snapsync.model.SyncHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A phone the service refuses as not genuine is told why, end to end (capability `privacy-security`, "A refused phone
 * is told why"; `create-event`, `join-event`, `sync-status`), over the REAL composed core, driven through the control
 * protocol.
 *
 * The backend mock answers what v2 answers — `401 attestation rejected: <reason>` — and every step between it and the
 * screen is the shipped one: the attestation service reads the reason, the authenticated backend asks it for a token
 * before a tapped create or join, and the container reduces the verdict onto each surface. Before this, all of it read
 * "Couldn't connect" (Bugsink SNAPSYNC-42/43/44).
 */
class DeviceRefusalIntegrationTest {

    @Test
    fun a_refused_phone_is_told_why_on_the_front_screen_when_the_app_opens() = rigTest {
        for (reason in DeviceRefusal.entries) {
            refuse(reason)
            tokenless()
            // The refused phone holds no token, so opening the app tries to verify it — and is refused — before any tap.
            device("relaunch")
        foreground()
            foreground() // the launch comes to the foreground: the wake that tries to verify the phone
            awaitState { (it.ui.layer as? Layer.CreateEvent)?.error == ScreenMessage.of(reason) }
        }
    }

    @Test
    fun a_refused_create_says_why_and_leaves_the_phone_in_no_event() = rigTest {
        refuse(DeviceRefusal.DEVICE_MODIFIED)
        user("create", "name" to Rig.EVENT_NAME, "startsAt" to Rig.WINDOW_START, "endsAt" to Rig.WINDOW_END)
        // The tap itself tried to verify the phone first, so it learns the cause from this create.
        awaitState { (it.ui.layer as? Layer.CreateEvent)?.error == ScreenMessage.DEVICE_MODIFIED }
        assertFalse(state().ready.configResolved, "the device is still in no event")
    }

    @Test
    fun a_refusal_the_service_stops_making_heals_on_the_next_tap() = rigTest {
        refuse(DeviceRefusal.DEVICE_UNVERIFIABLE)
        tokenless()
        device("relaunch")
        foreground()
        awaitState { (it.ui.layer as? Layer.CreateEvent)?.error == ScreenMessage.DEVICE_UNVERIFIABLE }

        device("backend/refuse-attestation", "reason" to "off")
        // No reopening: the tap re-checks, and the event is created as on any genuine phone.
        create()
    }

    @Test
    fun a_refused_join_says_why_and_its_retry_joins_once_the_service_stops_refusing() = rigTest {
        val event = registerEvent()
        refuse(DeviceRefusal.APP_NOT_GENUINE)
        openLink(inviteLink(event))
        awaitState { (it.ui.layer as? Layer.JoiningEvent)?.phase?.let { p -> p is JoinPhase.Detailed } == true }

        user("confirmJoin")
        val refused = awaitState { state ->
            ((state.ui.layer as? Layer.JoiningEvent)?.phase as? JoinPhase.Detailed)?.step == JoinPhase.Detailed.Step.DeviceRefused
        }
        val phase = (refused.ui.layer as Layer.JoiningEvent).phase as JoinPhase.Detailed
        assertEquals(ScreenMessage.APP_NOT_GENUINE, phase.refusal)
        assertFalse(refused.ready.configResolved)

        device("backend/refuse-attestation", "reason" to "off")
        user("retryJoin")
        awaitState { it.ready.configResolved }
    }

    @Test
    fun a_joined_phone_the_service_comes_to_refuse_is_told_the_cause_on_its_status_line() = rigTest {
        createAndJoin()
        refuse(DeviceRefusal.DEVICE_MODIFIED)
        // A gated call is refused for its token; recovering, the app re-attests and is refused as not genuine.
        reconcile()
        awaitState { ((it.ui.layer as? Layer.Joined)?.health as? SyncHealth.Unattested)?.refusal == DeviceRefusal.DEVICE_MODIFIED }
    }

    @Test
    fun only_a_report_offered_for_the_refusal_carries_the_certificates_the_phone_presented() = rigTest {
        // Capability `privacy-security`: which root a refused phone's proof ends at reaches the operator — from "Report
        // this", never from any other report.
        device("backend/refuse-attestation", "reason" to "device-unverifiable", "detail" to "certificate")
        tokenless()

        user("reportRefusal")
        user("sendDiagnostics", "note" to "offered")
        user("sendDiagnostics", "note" to "from the menu")

        val dumps = eventually(read = { reportedStates() }) { it.size >= 2 }
        val offered = dumps.getValue("offered")
        assertEquals("device-unverifiable (certificate)", offered["attest_failure"])
        assertTrue("O=SnapSync Mock" in offered.getValue("attest_chain"), offered.getValue("attest_chain"))
        assertEquals("0".repeat(64), offered["attest_root_key_sha256"])
        assertTrue(dumps.getValue("from the menu").keys.none { it.startsWith("attest_") }, "a menu report carries none")
    }

    /** Every report the reporter received, by its note: its state section. */
    private suspend fun Rig.reportedStates(): Map<String, Map<String, String>> =
        deviceJson("diagnostics/sent").getValue("dumps").jsonArray.associate { dump ->
            val o = dump.jsonObject
            o.getValue("note").jsonPrimitive.content to
                o.getValue("state").jsonObject.mapValues { it.value.jsonPrimitive.content }
        }

    /**
     * The refused phone as it is in the field: holding no token. The rig's phone attested as it started; one refused tap
     * has its token rejected, dropped, and re-attesting refused — after which it holds none, as a phone that was never
     * accepted does.
     */
    private suspend fun Rig.tokenless() {
        user("create", "name" to Rig.EVENT_NAME, "startsAt" to Rig.WINDOW_START, "endsAt" to Rig.WINDOW_END)
        awaitState { (it.ui.layer as? Layer.CreateEvent)?.error != null }
    }

    private suspend fun Rig.refuse(reason: DeviceRefusal) {
        device("backend/refuse-attestation", "reason" to reason.wireName)
    }
}
