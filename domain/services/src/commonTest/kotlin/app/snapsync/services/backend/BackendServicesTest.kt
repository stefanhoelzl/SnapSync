package app.snapsync.services.backend

import app.snapsync.data.backend.BackendGateway
import app.snapsync.domain.event.EventId
import app.snapsync.domain.service.ServiceFault
import app.snapsync.model.CreateOutcome
import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.ManifestResource
import app.snapsync.model.PushEndpoint
import app.snapsync.model.ResourceRole
import app.snapsync.model.StoredResource
import app.snapsync.model.UnionTrigger
import app.snapsync.ports.HttpResult
import app.snapsync.services.identity.MapSecureStore
import app.snapsync.services.identity.identityOf
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import app.snapsync.wire.models.EventCreate
import app.snapsync.wire.models.Manifest
import app.snapsync.wire.models.Role
import app.snapsync.wire.runtime.WireJson
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What each backend answer MEANS to the app — the need-shaped services' mappings over the gateway's replies. How an
 * answer becomes a reply is the generated wire's (`BackendGatewayTest`); what the real backend answers is the `Backend`
 * contract's.
 */
class BackendServicesTest {

    private val offline = HttpResult.Unreachable("offline")
    private val maintenance = HttpResult.Response(503, mapOf("Retry-After" to "60"), "maintenance")

    private fun event(name: String = "Party") =
        """{"eventId":"${testEventId("E1").value}","name":"$name","createdAt":"2026-06-01T00:00:00Z","startsAt":"2026-07-01T00:00:00Z",""" +
            """"endsAt":"2026-07-08T00:00:00Z","openUntil":"2026-07-08T00:00:00Z","capacity":40,""" +
            """"deletesAt":"2026-07-31T00:00:00Z","closedAt":null,"completedAt":null}"""

    // ── what the device received ───────────────────────────────────────────────────────────────────

    @Test
    fun a_received_position_is_reported_only_when_the_backend_recorded_it() = runTest {
        assertEquals(true, servicesAnswering(text(200, "")).received.report(testEventId("E"), 7))
        assertEquals(false, servicesAnswering(text(409, "not a member")).received.report(testEventId("E"), 7))
        assertEquals(false, servicesAnswering(text(502, "upstream")).received.report(testEventId("E"), 7))
        assertEquals(false, servicesAnswering(offline).received.report(testEventId("E"), 7))
        assertEquals(
            false,
            servicesAnswering(ok(), MapSecureStore(unavailable = true)).received.report(testEventId("E"), 7),
        )
    }

