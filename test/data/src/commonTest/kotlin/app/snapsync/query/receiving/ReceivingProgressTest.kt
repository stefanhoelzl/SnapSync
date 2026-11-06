@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.query.receiving

import app.snapsync.application.EntryPointCall
import app.snapsync.application.receiving.Listed
import app.snapsync.application.receiving.PlanReceiving
import app.snapsync.application.receiving.Receiver
import app.snapsync.application.receiving.receiving
import app.snapsync.data.foreground.AppForeground
import app.snapsync.data.membership.active
import app.snapsync.domain.membership.ConfirmedDeletion
import app.snapsync.domain.membership.MintsConfirmedDeletion
import app.snapsync.domain.receiving.UnionTrigger
import app.snapsync.mock.ClockMock
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The joined screen's download progress (capability `sync-status`): unread until the first read, then the joined
 * event's counts — with what the others declared beyond them awaited while the membership receives — re-read whenever
 * the download store changes, only while the app is in front; and a read that fails keeps the last good value.
 */
@OptIn(EntryPointCall::class, MintsConfirmedDeletion::class)
class ReceivingProgressTest {

    private val r = Receiver()
    private val foreground = AppForeground(ClockMock().port())

    /** The progress over [receiver], followed as a screen follows it, the app in front. */
    private fun TestScope.followed(receiver: Receiver = r): ReceivingProgress {
        val progress = ReceivingProgress(
            receiver.counts,
            receiver.device.membership,
            foreground,
            receiver.file.changes,
            backgroundScope,
        )
        progress.progress.launchIn(backgroundScope)
        foreground.entered()
        runCurrent()
        return progress
    }

    private suspend fun planned(declared: Int, listed: List<String> = emptyList()) {
        val membership = r.joined()
        r.listed += listed.map(::Listed)
        r.position = listed.size.toLong()
        r.declared = declared.toLong()
        PlanReceiving(r.plans, r.union, r.device.identity, r.checks, r.device.wallClock)(
            receiving(membership),
            UnionTrigger.FOREGROUND,
        )
    }

    @Test
    fun `nothing is read before a screen follows it, and nothing joined reads nothing`() = runTest {
        val unfollowed = ReceivingProgress(r.counts, r.device.membership, foreground, r.file.changes, backgroundScope)
        foreground.entered()
        runCurrent()
        assertEquals(DownloadProgress.UNREAD, unfollowed.progress.value)
        assertFalse(unfollowed.progress.value.read)

        assertEquals(DownloadProgress.UNREAD, followed().progress.value, "not joined")
    }

    @Test
    fun `the joined event's counts, with the others' declared photos awaited`() = runTest {
        planned(declared = 3, listed = listOf("A"))

        assertEquals(
            DownloadProgress(downloaded = 0, total = 1, inFlight = 1, awaited = 2),
            followed().progress.value,
        )
    }

    @Test
    fun `a change to the download store is read again at once`() = runTest {
        r.joined()
        val progress = followed()
        assertEquals(DownloadProgress(downloaded = 0, total = 0), progress.progress.value)

        planned(declared = 1, listed = listOf("A"))
        runCurrent()

        assertEquals(DownloadProgress(downloaded = 0, total = 1, inFlight = 1), progress.progress.value)
    }

    @Test
    fun `nothing is read while the app is away, and its return reads afresh`() = runTest {
        r.joined()
        val progress = followed()
        foreground.left()
        runCurrent()

        planned(declared = 1, listed = listOf("A"))
        runCurrent()
        assertEquals(DownloadProgress(downloaded = 0, total = 0), progress.progress.value, "away: not read")

        foreground.entered()
        runCurrent()
        assertEquals(DownloadProgress(downloaded = 0, total = 1, inFlight = 1), progress.progress.value)
    }

    @Test
    fun `a deleted event awaits nothing more`() = runTest {
        val membership = r.joined()
        r.declared = 3
        PlanReceiving(r.plans, r.union, r.device.identity, r.checks, r.device.wallClock)(
            receiving(membership),
            UnionTrigger.FOREGROUND,
        )
        r.device.membership.markDeleted(active(), ConfirmedDeletion(membership.event.id))

        assertEquals(DownloadProgress(downloaded = 0, total = 0), followed().progress.value)
    }

    @Test
    fun `a read that fails keeps the last good value`() = runTest {
        r.joined()
        val progress = followed()
        val good = progress.progress.value

        r.unreadable()
        r.file.changes.announce()
        runCurrent()
        assertEquals(good, progress.progress.value)

        val locked = Receiver().apply { device.databases.lock() }
        assertEquals(DownloadProgress.UNREAD, followed(locked).progress.value, "the membership cannot be read")
    }
}
