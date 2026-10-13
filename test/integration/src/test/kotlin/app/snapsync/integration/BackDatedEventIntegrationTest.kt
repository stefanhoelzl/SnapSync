package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.ui.state.SharingNote
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An event created after its end, over the REAL composition (decision record `changes/back-dated-events`): the host
 * creates it for a weekend already over and keeps it open for a week, a guest joins days later and shares, both have
 * everything, nothing ends the host's membership, and once the sweep deletes the photos the host is shown the event as
 * over. Beside it, a member on a build that predates the received report: it sees the event closed once everyone has
 * shared, leaves on its own as it always did, and holds nothing up.
 */
class BackDatedEventIntegrationTest {

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "An event's photos are deleted once it is finished, and after 30 days at the latest",
        scenario = "A back-dated event",
    )
    fun a_weekend_already_over_is_shared_until_it_is_deleted() = rigTest {
        extensionUploadsOnly()
        addPhoto("HOST-1", date = IN_THE_WEEKEND)
        // Created on 1 June for a weekend in May; the sheet is answered with a week.
        val event = create(startsAt = WEEKEND_START, endsAt = WEEKEND_END)
        join()
        refresh()
        awaitState { it.joined?.joinableUntil != null } // the joined screen says until when others can join
        uploadAll()

        // Day 2: a guest scans the invite, joins and shares the photo they took that weekend.
        device("clock/advance", "to" to DAY_TWO)
        foreignDevice(GUEST, "GUEST-1", final = true)
        downloadAll()
        refresh()
        assertEquals(setOf("HOST-1", "GUEST-1"), union(event).keys, "both members' photos are in the event")
        val shared = awaitState { it.joined?.sharingNote == SharingNote.EveryoneShared }
        assertEquals(2, shared.joined!!.members, "the joined screen counts both members")
        device("foreign-device/received", "device" to GUEST)

        // Everyone has everything, but the week the host chose is not over: the event is not finished.
        refresh()
        assertFalse(finished(event), "the open-until date has not passed")

        // After the week, with everyone done, the event is finished — and the host is still in it.
        device("clock/advance", "to" to AFTER_THE_WEEK)
        eventually(read = {
            refresh()
            finished(event)
        }) { it }
        neverWithin(what = "a finished event never ends a membership") { !it.ready.configResolved }

        // The sweep deletes the photos: the host is shown the event as over, with the guest's photo still in the
        // library.
        val library = libraryTotal()
        device("backend/complete", "event" to event)
        refresh()
        val over = awaitState { it.joined?.deleted == true }
        assertNull(over.joined!!.members, "a deleted event shows no member count")
        assertTrue(over.ready.configResolved, "the membership stays until the member leaves")
        assertEquals(library, libraryTotal(), "deletion touches no photo in the library")
    }

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "An event's photos are deleted once it is finished, and after 30 days at the latest",
    )
    fun a_member_on_an_older_build_leaves_once_everyone_shared_and_holds_nothing_up() = rigTest {
        extensionUploadsOnly()
        addPhoto("HOST-1", date = IN_THE_WEEKEND)
        val event = create(startsAt = WEEKEND_START, endsAt = WEEKEND_END)
        join()
        uploadAll()
        // A member whose build predates the received report: it shares, and never says what it received.
        foreignDevice(OLD_BUILD, "OLD-1", final = true)
        downloadAll()
        refresh()
        device("clock/advance", "to" to AFTER_THE_WEEK)

        // Once everyone still in the event has shared, the older build is told the event closed — what it waits for.
        val closedAt = eventually(read = {
            refresh()
            closedAt(event)
        }) { it != null }
        assertNotNull(closedAt)
        assertFalse(finished(event), "it never reports what it received, so the event waits on it")

        // It leaves on its own, as that build always has; nothing it never reported holds the event up any more.
        device("foreign-device/leave", "device" to OLD_BUILD)
        eventually(read = {
            refresh()
            finished(event)
        }) { it }
        assertTrue(state().ready.configResolved, "this member is still in the event")
    }

    private suspend fun Rig.finished(event: String): Boolean =
        deviceJson("backend/finished", "event" to event).getValue("finished").jsonPrimitive.boolean

    private suspend fun Rig.closedAt(event: String): String? =
        deviceJson("backend/event", "event" to event).getValue("closedAt").jsonPrimitive.contentOrNull

    private companion object {
        const val GUEST = "9e570000-0000-4000-8000-000000000001"
        const val OLD_BUILD = "01db0000-0000-4000-8000-000000000001"

        // A weekend in May — over by the time every test's clock starts on 1 June.
        const val WEEKEND_START = "2026-05-08T18:00:00"
        const val WEEKEND_END = "2026-05-10T20:00:00"
        const val IN_THE_WEEKEND = "2026-05-09T12:00:00Z"

        /** Two days after the event was created ([Rig.NOW]), inside the week the host kept it open. */
        const val DAY_TWO = "2026-06-03T12:00:00Z"

        /** Past the week the host chose (created 1 June 12:00, open for 7 days). */
        const val AFTER_THE_WEEK = "2026-06-09T12:00:00Z"
    }
}
