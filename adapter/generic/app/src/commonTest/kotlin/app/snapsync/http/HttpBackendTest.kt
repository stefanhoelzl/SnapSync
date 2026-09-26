package app.snapsync.http

import app.snapsync.model.APP_VERSION_HEADER
import app.snapsync.model.ApnsPushToken
import app.snapsync.model.AssetId
import app.snapsync.model.CreateEventRequest
import app.snapsync.model.DeviceFile
import app.snapsync.model.DeviceManifest
import app.snapsync.model.MintRequest
import app.snapsync.model.RenewRequest
import app.snapsync.model.Reply
import app.snapsync.model.ResourceRole
import app.snapsync.model.encodeToJson
import app.snapsync.ports.Backend
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [HttpBackend]'s wire — each route's method, path, headers and body, and how an answer is read — over a
 * `MockEngine`, because producing a response is the point and no real engine can be made to produce these without a
 * server. What the answers MEAN is the services' (`:domain:services`); what the real backend answers is the
 * `Backend` contract's, bound live in `jvmTest`.
 */
@OptIn(ExperimentalEncodingApi::class)
class HttpBackendTest {

    private class Sent(val method: String, val path: String, val headers: Map<String, List<String>>, val body: String)

    private val sent = mutableListOf<Sent>()

    private fun backend(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = "",
        base: String = "https://edge.test/api/v2/",
        fail: Throwable? = null,
    ): Backend = HttpBackend(
        HttpClient(
            MockEngine { request ->
                sent += Sent(
                    request.method.value,
                    request.url.encodedPath,
                    request.headers.entries().associate { it.key to it.value },
                    request.body.toByteArray().decodeToString(),
                )
                fail?.let { throw it }
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ),
        base,
        appVersion = "9.9",
    )

    private val manifest = DeviceManifest("D", emptyList(), version = 3)

    // ── the headers every call carries ─────────────────────────────────────────────────────────────

    @Test
    fun every_request_declares_this_builds_version_including_the_attest_bootstrap() = runTest {
        val backend = backend()
        backend.challenge()
        backend.getEvent("E")
        backend.joinEvent("T", "E", "D")
        assertEquals(listOf("9.9", "9.9", "9.9"), sent.map { it.headers[APP_VERSION_HEADER]?.single() })
    }

    @Test
    fun a_token_rides_as_a_bearer_and_no_token_sends_no_authorization() = runTest {
        val backend = backend()
        backend.joinEvent("T1", "E", "D")
        backend.joinEvent(null, "E", "D")
        assertEquals(listOf("Bearer T1"), sent[0].headers[HttpHeaders.Authorization])
        assertNull(sent[1].headers[HttpHeaders.Authorization], "a missing token still sends the request, unauthenticated")
    }

    /**
     * Whether a method takes a token IS whether its route is gated: the backend's gate is pinned against
     * `isGatedRequest` by `GatedPathPinTest`, and this pins every route of the port against the same predicate —
     * so a token can never be withheld from a gated route, nor offered to an ungated one.
     */
    @Test
    fun exactly_the_routes_that_take_a_token_are_the_gated_ones() = runTest {
        val backend = backend()
        val gated = listOf<suspend () -> Unit>(
            { backend.createEvent("T", CreateEventRequest("n", "s", null)) },
            { backend.renameEvent("T", "E", "n") },
            { backend.joinEvent("T", "E", "D") },
            { backend.publishManifest("T", "E", "D", manifest) },
            { backend.leaveEvent("T", "E", "D") },
            { backend.deviceFiles("T", "D") },
            { backend.putDeviceConfig("T", "D", ApnsPushToken("t", "sandbox")) },
        )
        val ungated = listOf<suspend () -> Unit>(
            { backend.challenge() },
            { backend.mintToken(MintRequest("D", "K", byteArrayOf(1), "c")) },
            { backend.renewToken(RenewRequest("D", byteArrayOf(1), "c")) },
            { backend.getEvent("E") },
            { backend.eventFiles("E") },
        )
        gated.forEach { it() }
        ungated.forEach { it() }
        sent.take(gated.size).forEach { assertTrue(isGatedRequest(it.method, it.path), "${it.method} ${it.path} is gated") }
        sent.drop(gated.size).forEach { assertTrue(!isGatedRequest(it.method, it.path), "${it.method} ${it.path} is ungated") }
    }

    // ── each route's address and body ──────────────────────────────────────────────────────────────

    @Test
    fun the_routes_are_addressed_under_the_base_with_or_without_its_trailing_slash() = runTest {
        backend(base = "https://edge.test/api/v2").joinEvent(null, "E", "D")
        backend(base = "https://edge.test/api/v2/").joinEvent(null, "E", "D")
        assertEquals(listOf("/api/v2/events/E/devices/D", "/api/v2/events/E/devices/D"), sent.map { it.path })
        assertEquals(listOf("PUT", "PUT"), sent.map { it.method })
        assertEquals("", sent[0].body, "the join carries no body")
    }

    @Test
    fun the_attest_routes_send_their_proofs_base64_and_read_the_minted_token() = runTest {
        val backend = backend(body = """{"token":"D.1.sig","challenge":"chal"}""")
        assertEquals(Reply.Ok("chal"), backend.challenge())
        assertEquals(Reply.Ok("D.1.sig"), backend.mintToken(MintRequest("D", "K", byteArrayOf(1, 2), "c")))
        assertEquals(Reply.Ok("D.1.sig"), backend.renewToken(RenewRequest("D", byteArrayOf(3), "c")))
        assertEquals(listOf("GET /api/v2/attest/challenge", "POST /api/v2/attest/token", "POST /api/v2/attest/renew"), sent.map { "${it.method} ${it.path}" })
        val mint = Json.parseToJsonElement(sent[1].body).jsonObject
        assertEquals(Base64.encode(byteArrayOf(1, 2)), mint.getValue("attestation").jsonPrimitive.content)
        assertEquals("K", mint.getValue("keyId").jsonPrimitive.content)
        val renew = Json.parseToJsonElement(sent[2].body).jsonObject
        assertEquals(Base64.encode(byteArrayOf(3)), renew.getValue("assertion").jsonPrimitive.content)
        assertNull(renew["keyId"], "a renewal carries no keyId: the backend knows the key it attested")
    }

    @Test
    fun a_success_that_names_no_token_is_malformed_rather_than_an_empty_credential() = runTest {
        assertIs<Reply.Malformed>(backend(body = "{}").mintToken(MintRequest("D", "K", byteArrayOf(1), "c")))
        assertIs<Reply.Malformed>(backend(body = "{}").challenge())
    }

    @Test
    fun create_posts_the_name_and_window_and_omits_an_absent_end() = runTest {
        val backend = backend(HttpStatusCode.Created, """{"eventId":"E1","name":"Party","createdAt":"x"}""")
        val created = backend.createEvent("T", CreateEventRequest("Party", "2030-01-01T00:00:00Z", null))
        assertEquals("E1", (created as Reply.Ok).value.eventId)
        assertEquals("Party", created.value.name)
        assertEquals("POST /api/v2/events", "${sent[0].method} ${sent[0].path}")
        val body = Json.parseToJsonElement(sent[0].body).jsonObject
        assertEquals("2030-01-01T00:00:00Z", body.getValue("startsAt").jsonPrimitive.content, "sent verbatim")
        assertNull(body["endsAt"], "an absent end is the backend's legacy +30d signal")
    }

    @Test
    fun the_event_read_carries_every_field_as_sent_and_absent_where_it_sent_none() = runTest {
        val meta = backend(body = """{"eventId":"E","name":"N","createdAt":1700000000000,"startsAt":"s","endsAt":"e"}""")
            .getEvent("E")
        val value = (meta as Reply.Ok).value
        assertEquals("N", value.name)
        assertEquals("1700000000000", value.createdAt)
        assertEquals("s", value.startsAt)
        assertNull(value.deletesAt, "absent is absent — whether that is usable is the reader's decision")
        assertEquals("GET /api/v2/events/E", "${sent[0].method} ${sent[0].path}")
    }

    @Test
    fun rename_patches_the_name_and_reads_the_echo() = runTest {
        val renamed = backend(body = """{"eventId":"E","name":"Echoed"}""").renameEvent("T", "E", "Asked")
        assertEquals("Echoed", (renamed as Reply.Ok).value.name)
        assertEquals("PATCH /api/v2/events/E", "${sent[0].method} ${sent[0].path}")
        assertEquals("Asked", Json.parseToJsonElement(sent[0].body).jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun the_manifest_is_put_at_its_sub_resource_as_the_projection_encodes_it() = runTest {
        backend().publishManifest("T", "E", "D", manifest)
        assertEquals("PUT /api/v2/events/E/devices/D/manifest", "${sent[0].method} ${sent[0].path}")
        assertEquals(manifest.encodeToJson(), sent[0].body)
    }

    @Test
    fun leave_deletes_the_membership() = runTest {
        backend().leaveEvent("T", "E", "D")
        assertEquals("DELETE /api/v2/events/E/devices/D", "${sent[0].method} ${sent[0].path}")
    }

    @Test
    fun the_device_config_is_the_apns_token_and_its_environment() = runTest {
        backend(HttpStatusCode.Created).putDeviceConfig("T", "D", ApnsPushToken("tok", "sandbox"))
        assertEquals("PUT /api/v2/devices/D", "${sent[0].method} ${sent[0].path}")
        assertEquals("""{"pushToken":{"kind":"apns","token":"tok","env":"sandbox"}}""", sent[0].body)
    }

    @Test
    fun the_union_is_read_with_each_resources_url() = runTest {
        val body = """[{"deviceId":"D","assetId":"A","creationDate":"c","resources":[
            {"key":"A-primary.jpg","url":"https://u","role":"primary","contentType":"image/jpeg","filename":"IMG.JPG"}]}]"""
        val union = (backend(body = body).eventFiles("E") as Reply.Ok).value.single()
        assertEquals("D", union.deviceId)
        assertEquals("https://u", union.resources.single().url)
        assertEquals("IMG.JPG", union.resources.single().originalFilename)
        assertEquals("GET /api/v2/events/E/files", "${sent[0].method} ${sent[0].path}")
    }

    // ── the per-device listing: strict ─────────────────────────────────────────────────────────────

    @Test
    fun the_listing_reads_identity_terms_and_ignores_extra_keys() = runTest {
        val listed = backend(body = """[{"assetId":"A","role":"primary","filename":"IMG.JPG","size":4,"url":"x"}]""")
            .deviceFiles("T", "D")
        assertEquals(Reply.Ok(listOf(DeviceFile(AssetId("A"), ResourceRole.PRIMARY, "IMG.JPG"))), listed)
        assertEquals("GET /api/v2/files/devices/D", "${sent[0].method} ${sent[0].path}")
    }

    @Test
    fun the_frozen_v1_listing_shape_is_malformed_rather_than_read_as_capture_names() = runTest {
        assertIs<Reply.Malformed>(backend(body = """[{"filename":"A-primary.jpg","url":"x"}]""").deviceFiles("T", "D"))
    }

    @Test
    fun an_unknown_role_is_malformed_rather_than_defaulted() = runTest {
        assertIs<Reply.Malformed>(backend(body = """[{"assetId":"A","role":"mystery","filename":"x.jpg"}]""").deviceFiles("T", "D"))
    }

    // ── answers that are not success ───────────────────────────────────────────────────────────────

    @Test
    fun a_refusal_carries_its_status_and_body_verbatim() = runTest {
        assertEquals(Reply.Refused(409, "event full"), backend(HttpStatusCode.Conflict, "event full").joinEvent("T", "E", "D"))
        val refused = backend(HttpStatusCode.UpgradeRequired, """{"minAppVersion":"0.4"}""").getEvent("E")
        assertEquals(Reply.Refused(426, """{"minAppVersion":"0.4"}"""), refused, "the 426 body is the minimum's only carrier")
    }

    @Test
    fun a_transport_failure_is_unreachable_and_never_thrown() = runTest {
        val failure = IllegalStateException("offline")
        val reply = backend(fail = failure).leaveEvent("T", "E", "D")
        assertIs<Reply.Unreachable>(reply)
        assertEquals("offline", reply.cause.message)
    }

    @Test
    fun a_success_whose_body_does_not_decode_is_malformed() = runTest {
        assertIs<Reply.Malformed>(backend(body = "not json").getEvent("E"))
        assertIs<Reply.Malformed>(backend(HttpStatusCode.Created, "{}").createEvent("T", CreateEventRequest("n", "s", null)))
        assertIs<Reply.Malformed>(backend(body = "{").eventFiles("E"))
    }
}