    // ── create ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_served_create_answers_the_minted_id_and_stored_name() = runTest {
        assertEquals(
            CreateOutcome.Created(testEventId("E1"), "Party"),
            servicesAnswering(json(201, event())).creation.create("Party", "s", null),
        )
    }

    @Test
    fun a_created_event_whose_id_is_no_event_id_is_a_reply_the_backend_could_not_have_meant() = runTest {
        assertEquals(
            CreateOutcome.Transient(),
            servicesAnswering(
                json(201, event().replace(testEventId("E1").value, "E1")),
            ).creation.create("Party", "s", null),
        )
    }

    @Test
    fun a_create_carries_the_zone_the_device_is_in_at_that_moment() = runTest {
        val transport = ScriptedTransport { json(201, event()) }
        var zone = "Europe/Berlin"
        val services = BackendServices(
            BackendGateway(exchangeOver(transport), ScriptedCredential(null)),
            identityOf("D", MapSecureStore()),
            zone = { zone },
            eventOver = null,
        )
        fun lastZone() = WireJson.decodeFromString(EventCreate.serializer(), transport.requests.last().body!!).zone
        services.creation.create("Party", "s", null)
        assertEquals("Europe/Berlin", lastZone())
        zone = "America/New_York"
        services.creation.create("Party", "s", null)
        assertEquals("America/New_York", lastZone(), "read at the create, not at composition")
    }

    @Test
    fun a_refused_create_is_the_name_unless_it_names_the_window() = runTest {
        for (body in listOf("invalid name", "invalid body", "invalid keyId")) {
            assertEquals(
                CreateOutcome.InvalidName,
                servicesAnswering(text(400, body)).creation.create("", "s", null),
                body,
            )
        }
        for (body in listOf("invalid endsAt", "invalid startsAt")) {
            assertEquals(
                CreateOutcome.InvalidWindow,
                servicesAnswering(text(400, body)).creation.create("n", "s", "e"),
                body,
            )
        }
    }

    @Test
    fun a_create_still_refused_for_its_credential_is_unverified_not_transient() = runTest {
        assertEquals(
            CreateOutcome.Unverified,
            servicesAnswering(UNAUTHORIZED).creation.create("n", "s", null),
        )
    }

    @Test
    fun every_other_create_answer_is_transient_with_how_the_service_failed() = runTest {
        for ((reply, fault) in listOf(
            text(502, "") to ServiceFault.TROUBLE,
            maintenance to ServiceFault.UPDATING,
            json(201, "<html/>") to ServiceFault.UNREACHABLE,
            offline to ServiceFault.UNREACHABLE,
        )) {
            assertEquals(
                CreateOutcome.Transient(fault),
                servicesAnswering(reply).creation.create("n", "s", null),
                "$reply",
            )
        }
    }

    @Test
    fun a_refusal_no_service_names_is_told_as_the_service_not_reached_never_as_its_failure() = runTest {
        // An answer from a working service that the app has no reading of — here, this build too old — is told as the
        // one condition whose remedy may be the member's own: never as the service failing.
        val tooOld = json(426, """{"error":"app too old","minAppVersion":"9.9"}""")
        assertEquals(
            CreateOutcome.Transient(ServiceFault.UNREACHABLE),
            servicesAnswering(tooOld).creation.create("n", "s", null),
        )
    }

    // ── membership ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_publish_is_confirmed_only_by_a_served_write() = runTest {
        val manifest = DeviceManifest(testDeviceId("D"), emptyList(), version = 1)
        assertTrue(servicesAnswering(ok()).manifest.publish(testEventId("E"), testDeviceId("D"), manifest))
        assertEquals(
            false,
            servicesAnswering(
                text(409, "not a member"),
            ).manifest.publish(testEventId("E"), testDeviceId("D"), manifest),
        )
        assertEquals(
            false,
            servicesAnswering(offline).manifest.publish(testEventId("E"), testDeviceId("D"), manifest),
        )
        assertEquals(
            false,
            servicesAnswering(text(502, "upstream")).manifest.publish(testEventId("E"), testDeviceId("D"), manifest),
        )
        val transport = ScriptedTransport()
        assertEquals(
            false,
            servicesOver(
                transport,
            ).manifest.publish(testEventId("E"), testDeviceId("D"), DeviceManifest(testDeviceId("D"), emptyList())),
            "a snapshot its producer did not stamp is never sent",
        )
        assertEquals(emptyList(), transport.calls)
    }

    @Test
    fun a_publish_sends_the_snapshot_as_the_description_shapes_it() = runTest {
        val transport = ScriptedTransport()
        val asset = DeviceManifestAsset(
            testAssetId("A"),
            "2026-07-01T00:00:00Z",
            listOf(
                ManifestResource(ResourceRole.PRIMARY, "image/heic", "A-primary.heic", "IMG.HEIC"),
                ManifestResource(ResourceRole.LIVE, "video/quicktime", "A-live.mov", "IMG.MOV"),
            ),
        )
        val manifest = DeviceManifest(testDeviceId("D"), listOf(asset), version = 4, final = true)
        servicesOver(transport).manifest.publish(testEventId("E"), testDeviceId("D"), manifest)
        val sent = WireJson.decodeFromString(Manifest.serializer(), transport.requests.single().body!!)
        assertEquals(4, sent.version)
        assertTrue(sent.`final`)
        assertEquals(listOf(Role.PRIMARY, Role.LIVE), sent.assets.single().resources.map { it.role })
        assertEquals("A", sent.assets.single().assetId)
    }

    /** A closed event's asset sets are fixed: its refusal is final, so it counts as published and is not re-sent. */
    @Test
    fun a_publish_refused_because_the_event_closed_is_recorded_as_done() = runTest {
        val manifest = DeviceManifest(testDeviceId("D"), emptyList(), version = 1)
        val over = mutableListOf<EventId>()
        val completed = servicesOver(ScriptedTransport { json(410, CLOSED) }, eventOver = { over += it })
        assertTrue(completed.manifest.publish(testEventId("E"), testDeviceId("D"), manifest))
        assertEquals(listOf(testEventId("E")), over, "the service's word that the photos were deleted is passed on")
        over.clear()
        val fixed = servicesOver(ScriptedTransport { json(409, CLOSED) }, eventOver = { over += it })
        assertTrue(fixed.manifest.publish(testEventId("E"), testDeviceId("D"), manifest))
        assertTrue(over.isEmpty(), "a set fixed before events stopped closing says nothing about deletion")
        assertTrue(
            servicesAnswering(json(410, CLOSED)).manifest.publish(testEventId("E"), testDeviceId("D"), manifest),
            "a process with no membership to tell records it all the same",
        )
    }

    @Test
    fun the_device_is_resolved_at_the_call_and_an_unreadable_one_is_a_failure() = runTest {
        val store = MapSecureStore()
        val services = servicesAnswering(ok(), store)
        val pushTokens = services.pushTokens
        assertEquals(0, store.reads, "building the service reads no identity — a locked launch composes it")
        pushTokens.publish(PushEndpoint("apns", "t", "e"))
        assertTrue(store.reads > 0, "the call resolves the identity")
        val locked = servicesAnswering(ok(), MapSecureStore(unavailable = true))
        assertTrue(locked.pushTokens.publish(PushEndpoint("apns", "t", "e")).isFailure)
    }

    @Test
    fun a_push_registration_succeeds_only_on_a_served_write() = runTest {
        val token = PushEndpoint("apns", "t", "sandbox")
        assertTrue(servicesAnswering(ok(201)).pushTokens.publish(token).isSuccess)
        assertTrue(servicesAnswering(UNAUTHORIZED).pushTokens.publish(token).isFailure)
    }

    // ── the listings ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun the_device_listing_recomposes_each_storage_key() = runTest {
        val listed = servicesAnswering(
            json(200, """[{"assetId":"A","role":"primary","filename":"IMG_1.HEIC"}]"""),
        ).deviceFiles.list(testEventId("E"), testDeviceId("D"))
        assertEquals(listOf(StoredResource("A-primary.heic", testAssetId("A"))), listed.getOrThrow())
    }

    @Test
    fun a_listed_live_resource_recomposes_its_own_key_and_a_non_canonical_asset_is_a_shape_failure() = runTest {
        val live = servicesAnswering(json(200, """[{"assetId":"A","role":"live","filename":"IMG_1.MOV"}]"""))
        assertEquals(
            listOf(StoredResource("A-live.mov", testAssetId("A"))),
            live.deviceFiles.list(testEventId("E"), testDeviceId("D")).getOrThrow(),
        )
        val odd = servicesAnswering(json(200, """[{"assetId":"a/b","role":"primary","filename":"IMG.JPG"}]"""))
        assertIs<DeviceListingShapeException>(
            odd.deviceFiles.list(testEventId("E"), testDeviceId("D")).exceptionOrNull(),
        )
    }

    @Test
    fun a_listing_that_does_not_decode_is_no_answer_retried_never_a_permanent_shape_failure() = runTest {
        for (body in listOf("""[{"role":"primary"}]""", "<html>log in to this Wi-Fi</html>")) {
            val unread = servicesAnswering(json(200, body)).deviceFiles.list(testEventId("E"), testDeviceId("D"))
            assertTrue(unread.isFailure && unread.exceptionOrNull() !is DeviceListingShapeException, body)
        }
        val transient = servicesAnswering(
            offline,
        ).deviceFiles.list(testEventId("E"), testDeviceId("D")).exceptionOrNull()
        assertTrue(transient != null && transient !is DeviceListingShapeException)
        val refused = servicesAnswering(
            text(502, "upstream"),
        ).deviceFiles.list(testEventId("E"), testDeviceId("D")).exceptionOrNull()
        assertTrue(refused != null && refused !is DeviceListingShapeException)
    }

    @Test
    fun the_union_is_served_or_a_failure_never_an_empty_one() = runTest {
        val page = json(
            200,
            """[{"deviceId":"${testDeviceId("D2").value}","assetId":"A","creationDate":"c","resources":[""" +
                """{"role":"primary","contentType":"image/jpeg","key":"A-primary.jpg","filename":"IMG.JPG"}]}]""",
            mapOf("SnapSync-Cursor" to "7"),
        )
        suspend fun read(
            reply: HttpResult,
        ) = servicesAnswering(reply).union.union(testEventId("E"), 3, UnionTrigger.PUSH)
        val served = read(page).getOrThrow()
        assertEquals(7, served.cursor)
        assertEquals(listOf(testAssetId("A")), served.assets.map { it.assetId })
        assertTrue(read(text(404, "event not found")).isFailure)
        assertTrue(read(offline).isFailure)
        val strangerDevice = json(
            200,
            """[{"deviceId":"D2","assetId":"A","creationDate":"c","resources":[]}]""",
            mapOf("SnapSync-Cursor" to "7"),
        )
        assertTrue(read(strangerDevice).isFailure, "an asset of no device is no asset")
        val strangerAsset = json(
            200,
            """[{"deviceId":"${testDeviceId("D2").value}","assetId":"a/b","creationDate":"c","resources":[]}]""",
            mapOf("SnapSync-Cursor" to "7"),
        )
        assertTrue(read(strangerAsset).isFailure, "an asset whose id is not canonical is no asset")
        assertTrue(
            read(json(200, "[]")).isFailure,
            "a page without its position is one the app could not continue from",
        )
    }

    // Change `count-declared-photos`: the event's declared total rides on the page; a backend without it, or a value
    // that is no count, reads as none.
    @Test
    fun the_unions_declared_total_is_read_and_its_absence_is_none() = runTest {
        suspend fun declared(headers: Map<String, String>) =
            servicesAnswering(json(200, "[]", mapOf("SnapSync-Cursor" to "7") + headers))
                .union.union(testEventId("E"), null, UnionTrigger.FOREGROUND).getOrThrow().declared
        assertEquals(200L, declared(mapOf("SnapSync-Declared" to "200")))
        assertEquals(null, declared(emptyMap()))
        assertEquals(null, declared(mapOf("SnapSync-Declared" to "many")))
    }

    @Test
    fun the_union_is_read_without_urls_and_each_address_built_on_the_builds_backend() = runTest {
        val transport = ScriptedTransport {
            json(
                200,
                """[{"deviceId":"${testDeviceId("D2").value}","assetId":"A1","creationDate":"c","resources":[""" +
                    """{"role":"live","contentType":"video/quicktime","key":"k","filename":"IMG.MOV"}]}]""",
                mapOf("SnapSync-Cursor" to "1"),
            )
        }
        val resource = servicesOver(transport).union.union(testEventId("E"), null, UnionTrigger.FOREGROUND)
            .getOrThrow().assets.single().resources.single()
        assertEquals("GET /events/${testEventId("E")}/files?urls=false null", transport.calls.single())
        assertEquals("foreground", transport.requests.single().headers["SnapSync-Trigger"])
        assertEquals("$BASE/events/${testEventId("E")}/files/devices/${testDeviceId("D2")}/A1/live", resource.url)
    }

    private companion object {
        const val CLOSED = """{"error":"closed"}"""
    }
}
