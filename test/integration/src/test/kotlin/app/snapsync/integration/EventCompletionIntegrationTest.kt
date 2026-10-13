package app.snapsync.integration

import app.snapsync.control.Verifies
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The end of an event's life over the REAL composition (decision record `changes/back-dated-events`): the end-of-wake
 * step settles this device's share and reads the event's state, and the app never ends a membership on its own — a
 * finished event stays joined, a deleted one is shown as over — while a leave the backend never heard of is delivered
 * by a later wake. `EventCompletionTest` covers the verdicts in isolation; these prove the wiring.
 *
 * The window is short (five days) so a moment exists after its end and before the event's deadline.
 */
class EventCompletionIntegrationTest {

    @Test
    @Verifies(spec = "event-lifetime", requirement = "A member's share is settled only after their own range has ended")
    fun the_only_member_settles_its_share_and_stays_in_the_finished_event() = rigTest {
        extensionUploadsOnly()
        device("clock/advance", "to" to DURING)
        val event = createAndJoin(startsAt = SHORT_START, endsAt = SHORT_END)
        device("clock/advance", "to" to AFTER_THE_END)
        // The first upload cycle after the end settles the share — on this host the operator plays the uploader.
        cycle()
        val published = deviceJson("backend/manifest", "event" to event).getValue("manifest").jsonObject
        assertEquals(true, published.getValue("final").jsonPrimitive.boolean, "the share is settled after the end")

        os("app", "onForeground")

        neverWithin(what = "a finished event never ends a membership") { !it.ready.configResolved }
        assertFalse(departed(event), "and the backend heard of no leave")
    }

    @Test
    @Verifies(spec = "event-lifetime", requirement = "The date range closes nothing")
    fun a_member_still_settling_keeps_this_member_in_the_event() = rigTest {
        device("clock/advance", "to" to DURING)
        createAndJoin(startsAt = SHORT_START, endsAt = SHORT_END)
        // Another member who has not settled its share (a manifest without the flag).
        foreignDevice("DEV-F", "FQ")
        downloadAll()
        device("clock/advance", "to" to AFTER_THE_END)

        os("app", "onForeground")

        neverWithin(what = "an event still being shared never ends a membership") { !it.ready.configResolved }
    }

    @Test
    @Verifies(spec = "event-lifetime", requirement = "Deletion is never announced and never touches anyone's library")
    fun a_completed_event_is_kept_as_deleted_from_a_background_wake() = rigTest {
        device("clock/advance", "to" to DURING)
        val event = createAndJoin(startsAt = SHORT_START, endsAt = SHORT_END)
        foreignDevice("DEV-F", "FQ")
        device("clock/advance", "to" to AFTER_THE_END)
        // The clock ran out while this member was away: the sweep completed the event.
        device("backend/complete", "event" to event)

        os("app", "onSilentPush", event)

        neverWithin(what = "a deleted event is never left on its own") { !it.ready.configResolved }
        assertFalse(departed(event), "the backend heard of no leave")
    }

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "An event's photos are deleted once it is finished, and after 30 days at the latest",
        scenario = "A leave made offline still counts",
    )
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
