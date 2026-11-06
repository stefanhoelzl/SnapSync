@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.data.foreground

import app.snapsync.domain.foreground.ForegroundReturn
import app.snapsync.domain.foreground.InFront
import app.snapsync.mock.ClockMock
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The app's comings and goings from the foreground: whether it is in front, each return, and what follows it. */
class AppForegroundTest {

    @Test
    fun `each return is counted with how long the app was away - none on a cold launch`() {
        val clock = ClockMock(Instant.parse("2026-07-21T09:00:00Z"))
        val foreground = AppForeground(clock.port())
        assertEquals(InFront.AWAY, foreground.inFront.value)
        assertEquals(ForegroundReturn.NONE, foreground.returns.value)

        foreground.entered()
        assertEquals(InFront.IN_FRONT, foreground.inFront.value)
        assertEquals(1, foreground.returns.value.count)
        assertNull(foreground.returns.value.awayFor, "a cold launch was never away")

        foreground.left()
        assertEquals(InFront.AWAY, foreground.inFront.value)
        clock.now = clock.now + 5.minutes
        foreground.entered()
        assertEquals(2, foreground.returns.value.count)
        assertEquals(5.minutes, foreground.returns.value.awayFor)
    }

    @Test
    fun `what follows the foreground is collected only while in front, afresh at each return`() = runTest {
        val foreground = AppForeground(ClockMock().port())
        var started = 0
        val seen = mutableListOf<Int>()
        foreground.whileInFront(flow { emit(++started) }).onEach { seen += it }.launchIn(backgroundScope)

        runCurrent()
        assertEquals(0, started, "nothing is read before the app is first in front")

        foreground.entered()
        runCurrent()
        assertEquals(listOf(1), seen)

        foreground.left()
        runCurrent()
        assertEquals(1, started, "nothing is read while away")

        foreground.entered()
        runCurrent()
        assertEquals(listOf(1, 2), seen, "each return reads afresh")
    }
}
