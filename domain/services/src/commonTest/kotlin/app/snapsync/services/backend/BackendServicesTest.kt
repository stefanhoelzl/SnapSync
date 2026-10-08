package app.snapsync.services.backend

import app.snapsync.model.AssetId
import app.snapsync.model.CreateOutcome
import app.snapsync.model.DeviceFile
import app.snapsync.model.DeviceManifest
import app.snapsync.model.EventCreated
import app.snapsync.model.EventLookup
import app.snapsync.model.EventMeta
import app.snapsync.model.EventRenamed
import app.snapsync.model.JoinResult
import app.snapsync.model.RenameOutcome
import app.snapsync.model.Reply
import app.snapsync.model.ResourceRole
import app.snapsync.model.StoredResource
import app.snapsync.model.UnionAsset
import app.snapsync.model.UnionPage
import app.snapsync.model.UnionTrigger
import app.snapsync.services.identity.MapSecureStore
import app.snapsync.services.identity.identityOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What each backend answer MEANS to the app — the need-shaped services' mappings, which used to live in one HTTP
 * client each. The wire is `HttpBackend`'s (`HttpBackendTest`); what the real backend answers is the `Backend`
 * contract's.
 */
class BackendServicesTest {

    private val offline = Reply.Unreachable(IllegalStateException("offline"))

    private fun meta(
        name: String? = "Party",
        startsAt: String? = "2026-07-01T00:00:00Z",
        endsAt: String? = "2026-07-08T00:00:00Z",
        deletesAt: String? = "2026-07-31T00:00:00Z",
    ) = Reply.Ok(EventMeta("E", name, null, startsAt, endsAt, deletesAt))

    // ── the event directory ────────────────────────────────────────────────────────────────────────

    @Test
    fun a_served_read_with_every_field_is_found() = runTest {
        val found = assertIs<EventLookup.Found>(servicesAnswering(meta()).directory.fetch("E"))
        assertEquals("Party", found.name)
        assertEquals("2026-07-01T00:00:00Z", found.startsAt.at.iso)
        assertEquals("2026-07-08T00:00:00Z", found.endsAt.at.iso)
        assertEquals("2026-07-31T00:00:00Z", found.deletesAt.at.iso)
    }

    @Test
    fun a_legacy_events_millisecond_start_is_normalized_toward_the_earlier_second() = runTest {
        val found = assertIs<EventLookup.Found>(
            servicesAnswering(meta(startsAt = "2026-07-01T00:00:00.182Z")).directory.fetch("E"),
        )
        assertEquals("2026-07-01T00:00:00Z", found.startsAt.at.iso)
    }

    @Test
    fun a_served_read_missing_or_blanking_any_field_is_failed_never_an_invented_one() = runTest {
        for (reply in listOf(
            meta(name = null),
            meta(name = "  "),
            meta(startsAt = null),
            meta(endsAt = null),
            meta(deletesAt = null),
            meta(startsAt = "garbage"),
            meta(endsAt = "garbage"),
            meta(deletesAt = "garbage"),
        )) {
            assertEquals(EventLookup.Failed, servicesAnswering(reply).directory.fetch("E"), "$reply")
        }
    }

    /** Presence is the fact (capability `event-lifetime`): a completed event is closed too, whatever its record says. */
    @Test
    fun a_served_read_says_whether_the_event_closed_and_completed() = runTest {
        suspend fun completion(closedAt: String?, completedAt: String?) = assertIs<EventLookup.Found>(
            servicesAnswering(
                Reply.Ok(
                    EventMeta(
                        "E",
                        "Party",
                        null,
                        "2026-07-01T00:00:00Z",
                        "2026-07-08T00:00:00Z",
                        "2026-07-31T00:00:00Z",
                        closedAt = closedAt,
                        completedAt = completedAt,
                    ),
                ),
            ).directory.fetch("E"),
        ).completion.let { it.closed to it.completed }
        assertEquals(false to false, completion(closedAt = null, completedAt = null))
        assertEquals(true to false, completion(closedAt = "2026-07-09T00:00:00Z", completedAt = null))
        assertEquals(true to true, completion(closedAt = null, completedAt = "2026-07-10T00:00:00Z"))
    }

    @Test
    fun only_a_404_is_not_found_every_other_answer_is_failed() = runTest {
        assertEquals(EventLookup.NotFound, servicesAnswering(Reply.Refused(404, "")).directory.fetch("E"))
        assertEquals(EventLookup.Failed, servicesAnswering(Reply.Refused(502, "")).directory.fetch("E"))
        assertEquals(EventLookup.Failed, servicesAnswering(Reply.Malformed("x")).directory.fetch("E"))
        assertEquals(EventLookup.Failed, servicesAnswering(offline).directory.fetch("E"))
    }

