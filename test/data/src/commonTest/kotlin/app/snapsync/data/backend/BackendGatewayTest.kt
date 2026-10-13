package app.snapsync.data.backend

import app.snapsync.data.version.AppVersionGate
import app.snapsync.domain.version.VersionRefusal
import app.snapsync.ports.HttpResult
import app.snapsync.wire.api.EventFilesReply
import app.snapsync.wire.api.EventNotFound
import app.snapsync.wire.api.Limits
import app.snapsync.wire.api.Malformed
import app.snapsync.wire.api.NoAnswer
import app.snapsync.wire.api.PutDeviceReply
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.Unauthorized
import app.snapsync.wire.api.Unreachable
import app.snapsync.wire.models.DeviceConfig
import app.snapsync.wire.models.EventChange
import app.snapsync.wire.models.EventCreate
import app.snapsync.wire.models.Manifest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The backend's protocol (`docs/architecture.md`, "The backend boundary"; capability `privacy-security`, "Only a
 * rejected credential is invalidated, and only that one"; capability `app-update-required`): which token a request
 * carries, what an [Unauthorized] means and what is done about it, what every request declares, and what the version
 * gate learns.
 */
class BackendGatewayTest {

    private fun join() = Routes.JoinEvent.request("E", "D")

    private fun create() = Routes.CreateEvent.request(EventCreate("n", "2030-01-01T00:00:00Z"))

    private fun putDevice() = Routes.PutDevice.request("D", DeviceConfig())

    @Test
    fun every_request_goes_to_the_builds_backend_declaring_its_version() = runTest {
        val transport = ScriptedTransport()
        BackendGateway(exchangeOver(transport), ScriptedCredential("T1")).send(join())

        val sent = transport.requests.single()
        assertEquals("$BASE/events/E/devices/D", sent.url)
        assertEquals(VERSION, sent.headers[Limits.VERSION_HEADER])
        assertEquals("Bearer T1", sent.headers["Authorization"])
    }

    @Test
    fun a_build_that_declares_no_version_sends_no_version_header() = runTest {
        val transport = ScriptedTransport()
        BackendExchange(transport, Target(declaredVersion = ""), VersionIgnored).send(Routes.Challenge.request())
        assertEquals(null, transport.requests.single().headers[Limits.VERSION_HEADER], "the backend answers it too old")
    }

    @Test
    fun an_open_request_never_carries_a_token() = runTest {
        val transport = ScriptedTransport { json(200, """{"challenge":"c"}""") }
        BackendGateway(exchangeOver(transport), ScriptedCredential("T1")).open.send(Routes.Challenge.request())
        assertEquals(listOf("GET /attest/challenge null"), transport.calls)
    }

    @Test
    fun the_token_is_read_per_request_never_held() = runTest {
        val credential = ScriptedCredential("T1")
        val transport = ScriptedTransport()
        val gateway = BackendGateway(exchangeOver(transport), credential)

        gateway.send(join())
        credential.current = "T2"
        gateway.send(join())

        assertEquals(
            listOf("PUT /events/E/devices/D T1", "PUT /events/E/devices/D T2"),
            transport.calls,
            "a renewal in the background is picked up by the next request",
        )
    }

    @Test
    fun a_missing_token_still_sends_the_request_and_its_401_is_no_verdict_on_any_credential() = runTest {
        val credential = ScriptedCredential(null, recovered = "T2")
        val transport = ScriptedTransport { UNAUTHORIZED }

        val reply = BackendGateway(exchangeOver(transport), credential).send(join())

        assertEquals(Unauthorized, reply)
        assertEquals(listOf("PUT /events/E/devices/D null"), transport.calls, "refusing to send would strand the work")
        assertEquals(emptyList(), credential.rejections, "a request that carried no token learns nothing about one")
    }

    @Test
    fun a_tapped_create_or_join_with_no_token_obtains_one_before_it_is_sent() = runTest {
        val credential = ScriptedCredential(null, obtained = "T1")
        val transport = ScriptedTransport()
        val gateway = BackendGateway(exchangeOver(transport), credential)

        gateway.sendObtainingFirst(create())
        gateway.sendObtainingFirst(join())

        assertEquals(
            listOf("POST /events T1", "PUT /events/E/devices/D T1"),
            transport.calls,
            "the tap tries to verify the phone first",
        )
        assertEquals(1, credential.obtains, "and once a token is held, the next request just sends it")
    }

