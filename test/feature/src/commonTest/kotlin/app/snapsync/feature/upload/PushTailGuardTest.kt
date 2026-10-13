package app.snapsync.feature.upload

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.feature.support.SharedStore
import app.snapsync.feature.support.configService
import app.snapsync.model.EventConfig
import app.snapsync.model.TimestampText
import app.snapsync.services.config.ConfigService
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [PushTailGuard] — the upload arm's active-event guard, now deciding whether a push's wake joins the tail (capability
 * `receiving-photos`, "Silent-push receive seam"). The limited-grant read discipline and the direction gate are not
 * this guard's: they are the tail's units' own, and tested there.
 */
class PushTailGuardTest {

    @Test
    fun a_push_for_the_active_event_joins_the_tail() {
        assertTrue(PushTailGuard(membership("E")).joinsTail(testEventId("E")))
    }

    @Test
    fun a_push_for_another_event_joins_nothing() {
        assertFalse(PushTailGuard(membership("E")).joinsTail(testEventId("OTHER")))
    }

    @Test
    fun a_push_for_a_locally_left_event_joins_nothing() {
        // Leave is local-only, so the backend keeps pushing the left event; after the leave no event is configured.
        assertFalse(PushTailGuard(membership(null)).joinsTail(testEventId("LEFT")))
    }

    @Test
    fun a_push_while_the_membership_is_unreadable_joins_nothing() {
        // The membership cannot be read — a device locked since boot — so the membership reads Unreadable.
        val unreadable = configService(store = SharedStore().apply { membershipUnreadable() })
        assertFalse(
            PushTailGuard(unreadable).joinsTail(testEventId("E")),
            "an event we cannot read is not one we may work on",
        )
    }

    @Test
    fun the_membership_is_read_at_every_push() = runTest {
        val live = membership("E")
        val guard = PushTailGuard(live)
        assertTrue(guard.joinsTail(testEventId("E")))
        live.save(config("F"))
        assertFalse(
            guard.joinsTail(testEventId("E")),
            "a guard that held its first answer would wake the tail for a left event",
        )
    }

    @Test
    fun a_push_payload_joins_the_tail_only_when_it_names_the_active_event() {
        val guard = PushTailGuard(membership("E"))
        assertTrue(guard.joinsTail(mapOf<Any?, Any?>("eventId" to testEventId("E").value, "seq" to 3L)))
        assertFalse(guard.joinsTail(mapOf<Any?, Any?>("eventId" to testEventId("OTHER").value)), "another event's push")
        assertFalse(guard.joinsTail(mapOf<Any?, Any?>("seq" to 3L)), "a push that names no event")
    }

    private fun membership(eventId: String?): ConfigService = configService(eventId?.let(::config))

    private fun config(eventId: String) =
        EventConfig(
            testEventId(eventId),
            "E",
            CaptureCutoff(TimestampText.parse("2026-01-01T00:00:00Z")),
            maxPhotoDate = CaptureCeiling(TimestampText.parse("2099-01-01T00:00:00Z")),
            endsAt = EventEnd(TimestampText.parse("2099-12-31T00:00:00Z")),
            deletesAt = DeletesAt(TimestampText.parse("2099-12-31T00:00:00Z")),
        )
}
