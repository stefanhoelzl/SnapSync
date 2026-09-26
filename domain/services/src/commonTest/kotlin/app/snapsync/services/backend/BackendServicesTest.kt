package app.snapsync.services.backend

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

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
        val found = assertIs<EventLookup.Found>(servicesAnswering(meta(startsAt = "2026-07-01T00:00:00.182Z")).directory.fetch("E"))
        assertEquals("2026-07-01T00:00:00Z", found.startsAt.at.iso)
    }

    @Test
    fun a_served_read_missing_or_blanking_any_field_is_failed_never_an_invented_one() = runTest {
        for (reply in listOf(meta(name = null), meta(name = "  "), meta(startsAt = null), meta(endsAt = null), meta(deletesAt = null), meta(startsAt = "garbage"))) {
            assertEquals(EventLookup.Failed, servicesAnswering(reply).directory.fetch("E"), "$reply")
        }
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
    fun a_400_is_the_name_unless_it_names_a_date_field() = runTest {
        assertEquals(CreateOutcome.InvalidName, servicesAnswering(Reply.Refused(400, "invalid name")).creation.create("", "s", null))
        assertEquals(CreateOutcome.InvalidWindow, servicesAnswering(Reply.Refused(400, "invalid endsAt")).creation.create("n", "s", "e"))
        assertEquals(CreateOutcome.InvalidWindow, servicesAnswering(Reply.Refused(400, "invalid startsAt")).creation.create("n", "s", "e"))
    }

    @Test
    fun every_other_create_answer_is_transient() = runTest {
        for (reply in listOf(Reply.Refused(502, ""), Reply.Malformed("x"), offline)) {
            assertEquals(CreateOutcome.Transient, servicesAnswering(reply).creation.create("n", "s", null), "$reply")
        }
    }

    @Test
    fun a_rename_answers_the_echoed_name_not_the_submitted_one() = runTest {
        assertEquals(RenameOutcome.Renamed("Echoed"), servicesAnswering(Reply.Ok(EventRenamed("Echoed"))).rename.rename("E", "Asked"))
    }

    @Test
    fun a_rename_400_is_the_name_and_a_404_is_transient_never_event_gone() = runTest {
        assertEquals(RenameOutcome.InvalidName, servicesAnswering(Reply.Refused(400, "")).rename.rename("E", " "))
        assertEquals(RenameOutcome.Transient, servicesAnswering(Reply.Refused(404, "")).rename.rename("E", "n"))
        assertEquals(RenameOutcome.Transient, servicesAnswering(Reply.Ok(EventRenamed(null))).rename.rename("E", "n"), "no echo is malformed")
        assertEquals(RenameOutcome.Transient, servicesAnswering(offline).rename.rename("E", "n"))
    }

    // ── membership ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_full_event_and_a_gone_one_are_their_own_answers_and_everything_else_failed() = runTest {
        assertEquals(JoinResult.JOINED, servicesAnswering(Reply.Ok(Unit)).join.join("E", "D"))
        assertEquals(JoinResult.EVENT_FULL, servicesAnswering(Reply.Refused(409, "")).join.join("E", "D"))
        assertEquals(JoinResult.EVENT_NOT_FOUND, servicesAnswering(Reply.Refused(404, "")).join.join("E", "D"))
        assertEquals(JoinResult.FAILED, servicesAnswering(Reply.Refused(500, "")).join.join("E", "D"))
        assertEquals(JoinResult.FAILED, servicesAnswering(offline).join.join("E", "D"))
    }

    @Test
    fun a_publish_is_confirmed_only_by_a_served_write() = runTest {
        val manifest = DeviceManifest("D", emptyList())
        assertTrue(servicesAnswering(Reply.Ok(Unit)).manifest.publish("E", "D", manifest))
        assertEquals(false, servicesAnswering(Reply.Refused(409, "")).manifest.publish("E", "D", manifest))
        assertEquals(false, servicesAnswering(offline).manifest.publish("E", "D", manifest))
    }

    @Test
    fun leaving_is_best_effort_and_never_throws() = runTest {
        assertTrue(servicesAnswering(Reply.Ok(Unit)).leave.notifyLeaving("E").isSuccess)
        assertTrue(servicesAnswering(Reply.Refused(404, "")).leave.notifyLeaving("E").isFailure)
        assertTrue(servicesAnswering(offline).leave.notifyLeaving("E").isFailure)
    }

    @Test
    fun the_leaving_device_is_resolved_per_call_and_an_unreadable_one_is_a_failure() = runTest {
        var reads = 0
        val services = servicesAnswering(Reply.Ok(Unit)) { reads++; "D" }
        val leave = services.leave
        assertEquals(0, reads, "building the service reads no identity — a locked launch composes it")
        leave.notifyLeaving("E")
        leave.notifyLeaving("E")
        assertEquals(2, reads)
        val locked = servicesAnswering(Reply.Ok(Unit)) { error("keychain locked") }
        assertTrue(locked.leave.notifyLeaving("E").isFailure)
        assertTrue(locked.pushTokens.publish(app.snapsync.model.ApnsPushToken("t", "e")).isFailure)
    }

    @Test
    fun a_push_registration_succeeds_only_on_a_served_write() = runTest {
        val token = app.snapsync.model.ApnsPushToken("t", "sandbox")
        assertTrue(servicesAnswering(Reply.Ok(Unit)).pushTokens.publish(token).isSuccess)
        assertTrue(servicesAnswering(Reply.Refused(401, "")).pushTokens.publish(token).isFailure)
    }

    // ── the listings ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun the_device_listing_recomposes_each_storage_key() = runTest {
        val listed = servicesAnswering(Reply.Ok(listOf(DeviceFile("A", ResourceRole.PRIMARY, "IMG_1.HEIC")))).deviceFiles.list("D")
        assertEquals(listOf(StoredResource("A-primary.heic", "A")), listed.getOrThrow())
    }

    @Test
    fun a_listing_that_does_not_decode_is_a_permanent_shape_failure_apart_from_a_transient_one() = runTest {
        assertIs<DeviceListingShapeException>(servicesAnswering(Reply.Malformed("no assetId")).deviceFiles.list("D").exceptionOrNull())
        val transient = servicesAnswering(offline).deviceFiles.list("D").exceptionOrNull()
        assertTrue(transient != null && transient !is DeviceListingShapeException)
        val refused = servicesAnswering(Reply.Refused(502, "")).deviceFiles.list("D").exceptionOrNull()
        assertTrue(refused != null && refused !is DeviceListingShapeException)
    }

    @Test
    fun the_union_is_served_or_a_failure_never_an_empty_one() = runTest {
        val asset = UnionAsset("D", "A", "c", emptyList())
        assertEquals(listOf(asset), servicesAnswering(Reply.Ok(listOf(asset))).union.union("E").getOrThrow())
        assertTrue(servicesAnswering(Reply.Refused(404, "")).union.union("E").isFailure)
        assertTrue(servicesAnswering(offline).union.union("E").isFailure)
    }
}
