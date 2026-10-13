package app.snapsync.services.backend

import app.snapsync.domain.asset.AssetId
import app.snapsync.model.CreateOutcome
import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.EventLookup
import app.snapsync.model.HttpResult
import app.snapsync.model.JoinResult
import app.snapsync.model.ManifestResource
import app.snapsync.model.MemberCounts
import app.snapsync.model.PushEndpoint
import app.snapsync.model.RenameOutcome
import app.snapsync.model.ResourceRole
import app.snapsync.model.StoredResource
import app.snapsync.model.TimestampText
import app.snapsync.model.UnionTrigger
import app.snapsync.services.identity.MapSecureStore
import app.snapsync.services.identity.identityOf
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

    private fun event(name: String = "Party") =
        """{"eventId":"${testEventId("E1").value}","name":"$name","createdAt":"2026-06-01T00:00:00Z","startsAt":"2026-07-01T00:00:00Z",""" +
            """"endsAt":"2026-07-08T00:00:00Z","openUntil":"2026-07-08T00:00:00Z","capacity":40,""" +
            """"deletesAt":"2026-07-31T00:00:00Z","closedAt":null,"completedAt":null}"""

    private fun details(
        name: String = "Party",
        startsAt: String = "2026-07-01T00:00:00Z",
        endsAt: String = "2026-07-08T00:00:00Z",
        deletesAt: String = "2026-07-31T00:00:00Z",
        closedAt: String? = null,
        completedAt: String? = null,
        openUntil: String = endsAt,
    ): HttpResult = json(
        200,
        """{"eventId":"${testEventId("E").value}","name":"$name","createdAt":"2026-06-01T00:00:00Z","startsAt":"$startsAt",""" +
            """"endsAt":"$endsAt","openUntil":"$openUntil","capacity":40,"deletesAt":"$deletesAt",""" +
            """"closedAt":${closedAt.quoted()},"completedAt":${completedAt.quoted()},""" +
            """"members":{"active":2,"final":1,"waiting":1}}""",
    )

    private fun String?.quoted() = this?.let { "\"$it\"" } ?: "null"

    // ── the event directory ────────────────────────────────────────────────────────────────────────

    @Test
    fun a_served_read_with_every_field_is_found() = runTest {
        val found = assertIs<EventLookup.Found>(servicesAnswering(details()).directory.fetch(testEventId("E")))
        assertEquals("Party", found.name)
        assertEquals("2026-07-01T00:00:00Z", TimestampText.format(found.startsAt.at))
        assertEquals("2026-07-08T00:00:00Z", TimestampText.format(found.endsAt.at))
        assertEquals("2026-07-31T00:00:00Z", TimestampText.format(found.deletesAt.at))
        assertEquals("2026-07-08T00:00:00Z", found.openUntil?.at?.let(TimestampText::format), "open until its end")
        assertEquals(MemberCounts(active = 2, settled = 1, waiting = 1), found.completion.members)
        assertEquals(2, found.completion.members?.active)
        assertEquals(1, found.completion.members?.settled)
    }

    @Test
    fun a_legacy_events_millisecond_start_is_normalized_toward_the_earlier_second() = runTest {
        val found = assertIs<EventLookup.Found>(
            servicesAnswering(details(startsAt = "2026-07-01T00:00:00.182Z")).directory.fetch(testEventId("E")),
        )
        assertEquals("2026-07-01T00:00:00Z", TimestampText.format(found.startsAt.at))
    }

    @Test
    fun a_served_read_blanking_or_garbling_any_field_is_failed_never_an_invented_one() = runTest {
        for (reply in listOf(
            details(name = "  "),
            details(startsAt = "garbage"),
            details(endsAt = "garbage"),
            details(deletesAt = "garbage"),
            json(200, """{"eventId":"${testEventId("E").value}","name":"Party"}"""),
        )) {
            assertEquals(EventLookup.Failed, servicesAnswering(reply).directory.fetch(testEventId("E")), "$reply")
        }
    }

    /**
     * Presence is the fact (capability `event-lifetime`): only a completed event's photos are deleted. `closedAt` is for
     * builds that predate open-until dates and means nothing to this one.
     */
    @Test
    fun a_served_read_says_whether_the_event_completed_and_ignores_closed_at() = runTest {
        suspend fun completed(closedAt: String?, completedAt: String?) = assertIs<EventLookup.Found>(
            servicesAnswering(
                details(closedAt = closedAt, completedAt = completedAt),
            ).directory.fetch(testEventId("E")),
        ).completion.completed
        assertEquals(false, completed(closedAt = null, completedAt = null))
        assertEquals(false, completed(closedAt = "2026-07-09T00:00:00Z", completedAt = null))
        assertEquals(true, completed(closedAt = "2026-07-10T00:00:00Z", completedAt = "2026-07-10T00:00:00Z"))
    }

    @Test
    fun a_served_read_carries_the_date_its_host_chose_and_an_unreadable_one_costs_only_that() = runTest {
        val chosen = assertIs<EventLookup.Found>(
            servicesAnswering(details(openUntil = "2026-07-20T00:00:00Z")).directory.fetch(testEventId("E")),
        )
        assertEquals("2026-07-20T00:00:00Z", chosen.openUntil?.at?.let(TimestampText::format))
        val garbled = assertIs<EventLookup.Found>(
            servicesAnswering(details(openUntil = "garbage")).directory.fetch(testEventId("E")),
        )
        assertEquals(null, garbled.openUntil)
    }

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

    @Test
    fun only_an_event_not_found_is_not_found_every_other_answer_is_failed() = runTest {
        assertEquals(
            EventLookup.NotFound,
            servicesAnswering(text(404, "event not found")).directory.fetch(testEventId("E")),
        )
        assertEquals(EventLookup.Failed, servicesAnswering(text(502, "upstream")).directory.fetch(testEventId("E")))
        assertEquals(EventLookup.Failed, servicesAnswering(json(200, "<html/>")).directory.fetch(testEventId("E")))
        assertEquals(EventLookup.Failed, servicesAnswering(offline).directory.fetch(testEventId("E")))
    }

    // ── create and rename ──────────────────────────────────────────────────────────────────────────

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
            CreateOutcome.Transient,
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
    fun every_other_create_answer_is_transient() = runTest {
        for (reply in listOf(text(502, ""), json(201, "<html/>"), offline)) {
            assertEquals(CreateOutcome.Transient, servicesAnswering(reply).creation.create("n", "s", null), "$reply")
        }
    }

    @Test
    fun a_rename_answers_the_echoed_name_not_the_submitted_one() = runTest {
        assertEquals(
            RenameOutcome.Renamed("Echoed"),
            servicesAnswering(json(200, event("Echoed"))).rename.rename(testEventId("E"), "Asked"),
        )
    }

    @Test
    fun a_refused_rename_is_the_name_and_a_gone_event_is_transient_never_event_gone() = runTest {
        assertEquals(
            RenameOutcome.InvalidName,
            servicesAnswering(text(400, "invalid name")).rename.rename(testEventId("E"), " "),
        )
        assertEquals(
            RenameOutcome.Transient,
            servicesAnswering(text(404, "event not found")).rename.rename(testEventId("E"), "n"),
        )
        assertEquals(
            RenameOutcome.Transient,
            servicesAnswering(json(200, """{"name":"n"}""")).rename.rename(testEventId("E"), "n"),
            "an echo missing the event is malformed",
        )
        assertEquals(RenameOutcome.Transient, servicesAnswering(offline).rename.rename(testEventId("E"), "n"))
    }

    // ── membership ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_closed_event_and_a_gone_one_are_their_own_answers_and_everything_else_failed() = runTest {
        assertEquals(JoinResult.JOINED, servicesAnswering(ok()).join.join(testEventId("E"), testDeviceId("D")))
        assertEquals(
            JoinResult.EVENT_NOT_FOUND,
            servicesAnswering(text(404, "event not found")).join.join(testEventId("E"), testDeviceId("D")),
        )
        assertEquals(
            JoinResult.FAILED,
            servicesAnswering(text(502, "upstream")).join.join(testEventId("E"), testDeviceId("D")),
        )
        assertEquals(JoinResult.FAILED, servicesAnswering(offline).join.join(testEventId("E"), testDeviceId("D")))
        assertEquals(
            JoinResult.EVENT_CLOSED,
            servicesAnswering(json(410, CLOSED)).join.join(testEventId("E"), testDeviceId("D")),
        )
        assertEquals(
            JoinResult.UNVERIFIED,
            servicesAnswering(UNAUTHORIZED).join.join(testEventId("E"), testDeviceId("D")),
            "a credential refusal",
        )
    }

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
            AssetId("A"),
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
        assertTrue(servicesAnswering(json(410, CLOSED)).manifest.publish(testEventId("E"), testDeviceId("D"), manifest))
        assertTrue(servicesAnswering(json(409, CLOSED)).manifest.publish(testEventId("E"), testDeviceId("D"), manifest))
    }

    @Test
    fun leaving_is_best_effort_and_never_throws() = runTest {
        assertTrue(servicesAnswering(ok()).leave.notifyLeaving(testEventId("E"), received = false).isSuccess)
        // An event the backend no longer holds has nothing left to leave: done, so a recorded leave stops retrying.
        assertTrue(
            servicesAnswering(
                text(404, "event not found"),
            ).leave.notifyLeaving(testEventId("E"), received = false).isSuccess,
        )
        assertTrue(
            servicesAnswering(text(502, "upstream")).leave.notifyLeaving(testEventId("E"), received = false).isFailure,
        )
        assertTrue(servicesAnswering(offline).leave.notifyLeaving(testEventId("E"), received = false).isFailure)
    }

    @Test
    fun the_leaving_device_is_resolved_at_the_call_and_an_unreadable_one_is_a_failure() = runTest {
        val store = MapSecureStore()
        val services = servicesAnswering(ok(), store)
        val leave = services.leave
        assertEquals(0, store.reads, "building the service reads no identity — a locked launch composes it")
        leave.notifyLeaving(testEventId("E"), received = false)
        assertTrue(store.reads > 0, "the call resolves the identity")
        val locked = servicesAnswering(ok(), MapSecureStore(unavailable = true))
        assertTrue(locked.leave.notifyLeaving(testEventId("E"), received = false).isFailure)
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
        assertEquals(listOf(StoredResource("A-primary.heic", AssetId("A"))), listed.getOrThrow())
    }

    @Test
    fun a_listed_live_resource_recomposes_its_own_key_and_a_non_canonical_asset_is_a_shape_failure() = runTest {
        val live = servicesAnswering(json(200, """[{"assetId":"A","role":"live","filename":"IMG_1.MOV"}]"""))
        assertEquals(
            listOf(StoredResource("A-live.mov", AssetId("A"))),
            live.deviceFiles.list(testEventId("E"), testDeviceId("D")).getOrThrow(),
        )
        val odd = servicesAnswering(json(200, """[{"assetId":"a/b","role":"primary","filename":"IMG.JPG"}]"""))
        assertIs<DeviceListingShapeException>(
            odd.deviceFiles.list(testEventId("E"), testDeviceId("D")).exceptionOrNull(),
        )
    }

    @Test
    fun a_listing_that_does_not_decode_is_a_permanent_shape_failure_apart_from_a_transient_one() = runTest {
        assertIs<DeviceListingShapeException>(
            servicesAnswering(
                json(200, """[{"role":"primary"}]"""),
            ).deviceFiles.list(testEventId("E"), testDeviceId("D")).exceptionOrNull(),
        )
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
        assertEquals(listOf(AssetId("A")), served.assets.map { it.assetId })
        assertTrue(read(text(404, "event not found")).isFailure)
        assertTrue(read(offline).isFailure)
        val strangerDevice = json(
            200,
            """[{"deviceId":"D2","assetId":"A","creationDate":"c","resources":[]}]""",
            mapOf("SnapSync-Cursor" to "7"),
        )
        assertTrue(read(strangerDevice).isFailure, "an asset of no device is no asset")
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