    // ── create and rename ──────────────────────────────────────────────────────────────────────────

    @Test
    fun a_served_create_answers_the_minted_id_and_stored_name() = runTest {
        assertEquals(
            CreateOutcome.Created("E1", "Party"),
            servicesAnswering(Reply.Ok(EventCreated("E1", "Party"))).creation.create("Party", "s", null),
        )
    }

    @Test
    fun a_create_carries_the_zone_the_device_is_in_at_that_moment() = runTest {
        val backend = ScriptedBackend { _, _ -> Reply.Ok(EventCreated("E1", "Party")) }
        var zone = "Europe/Berlin"
        val services = BackendServices(
            CredentialedBackend(backend, ScriptedCredential(null), versionGate = null),
            identityOf("D", MapSecureStore()),
            zone = { zone },
        )
        services.creation.create("Party", "s", null)
        assertEquals("Europe/Berlin", backend.lastCreate?.zone)
        zone = "America/New_York"
        services.creation.create("Party", "s", null)
        assertEquals("America/New_York", backend.lastCreate?.zone, "read at the create, not at composition")
    }

    @Test
    fun a_400_is_the_name_unless_it_names_a_date_field() = runTest {
        assertEquals(
            CreateOutcome.InvalidName,
            servicesAnswering(Reply.Refused(400, "invalid name")).creation.create("", "s", null),
        )
        assertEquals(
            CreateOutcome.InvalidWindow,
            servicesAnswering(Reply.Refused(400, "invalid endsAt")).creation.create("n", "s", "e"),
        )
        assertEquals(
            CreateOutcome.InvalidWindow,
            servicesAnswering(Reply.Refused(400, "invalid startsAt")).creation.create("n", "s", "e"),
        )
    }

    @Test
    fun a_create_still_refused_for_its_credential_is_unverified_not_transient() = runTest {
        assertEquals(
            CreateOutcome.Unverified,
            servicesAnswering(Reply.Refused(401, "unattested")).creation.create("n", "s", null),
        )
    }

    @Test
    fun every_other_create_answer_is_transient() = runTest {
        for (reply in listOf(Reply.Refused(502, ""), Reply.Malformed("x"), offline)) {
            assertEquals(CreateOutcome.Transient, servicesAnswering(reply).creation.create("n", "s", null), "$reply")
        }
    }

    @Test
    fun a_rename_answers_the_echoed_name_not_the_submitted_one() = runTest {
        assertEquals(
            RenameOutcome.Renamed("Echoed"),
            servicesAnswering(Reply.Ok(EventRenamed("Echoed"))).rename.rename("E", "Asked"),
        )
    }

    @Test
    fun a_rename_400_is_the_name_and_a_404_is_transient_never_event_gone() = runTest {
        assertEquals(RenameOutcome.InvalidName, servicesAnswering(Reply.Refused(400, "")).rename.rename("E", " "))
        assertEquals(RenameOutcome.Transient, servicesAnswering(Reply.Refused(404, "")).rename.rename("E", "n"))
        assertEquals(
            RenameOutcome.Transient,
            servicesAnswering(Reply.Ok(EventRenamed(null))).rename.rename("E", "n"),
            "no echo is malformed",
        )
        assertEquals(RenameOutcome.Transient, servicesAnswering(offline).rename.rename("E", "n"))
        assertEquals(RenameOutcome.Transient, servicesAnswering(Reply.Malformed("x")).rename.rename("E", "n"))
    }

