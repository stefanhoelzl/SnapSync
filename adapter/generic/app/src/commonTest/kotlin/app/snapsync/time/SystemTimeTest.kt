package app.snapsync.time

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The production bindings of the `Clock` and `TimeZoneSource` ports. The ports exist so that nothing
 * else in the tree reads the system clock or zone directly — which makes these two objects the single
 * place where "now" and "here" enter the app, and the only place a fixed or stubbed value would be
 * invisible. Everything downstream (the cutoff formatting, the attestation staleness check, the event
 * window clamps) reads a port and is tested against an injected fake, so a production binding that
 * quietly answered a constant would pass every one of those tests.
 */
class SystemTimeTest {

    @Test
    fun `the production clock answers the system clock and moves forward`() {
        val before = kotlin.time.Clock.System.now()
        val first = SystemClock.now()
        val second = SystemClock.now()
        val after = kotlin.time.Clock.System.now()

        // Bracketed by the system clock rather than compared for equality: the point is that this
        // reads the real clock, not a constant or an offset epoch.
        assertTrue(first >= before && first <= after, "$first outside [$before, $after]")
        assertTrue(second >= first, "$second went backwards from $first")
        assertTrue(after - before < 1.minutes, "the bracket itself took ${after - before}")
    }

    @Test
    fun `the production zone source answers the device's current default zone`() {
        assertEquals(TimeZone.currentSystemDefault(), SystemTimeZone.current())
    }
}
