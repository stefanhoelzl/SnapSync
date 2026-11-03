@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.feature.status

import app.snapsync.mock.ClockMock
import app.snapsync.services.foreground.AppForeground
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * What follows the app only while its screen is visible (capability `sync-status`): the counts poll starts at the
 * foreground and stops at the background, and a wake's steps refresh the counts only while a person can see them.
 */
class ForegroundCountsTest {

    @Test
    fun `the poll runs between its start and its stop`() = runTest {
        var polls = 0
        val poller = StatusCountsPoller(backgroundScope, { polls++ }, cadence = 2.seconds)

        poller.start()
        runCurrent()
        advanceTimeBy(6.seconds)
        assertEquals(2, polls, "every cadence while started")

        poller.stop()
        advanceTimeBy(10.seconds)
        assertEquals(2, polls, "and nothing once stopped")
    }

    @Test
    fun `a wake refreshes the counts only while the app is in front`() = runTest {
        var reads = 0
        val foreground = AppForeground(ClockMock().port(), onEntered = {})
        val counts = ForegroundCounts(
            foreground,
            ReadingLedgerCountsSource {
                reads++
                LedgerCounts.UNREAD
            },
        )
        counts.refresh()
        assertEquals(0, reads, "in the background nobody sees them")
        foreground.entered()
        counts.refresh()
        assertEquals(1, reads)
    }
}