    // ── membership ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_full_event_and_a_gone_one_are_their_own_answers_and_everything_else_failed() = runTest {
        assertEquals(JoinResult.JOINED, servicesAnswering(Reply.Ok(Unit)).join.join("E", "D"))
        assertEquals(JoinResult.EVENT_FULL, servicesAnswering(Reply.Refused(409, "")).join.join("E", "D"))
        assertEquals(JoinResult.EVENT_NOT_FOUND, servicesAnswering(Reply.Refused(404, "")).join.join("E", "D"))
        assertEquals(JoinResult.FAILED, servicesAnswering(Reply.Refused(500, "")).join.join("E", "D"))
        assertEquals(JoinResult.FAILED, servicesAnswering(offline).join.join("E", "D"))
        assertEquals(JoinResult.EVENT_CLOSED, servicesAnswering(Reply.Refused(410, "")).join.join("E", "D"))
        assertEquals(
            JoinResult.UNVERIFIED,
            servicesAnswering(Reply.Refused(401, "unattested")).join.join("E", "D"),
            "a credential refusal",
        )
    }

    @Test
    fun a_publish_is_confirmed_only_by_a_served_write() = runTest {
        val manifest = DeviceManifest("D", emptyList())
        assertTrue(servicesAnswering(Reply.Ok(Unit)).manifest.publish("E", "D", manifest))
        assertEquals(false, servicesAnswering(Reply.Refused(409, "")).manifest.publish("E", "D", manifest))
        assertEquals(false, servicesAnswering(offline).manifest.publish("E", "D", manifest))
        assertEquals(false, servicesAnswering(Reply.Refused(502, "")).manifest.publish("E", "D", manifest))
    }

    /** A closed event's asset sets are fixed: its refusal is final, so it counts as published and is not re-sent. */
    @Test
    fun a_publish_refused_because_the_event_closed_is_recorded_as_done() = runTest {
        val manifest = DeviceManifest("D", emptyList())
        assertTrue(servicesAnswering(Reply.Refused(410, "")).manifest.publish("E", "D", manifest))
        assertTrue(
            servicesAnswering(Reply.Refused(409, """{"error":"closed"}""")).manifest.publish("E", "D", manifest),
        )
    }

    @Test
    fun leaving_is_best_effort_and_never_throws() = runTest {
        assertTrue(servicesAnswering(Reply.Ok(Unit)).leave.notifyLeaving("E", received = false).isSuccess)
        // An event the backend no longer holds has nothing left to leave: done, so a recorded leave stops retrying.
        assertTrue(servicesAnswering(Reply.Refused(404, "")).leave.notifyLeaving("E", received = false).isSuccess)
        assertTrue(servicesAnswering(Reply.Refused(502, "")).leave.notifyLeaving("E", received = false).isFailure)
        assertTrue(servicesAnswering(offline).leave.notifyLeaving("E", received = false).isFailure)
    }

    @Test
    fun the_leaving_device_is_resolved_at_the_call_and_an_unreadable_one_is_a_failure() = runTest {
        val store = MapSecureStore()
        val services = servicesAnswering(Reply.Ok(Unit), store)
        val leave = services.leave
        assertEquals(0, store.reads, "building the service reads no identity — a locked launch composes it")
        leave.notifyLeaving("E", received = false)
        assertTrue(store.reads > 0, "the call resolves the identity")
        val locked = servicesAnswering(Reply.Ok(Unit), MapSecureStore(unavailable = true))
        assertTrue(locked.leave.notifyLeaving("E", received = false).isFailure)
        assertTrue(locked.pushTokens.publish(app.snapsync.model.PushEndpoint("apns", "t", "e")).isFailure)
    }

    @Test
    fun a_push_registration_succeeds_only_on_a_served_write() = runTest {
        val token = app.snapsync.model.PushEndpoint("apns", "t", "sandbox")
        assertTrue(servicesAnswering(Reply.Ok(Unit)).pushTokens.publish(token).isSuccess)
        assertTrue(servicesAnswering(Reply.Refused(401, "")).pushTokens.publish(token).isFailure)
    }

    // ── the listings ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun the_device_listing_recomposes_each_storage_key() = runTest {
        val listed = servicesAnswering(
            Reply.Ok(listOf(DeviceFile(AssetId("A"), ResourceRole.PRIMARY, "IMG_1.HEIC"))),
        ).deviceFiles.list("E", "D")
        assertEquals(listOf(StoredResource("A-primary.heic", AssetId("A"))), listed.getOrThrow())
    }

    @Test
    fun a_listing_that_does_not_decode_is_a_permanent_shape_failure_apart_from_a_transient_one() = runTest {
        assertIs<DeviceListingShapeException>(
            servicesAnswering(Reply.Malformed("no assetId")).deviceFiles.list("E", "D").exceptionOrNull(),
        )
        val transient = servicesAnswering(offline).deviceFiles.list("E", "D").exceptionOrNull()
        assertTrue(transient != null && transient !is DeviceListingShapeException)
        val refused = servicesAnswering(Reply.Refused(502, "")).deviceFiles.list("E", "D").exceptionOrNull()
        assertTrue(refused != null && refused !is DeviceListingShapeException)
    }

    @Test
    fun the_union_is_served_or_a_failure_never_an_empty_one() = runTest {
        val page = UnionPage(listOf(UnionAsset("D", AssetId("A"), "c", emptyList())), cursor = 7)
        suspend fun read(reply: Reply<*>) = servicesAnswering(reply).union.union("E", 3, UnionTrigger.PUSH)
        assertEquals(page, read(Reply.Ok(page)).getOrThrow())
        assertEquals(page, servicesAnswering(Reply.Ok(page)).union.union("E", null, UnionTrigger.PUSH).getOrThrow())
        assertTrue(read(Reply.Refused(404, "")).isFailure)
        assertTrue(read(offline).isFailure)
    }
}
