package app.snapsync.integration

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **A background wake asks the event at most once an hour** (capability `receiving-photos`, "New photos are announced
 * by a silent wake, and never only by it"; decision record `changes/timely-background-receiving`, D4–D5), counted at
 * the backend: a heartbeat reads the union when none was read within the hour, and — after the event's end — its
 * state the same way; a push reads both whenever it comes, and resets the hour. A join and a reconfigure read the union
 * once each.
 */
class EventChecksIntegrationTest {

    @Test
    fun heartbeats_read_the_union_at_most_once_an_hour() = rigTest {
        val event = createAndJoin()
        device("clock/advance", "to" to T0)
        heartbeat() // long after the join's own reads: this wake asks
        val first = unionReads(event)

        device("clock/advance", "to" to T0_PLUS_10_MIN)
        heartbeat()
        assertEquals(first, unionReads(event), "ten minutes later it does not")

        device("clock/advance", "to" to T0_PLUS_1_H)
        heartbeat()
        assertEquals(first + 1, unionReads(event), "an hour later it asks again")
    }

    @Test
    fun a_push_reads_the_union_whenever_it_comes_and_resets_the_hour() = rigTest {
        val event = createAndJoin()
        device("clock/advance", "to" to T0)
        heartbeat()
        val before = unionReads(event)

        device("clock/advance", "to" to T0_PLUS_10_MIN)
        os("app", "onSilentPush", event)
        eventually(read = { unionReads(event) }) { it == before + 1 }

        device("clock/advance", "to" to T0_PLUS_20_MIN)
        heartbeat()
        assertEquals(before + 1, unionReads(event), "the wake after a push reads nothing")
    }

    @Test
    fun after_the_end_heartbeats_read_the_event_at_most_once_an_hour_and_a_push_always() = rigTest {
        device("clock/advance", "to" to DURING)
        val event = createAndJoin(startsAt = SHORT_START, endsAt = SHORT_END)
        // A second member that never settles keeps the event open, so this one stays joined to be woken.
        foreignDevice("DEV-F", "FQ")
        device("clock/advance", "to" to AFTER_THE_END)
        heartbeat()
        val first = eventReads(event)

        device("clock/advance", "to" to AFTER_THE_END_PLUS_10_MIN)
        heartbeat()
        assertEquals(first, eventReads(event), "a wake within the hour reads no state")

        os("app", "onSilentPush", event)
        eventually<Int>(read = { eventReads(event) }) { it == first + 1 }
    }

    @Test
    fun a_join_reads_the_union_once() = rigTest {
        val event = createAndJoin()
        assertEquals(1, unionReads(event), "the join read it before the membership was saved")
        settleDownloads()
        assertEquals(1, unionReads(event), "nothing after the save reads it again: the reconcile, the arm, the gather")
    }

    @Test
    fun a_reconfigure_reads_the_union_once() = rigTest {
        val event = createAndJoin("saveToAlbum" to "false")
        settleDownloads()
        val before = unionReads(event)

        user("reconfigure", "saveToAlbum" to "true")
        awaitState { it.joined?.membership?.saveToAlbum == true }
        settleDownloads()

        assertEquals(before + 1, unionReads(event), "the reconcile reads it; the arm and the gather do not")
    }

    private suspend fun Rig.heartbeat() {
        os("app", "onBackgroundTask", HEARTBEAT_TASK)
    }

    private suspend fun Rig.unionReads(event: String): Int =
        deviceJson("backend/reads", "event" to event).getValue("union").jsonPrimitive.int

    private suspend fun Rig.eventReads(event: String): Int =
        deviceJson("backend/reads", "event" to event).getValue("event-details").jsonPrimitive.int

    private companion object {
        const val T0 = "2026-06-10T12:00:00Z"
        const val T0_PLUS_10_MIN = "2026-06-10T12:10:00Z"
        const val T0_PLUS_20_MIN = "2026-06-10T12:20:00Z"
        const val T0_PLUS_1_H = "2026-06-10T13:00:00Z"

        const val SHORT_START = "2026-05-15T00:00:00"
        const val SHORT_END = "2026-05-20T00:00:00"
        const val DURING = "2026-05-16T12:00:00Z"
        const val AFTER_THE_END = "2026-05-22T00:00:00Z"
        const val AFTER_THE_END_PLUS_10_MIN = "2026-05-22T00:10:00Z"
    }
}
