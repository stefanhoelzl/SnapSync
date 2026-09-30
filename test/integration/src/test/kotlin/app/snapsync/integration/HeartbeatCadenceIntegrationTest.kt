package app.snapsync.integration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * **The heartbeat stays pending while the device is joined, busy or idle** (capability `receiving-photos`, "New photos
 * are announced by a silent wake, and never only by it"; decision record `changes/timely-background-receiving`, D1),
 * read off the operating system's record after a heartbeat wake. The JVM host carries no OS uploader, so its
 * full-grant sharer is the one that notices its own photos only by looking — iOS below 26.1; the confirmed-uploader row
 * is `TailRunnerTest`'s and `HeartbeatCadenceTest`'s.
 */
class HeartbeatCadenceIntegrationTest {

    @Test
    fun a_full_grant_sharer_keeps_looking_until_the_end_and_then_idles() = rigTest {
        permission("GRANTED")
        createAndJoin("direction" to "upload", startsAt = SHORT_START, endsAt = SHORT_END)
        heartbeat()
        assertEquals(BUSY, osRecord().pendingCadence, "its own new photos are noticed only by looking")

        device("clock/advance", "to" to AFTER_THE_END)
        heartbeat()
        val after = osRecord()
        assertEquals(IDLE, after.pendingCadence, "after the end nothing new can enter the event")
        assertEquals(3600L, after.pendingEarliestSeconds, "and it looks in hourly")
    }

    @Test
    fun a_member_who_only_receives_idles_instead_of_keeping_no_wake() = rigTest {
        createAndJoin("direction" to "download")
        heartbeat()
        assertEquals(IDLE, osRecord().pendingCadence)
    }

    @Test
    fun a_sharer_whose_uploads_are_held_back_idles() = rigTest {
        createAndJoin()
        permission("DENIED")
        heartbeat()
        assertEquals(IDLE, osRecord().pendingCadence, "it uploads nothing, but still looks for others' photos")
    }

    @Test
    fun a_partial_grant_idles_because_a_new_camera_photo_never_joins_the_selection() = rigTest {
        permission("LIMITED")
        createAndJoin()
        heartbeat()
        assertEquals(IDLE, osRecord().pendingCadence)
    }

    @Test
    fun leaving_withdraws_the_heartbeat() = rigTest {
        createAndJoin()
        heartbeat()
        leave()
        eventually(read = { osRecord().pendingCadence }) { it == null }
        assertNull(osRecord().pendingEarliestSeconds)
    }

    /** The operating system runs the heartbeat, and the app re-arms it at the end of its tail. */
    private suspend fun Rig.heartbeat() {
        os("app", "onBackgroundTask", HEARTBEAT_TASK)
    }

    private companion object {
        const val BUSY = "busy"
        const val IDLE = "idle"
        const val SHORT_START = "2026-05-15T00:00:00"
        const val SHORT_END = "2026-05-20T00:00:00"

        /** After [SHORT_END], and long before the event's deadline. */
        const val AFTER_THE_END = "2026-05-22T00:00:00Z"
    }
}