    @Test
    fun a_token_that_cannot_be_read_sends_nothing_and_answers_no_answer() = runTest {
        // The store of record is locked: nothing is sent — neither without a credential nor with a guessed one — and the
        // caller is answered as when the backend cannot be reached, which every caller retries.
        val transport = ScriptedTransport()
        val gateway = BackendGateway(exchangeOver(transport), ScriptedCredential("T1", readable = false))

        assertIs<NoAnswer>(gateway.send(join()))
        assertIs<NoAnswer>(gateway.sendObtainingFirst(create()))
        assertEquals(emptyList(), transport.calls)
    }

    @Test
    fun a_background_request_with_no_token_never_attests_on_its_own() = runTest {
        // A refused phone would otherwise be re-attested once per background request.
        val credential = ScriptedCredential(null, obtained = "T1")
        val transport = ScriptedTransport { UNAUTHORIZED }

        BackendGateway(exchangeOver(transport), credential).send(putDevice())

        assertEquals(listOf("PUT /devices/D null"), transport.calls)
        assertEquals(0, credential.obtains)
    }

    @Test
    fun a_tapped_request_whose_credential_cannot_obtain_one_is_still_sent() = runTest {
        val credential = ScriptedCredential(null) // the extension's answer, or a refused phone
        val transport = ScriptedTransport { UNAUTHORIZED }

        val reply = BackendGateway(exchangeOver(transport), credential).sendObtainingFirst(create())

        assertEquals(Unauthorized, reply, "its 401 is the request's answer")
        assertEquals(listOf("POST /events null"), transport.calls)
        assertEquals(1, credential.obtains)
    }

    /**
     * A request refused for its token is sent ONCE more with the token the recovery obtained, so the caller is
     * answered by the retry rather than told "failed" for a request the next attempt would have served.
     */
    @Test
    fun a_rejected_token_is_reported_by_value_and_the_request_retried_once_with_the_recovered_one() = runTest {
        val credential = ScriptedCredential("T1", recovered = "T2")
        val transport = ScriptedTransport { r ->
            if (r.headers["Authorization"] == "Bearer T1") {
                UNAUTHORIZED
            } else {
                ok(
                    201,
                )
            }
        }

        val reply = BackendGateway(exchangeOver(transport), credential).send(putDevice())

        assertEquals(PutDeviceReply.Recorded, reply)
        assertEquals(listOf("T1"), credential.rejections, "the rejection names the token the request CARRIED")
        assertEquals(listOf("PUT /devices/D T1", "PUT /devices/D T2"), transport.calls)
    }

    @Test
    fun only_once_a_second_rejection_is_the_requests_answer() = runTest {
        val credential = ScriptedCredential("T1", recovered = "T2")
        val transport = ScriptedTransport { UNAUTHORIZED }

        val reply = BackendGateway(exchangeOver(transport), credential).send(create())

        assertEquals(Unauthorized, reply)
        assertEquals(listOf("POST /events T1", "POST /events T2"), transport.calls, "one retry, never a loop")
        assertEquals(listOf("T1"), credential.rejections, "the retry's refusal is not reported again")
    }

    @Test
    fun no_retry_when_the_credential_has_nothing_better_to_send() = runTest {
        // The extension's credential: it drops the rejected token and offers none.
        val dropsOnly = ScriptedCredential("T1", recovered = null)
        val transport = ScriptedTransport { UNAUTHORIZED }
        val manifest = Manifest(assets = emptyList(), version = 1, `final` = false)
        assertEquals(
            Unauthorized,
            BackendGateway(exchangeOver(transport), dropsOnly).send(Routes.PublishManifest.request("E", "D", manifest)),
        )
        assertEquals(listOf("PUT /events/E/devices/D/manifest T1"), transport.calls)

        // A recovery that answers the very token that was rejected is no reason to send it again.
        val same = ScriptedCredential("T1", recovered = "T1")
        val again = ScriptedTransport { UNAUTHORIZED }
        BackendGateway(exchangeOver(again), same).send(Routes.LeaveEvent.request("E", "D", received = false))
        assertEquals(listOf("DELETE /events/E/devices/D?received=false T1"), again.calls)
    }

