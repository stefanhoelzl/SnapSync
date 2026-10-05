package app.snapsync.http

import app.snapsync.model.UnionTrigger
import app.snapsync.model.UNION_TRIGGER_HEADER
import app.snapsync.model.UNION_CURSOR_HEADER
import app.snapsync.model.APP_VERSION_HEADER
import app.snapsync.model.PushEndpoint
import app.snapsync.model.AssetId
import app.snapsync.model.CreateEventRequest
import app.snapsync.model.DeviceFile
import app.snapsync.model.DeviceManifest
import app.snapsync.model.MemberCounts
import app.snapsync.model.MintRequest
import app.snapsync.model.ProofFormat
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
import kotlinx.serialization.json.jsonArray
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

    private class Sent(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val body: String,
        val query: String = "",
    )

    private val sent = mutableListOf<Sent>()

    private fun backend(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = "",
        base: String = "https://edge.test/api/v2/",
        fail: Throwable? = null,
        cursor: String? = "0",
    ): Backend = HttpBackend(
        HttpClient(
            MockEngine { request ->
                sent += Sent(
                    request.method.value,
                    request.url.encodedPath,
                    request.headers.entries().associate { it.key to it.value },
                    request.body.toByteArray().decodeToString(),
                    request.url.encodedQuery,
                )
                fail?.let { throw it }
                val headers = listOfNotNull(
                    HttpHeaders.ContentType to listOf("application/json"),
                    cursor?.let { UNION_CURSOR_HEADER to listOf(it) },
                )
                respond(body, status, headersOf(*headers.toTypedArray()))
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
     * Whether a method takes a token IS whether its route verifies one: the backend's gate is pinned against
     * `isGatedRequest` by `GatedPathPinTest`, and this pins every route of the port against [verifiesToken] — the gated
     * routes and the union read (decision record `changes/incremental-union`, D5) — so a token can never be withheld
     * from a route that verifies it, nor offered to one that does not.
     */
    @Test
    fun exactly_the_routes_that_take_a_token_are_the_ones_that_verify_it() = runTest {
        val backend = backend()
        val gated = listOf<suspend () -> Unit>(
            { backend.createEvent("T", CreateEventRequest("n", "s", null)) },
            { backend.renameEvent("T", "E", "n") },
            { backend.joinEvent("T", "E", "D") },
            { backend.publishManifest("T", "E", "D", manifest) },
            { backend.leaveEvent("T", "E", "D", received = false) },
            { backend.deviceFiles("T", "D") },
            { backend.putDeviceConfig("T", "D", PushEndpoint("apns", "t", "sandbox")) },
            { backend.eventFiles("T", "E", null, UnionTrigger.FOREGROUND) },
        )
        val ungated = listOf<suspend () -> Unit>(
            { backend.challenge() },
            { backend.mintToken(MintRequest("D", "K", ProofFormat.APP_ATTEST, byteArrayOf(1), "c")) },
            { backend.renewToken(RenewRequest("D", byteArrayOf(1), "c")) },
            { backend.getEvent("E") },
        )
        gated.forEach { it() }
        ungated.forEach { it() }
        sent.take(gated.size).forEach { assertTrue(verifiesToken(it.method, it.path), "${it.method} ${it.path} verifies a token") }
        sent.drop(gated.size).forEach { assertTrue(!verifiesToken(it.method, it.path), "${it.method} ${it.path} verifies none") }
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
    fun an_android_key_attestation_goes_out_as_its_chain_one_certificate_per_entry() = runTest {
        val leaf = byteArrayOf(0x30, 0x01, 0x01)
        val root = byteArrayOf(0x30, 0x02, 0x02, 0x02)
        backend(body = """{"token":"D.1.sig"}""").mintToken(MintRequest("D", "alias", ProofFormat.ANDROID_KEY, leaf + root, "c"))
        val proof = Json.parseToJsonElement(sent.single().body).jsonObject.getValue("proof").jsonObject
        assertEquals("android-key", proof.getValue("format").jsonPrimitive.content)
        assertEquals(
            listOf(Base64.encode(leaf), Base64.encode(root)),
            proof.getValue("chain").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(setOf("format", "chain"), proof.keys, "the backend reads nothing from an Android key's alias")
    }

    @Test
    fun the_attest_routes_send_their_proofs_base64_and_read_the_minted_token() = runTest {
        val backend = backend(body = """{"token":"D.1.sig","challenge":"chal"}""")
        assertEquals(Reply.Ok("chal"), backend.challenge())
        assertEquals(Reply.Ok("D.1.sig"), backend.mintToken(MintRequest("D", "K", ProofFormat.APP_ATTEST, byteArrayOf(1, 2), "c")))
        assertEquals(Reply.Ok("D.1.sig"), backend.renewToken(RenewRequest("D", byteArrayOf(3), "c")))
        assertEquals(listOf("GET /api/v2/attest/challenge", "POST /api/v2/attest/token", "POST /api/v2/attest/renew"), sent.map { "${it.method} ${it.path}" })
        val mint = Json.parseToJsonElement(sent[1].body).jsonObject
        assertEquals("c", mint.getValue("challenge").jsonPrimitive.content)
        val proof = mint.getValue("proof").jsonObject
        assertEquals("apple-appattest", proof.getValue("format").jsonPrimitive.content)
        assertEquals(Base64.encode(byteArrayOf(1, 2)), proof.getValue("attestation").jsonPrimitive.content)
        assertEquals("K", proof.getValue("keyId").jsonPrimitive.content)
        val renew = Json.parseToJsonElement(sent[2].body).jsonObject
        assertEquals(Base64.encode(byteArrayOf(3)), renew.getValue("assertion").jsonPrimitive.content)
        assertNull(renew["keyId"], "a renewal carries no keyId: the backend knows the key it attested")
    }

    @Test
    fun a_success_that_names_no_token_is_malformed_rather_than_an_empty_credential() = runTest {
        assertIs<Reply.Malformed>(backend(body = "{}").mintToken(MintRequest("D", "K", ProofFormat.APP_ATTEST, byteArrayOf(1), "c")))
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
    fun the_event_read_carries_its_completion_state_and_member_counts() = runTest {
        val closed = (
            backend(body = """{"name":"N","closedAt":"c","completedAt":null,"members":{"active":5,"final":3}}""")
                .getEvent("E") as Reply.Ok
            ).value
        assertEquals("c", closed.closedAt)
        assertNull(closed.completedAt)
        assertEquals(MemberCounts(active = 5, settled = 3), closed.members)
        // A backend predating completion sends none of it; a malformed count object is no counts, never a guess.
        val older = (backend(body = """{"name":"N"}""").getEvent("E") as Reply.Ok).value
        assertNull(older.closedAt)
        assertNull(older.members)
        val partial = (backend(body = """{"name":"N","members":{"active":5}}""").getEvent("E") as Reply.Ok).value
        assertNull(partial.members)
        val wrongShape = (backend(body = """{"name":"N","members":{"active":"x","final":1}}""").getEvent("E") as Reply.Ok).value
        assertNull(wrongShape.members)
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
    fun leave_deletes_the_membership_saying_whether_it_received_everything() = runTest {
        backend().leaveEvent("T", "E", "D", received = true)
        backend().leaveEvent("T", "E", "D", received = false)
        assertEquals("DELETE /api/v2/events/E/devices/D", "${sent[0].method} ${sent[0].path}")
        assertEquals(listOf("received=true", "received=false"), sent.map { it.query })
    }

    @Test
    fun the_device_config_is_the_apns_token_and_its_environment() = runTest {
        backend(HttpStatusCode.Created).putDeviceConfig("T", "D", PushEndpoint("apns", "tok", "sandbox"))
        assertEquals("PUT /api/v2/devices/D", "${sent[0].method} ${sent[0].path}")
        assertEquals("""{"pushToken":{"kind":"apns","token":"tok","env":"sandbox"}}""", sent[0].body)
    }

    @Test
    fun the_device_config_carries_the_kind_the_push_adapter_states() = runTest {
        backend(HttpStatusCode.Created).putDeviceConfig("T", "D", PushEndpoint("fcm", "f:tok", "snapsync-prod"))
        assertEquals("""{"pushToken":{"kind":"fcm","token":"f:tok","env":"snapsync-prod"}}""", sent[0].body)
    }

    @Test
    fun the_union_is_read_without_urls_and_each_resource_is_addressed_at_its_download_route() = runTest {
        // Decision record `changes/incremental-union`, D1/D3: the route that redirects to the bytes is this class's to
        // build, so the backend sends none.
        val body = """[{"deviceId":"D","assetId":"A.b-c_d~e","creationDate":"c","resources":[
            {"key":"A-primary.jpg","role":"primary","contentType":"image/jpeg","filename":"IMG.JPG"}]}]"""
        val page = (backend(body = body, cursor = "17").eventFiles("T", "E", null, UnionTrigger.FOREGROUND) as Reply.Ok).value
        val union = page.assets.single()
        assertEquals("D", union.deviceId)
        assertEquals("https://edge.test/api/v2/events/E/files/devices/D/A.b-c_d~e/primary", union.resources.single().url)
        assertEquals("IMG.JPG", union.resources.single().originalFilename)
        assertEquals(17, page.cursor)
        assertEquals("GET /api/v2/events/E/files", "${sent[0].method} ${sent[0].path}")
        assertEquals("urls=false", sent[0].query)
        assertEquals(listOf("foreground"), sent[0].headers[UNION_TRIGGER_HEADER])
    }

    @Test
    fun a_union_read_from_a_position_sends_it_and_why() = runTest {
        backend(body = "[]").eventFiles("T", "E", 42, UnionTrigger.PUSH)
        assertEquals("urls=false&cursor=42", sent[0].query)
        assertEquals(listOf("push"), sent[0].headers[UNION_TRIGGER_HEADER])
        assertEquals(listOf("Bearer T"), sent[0].headers[HttpHeaders.Authorization])
    }

    @Test
    fun a_url_an_older_backend_still_sends_is_kept() = runTest {
        val body = """[{"deviceId":"D","assetId":"A","creationDate":"c","resources":[
            {"key":"A-primary.jpg","url":"https://u","role":"primary","contentType":"image/jpeg","filename":"IMG.JPG"}]}]"""
        val page = (backend(body = body).eventFiles(null, "E", null, UnionTrigger.JOIN) as Reply.Ok).value
        assertEquals("https://u", page.assets.single().resources.single().url)
    }

    @Test
    fun a_union_answer_without_its_position_is_malformed() = runTest {
        assertIs<Reply.Malformed>(backend(body = "[]", cursor = null).eventFiles(null, "E", null, UnionTrigger.JOIN))
        assertIs<Reply.Malformed>(backend(body = "[]", cursor = "x").eventFiles(null, "E", null, UnionTrigger.JOIN))
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
        val reply = backend(fail = failure).leaveEvent("T", "E", "D", received = false)
        assertIs<Reply.Unreachable>(reply)
        assertEquals("offline", reply.cause.message)
    }

    @Test
    fun a_success_whose_body_does_not_decode_is_malformed() = runTest {
        assertIs<Reply.Malformed>(backend(body = "not json").getEvent("E"))
        assertIs<Reply.Malformed>(backend(HttpStatusCode.Created, "{}").createEvent("T", CreateEventRequest("n", "s", null)))
        assertIs<Reply.Malformed>(backend(body = "{").eventFiles(null, "E", null, UnionTrigger.FOREGROUND))
    }
}
