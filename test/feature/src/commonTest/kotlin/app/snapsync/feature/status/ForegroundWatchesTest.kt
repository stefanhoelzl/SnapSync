@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.feature.status

import app.snapsync.mock.ClockMock
import app.snapsync.mock.NetworkMock
import app.snapsync.model.NetworkAccess
import app.snapsync.services.foreground.AppForeground
import app.snapsync.services.network.NetworkReadings
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * What follows the app only while its screen is visible (capability `sync-status`): the counts poll and the network
 * watch start together at the foreground and stop together at the background, and a wake's steps refresh the counts
 * only while a person can see them.
 */
class ForegroundWatchesTest {

    @Test
    fun `the poll and the watch start together and stop together`() = runTest {
        var polls = 0
        val network = NetworkMock()
        val watch = NetworkWatch(backgroundScope, NetworkReadings(network.port()))
        watch.stop() // a stop before any start stops nothing
        val watches = ForegroundWatches(StatusCountsPoller(backgroundScope, { polls++ }, cadence = 2.seconds), watch)

        watches.start()
        runCurrent()
        network.access = NetworkAccess.Offline
        advanceTimeBy(NetworkWatch.DEFAULT_GRACE + 1.seconds)
        assertEquals(NetworkAccess.Offline, watch.access.value, "the watch runs at the default grace")
        assertEquals(2, polls, "the poll runs beside it")

        watches.stop()
        advanceTimeBy(10.seconds)
        assertEquals(2, polls, "and both stop together")
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
