package app.snapsync.integration

import app.snapsync.model.Layer
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Early event completion over the REAL composition (capabilities `event-lifetime` and `manage-membership`; decision
 * record `changes/early-event-completion`): the end-of-wake step settles this device's share, reads the event's state,
 * and ends the membership on its own once the event is finished for it — and a leave the backend never heard of is
 * delivered by a later wake. `EventCompletionTest` covers the verdicts in isolation; these prove the wiring.
 *
 * The window is short (five days) so a moment exists after its end and before the event's deadline.
 */
class EventCompletionIntegrationTest {

    @Test
    fun the_only_member_settles_closes_the_event_and_leaves_at_the_end_of_a_wake() = rigTest {
        extensionUploadsOnly()
        device("clock/advance", "to" to DURING)
        val event = createAndJoin(startsAt = SHORT_START, endsAt = SHORT_END)
        device("clock/advance", "to" to AFTER_THE_END)
        // The first upload cycle after the end settles the share — on this host the operator plays the uploader.
        cycle()
        val published = deviceJson("backend/manifest", "event" to event).getValue("manifest").jsonObject
        assertEquals(true, published.getValue("final").jsonPrimitive.boolean, "the share is settled after the end")

        os("app", "onForeground")

        awaitState { it.ui.layer is Layer.CreateEvent }
        assertFalse(state().ready.configResolved, "the membership ended on its own")
        assertTrue(departed(event), "and the backend was told")
    }

    @Test
    fun a_member_still_settling_keeps_the_event_open_and_this_member_in_it() = rigTest {
        device("clock/advance", "to" to DURING)
        createAndJoin(startsAt = SHORT_START, endsAt = SHORT_END)
        // Another member who has not settled its share (a manifest without the flag).
        foreignDevice("DEV-F", "FQ")
        downloadAll()
        device("clock/advance", "to" to AFTER_THE_END)

        os("app", "onForeground")

        neverWithin(what = "an open event never ends a membership") { !it.ready.configResolved }
        assertFalse(state().joined!!.closed, "the event has not closed")
    }

    @Test
    fun a_completed_event_ends_the_membership_from_a_background_wake() = rigTest {
        device("clock/advance", "to" to DURING)
        val event = createAndJoin(startsAt = SHORT_START, endsAt = SHORT_END)
        foreignDevice("DEV-F", "FQ")
        device("clock/advance", "to" to AFTER_THE_END)
        // The clock ran out while this member was away: the sweep completed the event.
        device("backend/complete", "event" to event)

        os("app", "onSilentPush", event)

        awaitState { !it.ready.configResolved }
    }

    @Test
    fun a_leave_made_offline_reaches_the_backend_on_a_later_wake() = rigTest {
        val event = createAndJoin()
        device("backend/offline", "on" to "true")

        leave()
        assertFalse(departed(event), "the backend was unreachable")

        device("backend/offline", "on" to "false")
        os("app", "onForeground")

        val delivered = eventually(read = { departed(event) }, until = { it })
        assertTrue(delivered, "the recorded leave was delivered without the member doing anything")
    }

    private suspend fun Rig.departed(event: String): Boolean =
        deviceJson("backend/departed", "event" to event).getValue("departed").jsonPrimitive.boolean

    private companion object {
        const val SHORT_START = "2026-05-15T00:00:00"
        const val SHORT_END = "2026-05-20T00:00:00"

        /** Inside [SHORT_START]..[SHORT_END]: the event is running. */
        const val DURING = "2026-05-16T12:00:00Z"

        /** After [SHORT_END], and long before the event's deadline (30 days after its start). */
        const val AFTER_THE_END = "2026-05-22T00:00:00Z"
    }
}
