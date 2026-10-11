package app.snapsync.services.wake

import app.snapsync.mock.DatabasesMock
import app.snapsync.services.databases.sharedOver
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A background wake asks each event at most once an hour per check, the times kept in the shared database (decision record `changes/timely-background-receiving`, D4–D5). */
class EventChecksTest {

    private class FixedClock(var now: Instant)

    private val start = Instant.parse("2026-09-30T12:00:00Z")
    private val databases = DatabasesMock()
    private val clock = FixedClock(start)
    private val checks = EventChecks(sharedOver(databases.port()), now = { clock.now })

    @Test
    fun `an event never asked is due`() {
        assertTrue(checks.due(EventCheck.PHOTOS, EVENT))
    }

    @Test
    fun `an ask makes the next one wait an hour`() {
        checks.stamp(EventCheck.PHOTOS, EVENT)
        clock.now = start + 59.minutes
        assertFalse(checks.due(EventCheck.PHOTOS, EVENT))
        clock.now = start + 1.hours
        assertTrue(checks.due(EventCheck.PHOTOS, EVENT))
    }

    @Test
    fun `each check and each event keeps its own time`() {
        checks.stamp(EventCheck.PHOTOS, EVENT)
        assertTrue(checks.due(EventCheck.CLOSE, EVENT), "the close check is bounded on its own")
        assertTrue(checks.due(EventCheck.PHOTOS, OTHER), "and so is every other event")
    }

    @Test
    fun `a time in the future counts as due`() {
        checks.stamp(EventCheck.PHOTOS, EVENT)
        clock.now = start - 10.minutes // the clock was moved back
        assertTrue(checks.due(EventCheck.PHOTOS, EVENT))
    }

    @Test
    fun `an unreadable time counts as due`() {
        checks.stamp(EventCheck.PHOTOS, EVENT)
        databases.operator.lock()
        assertTrue(checks.due(EventCheck.PHOTOS, EVENT), "unreadable")
    }

    @Test
    fun `an ask that cannot be recorded leaves the event due`() {
        databases.operator.lock()
        checks.stamp(EventCheck.PHOTOS, EVENT)
        databases.operator.lock(false)
        assertTrue(checks.due(EventCheck.PHOTOS, EVENT), "the next wake asks again")
    }

    @Test
    fun `a clear that cannot reach the store raises nothing`() {
        databases.operator.lock()
        checks.clear(EVENT)
    }

    @Test
    fun `clearing forgets every check of the event and no other`() {
        checks.stamp(EventCheck.PHOTOS, EVENT)
        checks.stamp(EventCheck.CLOSE, EVENT)
        checks.stamp(EventCheck.PHOTOS, OTHER)
        checks.clear(EVENT)
        assertTrue(checks.due(EventCheck.PHOTOS, EVENT))
        assertTrue(checks.due(EventCheck.CLOSE, EVENT))
        assertFalse(checks.due(EventCheck.PHOTOS, OTHER))
    }

    private companion object {
        const val EVENT = "11111111-1111-4111-8111-111111111111"
        const val OTHER = "22222222-2222-4222-8222-222222222222"
    }
}