    @Test
    fun no_answer_or_another_refusal_is_never_mistaken_for_a_rejected_credential() = runTest {
        val credential = ScriptedCredential("T1", recovered = "T2")
        val offline = BackendGateway(exchangeOver(ScriptedTransport { HttpResult.Unreachable("offline") }), credential)
        assertIs<Unreachable>(offline.send(Routes.DeviceFiles.request("E", "D")))
        val refused = BackendGateway(exchangeOver(ScriptedTransport { text(403, "not this device") }), credential)
        refused.send(Routes.ChangeEvent.request("E", EventChange("n")))
        assertEquals(
            emptyList(),
            credential.rejections,
            "dropping a good credential on a blip costs a throttled re-attestation",
        )
    }

    @Test
    fun an_answer_outside_the_description_is_malformed_never_a_value() = runTest {
        val gateway = BackendGateway(exchangeOver(ScriptedTransport { json(200, "<html/>") }), ScriptedCredential("T1"))
        assertIs<Malformed>(gateway.send(Routes.GetEvent.request("E")), "a body this build cannot read")
        val teapot = BackendGateway(exchangeOver(ScriptedTransport { text(418, "teapot") }), ScriptedCredential("T1"))
        assertIs<Malformed>(teapot.send(join()), "a status the description does not declare")
    }

    @Test
    fun the_event_details_carry_the_token_and_a_rejection_of_it_is_recovered_once() = runTest {
        // Public, but the backend verifies a token it is sent (decision record
        // `changes/separate-event-page-from-device-api`, D7): its 401 is a verdict on that token.
        val credential = ScriptedCredential("T1", recovered = "T2")
        val transport = ScriptedTransport { r ->
            if (r.headers["Authorization"] == "Bearer T1") UNAUTHORIZED else text(404, "event not found")
        }

        val reply = BackendGateway(exchangeOver(transport), credential).send(Routes.GetEvent.request("E"))

        assertEquals(EventNotFound, reply)
        assertEquals(listOf("GET /events/E T1", "GET /events/E T2"), transport.calls)
        assertEquals(listOf("T1"), credential.rejections)
    }

    @Test
    fun the_union_carries_the_token_and_a_rejection_of_it_is_recovered_once() = runTest {
        // Public, but the backend verifies a token it is sent (decision record `changes/incremental-union`, D5): its
        // 401 is a verdict on that token, recovered like a gated route's.
        val credential = ScriptedCredential("T1", recovered = "T2")
        val transport = ScriptedTransport { r ->
            if (r.headers["Authorization"] == "Bearer T1") {
                UNAUTHORIZED
            } else {
                json(
                    200,
                    "[]",
                    mapOf("SnapSync-Cursor" to "0"),
                )
            }
        }

        val reply = BackendGateway(exchangeOver(transport), credential).send(Routes.EventFiles.request("E"))

        assertIs<EventFilesReply.Page>(reply)
        assertEquals(listOf("GET /events/E/files T1", "GET /events/E/files T2"), transport.calls)
        assertEquals(listOf("T1"), credential.rejections)
    }

    @Test
    fun a_426_on_any_route_refuses_this_build_and_any_answer_past_the_version_check_clears_it() = runTest {
        val gate = AppVersionGate()
        var reply: HttpResult = appTooOld("0.5")
        val gateway = BackendGateway(exchangeOver(ScriptedTransport { reply }, gate), ScriptedCredential("T1"))

        gateway.send(Routes.GetEvent.request("E"))
        assertEquals(
            VersionRefusal("0.5"),
            gate.refusal.value,
            "the public read learns it too — the gate precedes every route",
        )

        reply = HttpResult.Unreachable("offline")
        gateway.send(join())
        assertEquals(VersionRefusal("0.5"), gate.refusal.value, "no answer says nothing about this build")

        reply = text(404, "event not found")
        gateway.send(join())
        assertNull(gate.refusal.value, "a 404 came from past the version check — the screen heals")
    }

    @Test
    fun a_refusal_on_the_retry_reaches_the_version_gate_too() = runTest {
        val gate = AppVersionGate()
        val transport = ScriptedTransport { r ->
            if (r.headers["Authorization"] == "Bearer T1") UNAUTHORIZED else appTooOld("0.6")
        }
        BackendGateway(exchangeOver(transport, gate), ScriptedCredential("T1", recovered = "T2")).send(join())
        assertEquals(VersionRefusal("0.6"), gate.refusal.value)
    }
}
