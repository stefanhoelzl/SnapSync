package app.snapsync.feature.membership

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.feature.support.ConfigWrites
import app.snapsync.mock.fixedClock
import app.snapsync.model.EventCompletionState
import app.snapsync.model.EventConfig
import app.snapsync.model.EventLookup
import app.snapsync.model.MemberCounts
import app.snapsync.model.TimestampText
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Whether another event's invite may leave the joined one without asking (capability `invite-link`; decision record
 * `changes/back-dated-events`, D8) — over the REAL config service, with the event read and this device's "holds
 * everything" answer played by the fields below.
 */
class QuietSwitchTest {

    private val joined = EventConfig(
        eventId = testEventId("E"),
        name = "Party",
        minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-06T12:00:00Z")),
        startsAt = EventStart(TimestampText.parse("2026-07-06T12:00:00Z")),
        endsAt = EventEnd(TimestampText.parse("2026-07-13T12:00:00Z")),
        maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-13T12:00:00Z")),
        deletesAt = DeletesAt(TimestampText.parse("2026-08-12T12:00:00Z")),
    )

    private fun found(waiting: Int?, completed: Boolean = false) = EventLookup.Found(
        joined.name,
        joined.startsAt,
        joined.endsAt,
        joined.deletesAt,
        EventCompletionState(completed, waiting?.let { MemberCounts(active = 3, settled = 3 - it, waiting = it) }),
    )

    /** One scenario: the clock, the membership, what the event read answers and whether this device holds everything. */
    private inner class World(now: String = AFTER_THE_END, config: EventConfig? = joined) {
        val service = ConfigWrites().service(config, fixedClock(Instant.parse(now)))
        var answer: EventLookup = found(waiting = 0)
        var holds = true
        var holdsThrows = false

        /** How many times the event was read; set to `-1` beforehand to make the read throw. */
        var reads = 0

        val rule = QuietSwitch(
            service,
            directory = {
                check(reads >= 0) { "the server could not be reached" }
                reads++
                answer
            },
            holdsEverything = {
                check(!holdsThrows) { "the union could not be read" }
                holds
            },
        )
    }

    @Test
    fun `an ended event everyone has shared and this device holds whole loses nothing`() = runTest {
        assertTrue(World().rule.losesNothing())
    }

    @Test
    fun `a member still sharing keeps the question`() = runTest {
        val world = World().apply { answer = found(waiting = 1) }
        assertFalse(world.rule.losesNothing())
    }

    @Test
    fun `a photo this device has not received yet keeps the question`() = runTest {
        val world = World().apply { holds = false }
        assertFalse(world.rule.losesNothing())
    }

    @Test
    fun `no answer from the server keeps the question`() = runTest {
        val counted = EventCompletionState(false, MemberCounts(3, 3, waiting = null)) // a server that counts no waiting
        val unserved = found(waiting = 0).copy(completion = counted)
        for (answer in listOf(EventLookup.Failed(), EventLookup.NotFound, found(waiting = null), unserved)) {
            val world = World().apply { this.answer = answer }
            assertFalse(world.rule.losesNothing(), "$answer")
        }
    }

    @Test
    fun `a read or a received check that throws keeps the question`() = runTest {
        val failing = World().apply { reads = -1 }
        assertFalse(failing.rule.losesNothing())
        val unsure = World().apply { holdsThrows = true }
        assertFalse(unsure.rule.losesNothing())
    }

    @Test
    fun `before the end nothing is read and the question is asked`() = runTest {
        val world = World(now = BEFORE_THE_END)
        assertFalse(world.rule.losesNothing())
        assertEquals(0, world.reads)
    }

    @Test
    fun `a deleted event loses nothing without a read`() = runTest {
        val world = World(now = BEFORE_THE_END, config = joined.copy(deleted = true)).apply { holds = false }
        assertTrue(world.rule.losesNothing())
        assertEquals(0, world.reads)
    }

    @Test
    fun `an event the read finds deleted loses nothing whatever this device holds`() = runTest {
        val world = World().apply {
            answer = found(waiting = 2, completed = true)
            holds = false
        }
        assertTrue(world.rule.losesNothing())
    }

    @Test
    fun `no membership is nothing to switch from`() = runTest {
        assertFalse(World(config = null).rule.losesNothing())
    }

    private companion object {
        const val AFTER_THE_END = "2026-07-15T12:00:00Z"
        const val BEFORE_THE_END = "2026-07-10T12:00:00Z"
    }
}
