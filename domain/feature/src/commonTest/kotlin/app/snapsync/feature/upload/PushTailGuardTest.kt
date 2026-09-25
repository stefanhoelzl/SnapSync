package app.snapsync.feature.upload

import app.snapsync.model.EventConfig
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.MembershipRead
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        assertTrue(PushTailGuard(membership("E")).joinsTail("E"))
    }

    @Test
    fun a_push_for_another_event_joins_nothing() {
        assertFalse(PushTailGuard(membership("E")).joinsTail("OTHER"))
    }

    @Test
    fun a_push_for_a_locally_left_event_joins_nothing() {
        // Leave is local-only, so the backend keeps pushing the left event; after the leave no event is configured.
        assertFalse(PushTailGuard(membership(null)).joinsTail("LEFT"))
    }

    @Test
    fun a_push_while_the_membership_is_unreadable_joins_nothing() {
        val unreadable = object : ConfigSource {
            override val config: StateFlow<EventConfig?> = MutableStateFlow(null)
            override val membership: MembershipRead = MembershipRead.Unreadable
        }
        assertFalse(PushTailGuard(unreadable).joinsTail("E"), "an event we cannot read is not one we may work on")
    }

    @Test
    fun the_membership_is_read_at_every_push() {
        var active: String? = "E"
        val live = object : ConfigSource {
            override val config: StateFlow<EventConfig?>
                get() = MutableStateFlow(active?.let(::config))
        }
        val guard = PushTailGuard(live)
        assertTrue(guard.joinsTail("E"))
        active = "F"
        assertFalse(guard.joinsTail("E"), "a guard that held its first answer would wake the tail for a left event")
    }

    private fun membership(eventId: String?): ConfigSource = object : ConfigSource {
        override val config: StateFlow<EventConfig?> = MutableStateFlow(eventId?.let(::config))
    }

    private fun config(eventId: String) =
        EventConfig(eventId, "E", captureCutoff("2026-01-01T00:00:00Z"), maxPhotoDate = captureCeiling("2099-01-01T00:00:00Z"))
}
