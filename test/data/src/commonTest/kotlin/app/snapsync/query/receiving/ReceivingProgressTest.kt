package app.snapsync.query.receiving

import app.snapsync.application.EntryPointCall
import app.snapsync.application.receiving.Listed
import app.snapsync.application.receiving.PlanReceiving
import app.snapsync.application.receiving.Receiver
import app.snapsync.application.receiving.receiving
import app.snapsync.data.membership.active
import app.snapsync.domain.membership.ConfirmedDeletion
import app.snapsync.domain.membership.MintsConfirmedDeletion
import app.snapsync.domain.receiving.UnionTrigger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The joined screen's download progress (capability `sync-status`): unread until the first refresh, then the joined
 * event's counts — with what the others declared beyond them awaited while the membership receives — and a read that
 * fails keeps the last good value.
 */
@OptIn(EntryPointCall::class, MintsConfirmedDeletion::class)
class ReceivingProgressTest {

    private val r = Receiver()
    private val progress = ReceivingProgress(r.counts, r.device.membership)

    @Test
    fun `nothing is read before the first refresh, and nothing joined reads nothing`() = runTest {
        assertEquals(DownloadProgress.UNREAD, progress.progress.value)
        assertFalse(progress.progress.value.read)

        progress.refresh()
        assertEquals(DownloadProgress.UNREAD, progress.progress.value, "not joined")
    }

    @Test
    fun `the joined event's counts, with the others' declared photos awaited`() = runTest {
        val membership = r.joined()
        r.listed += Listed("A")
        r.position = 1
        r.declared = 3
        PlanReceiving(r.plans, r.union, r.device.identity, r.checks, r.device.wallClock)(
            receiving(membership),
            UnionTrigger.FOREGROUND,
        )

        progress.refresh()

        assertEquals(DownloadProgress(downloaded = 0, total = 1, inFlight = 1, awaited = 2), progress.progress.value)
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

        progress.refresh()

        assertEquals(DownloadProgress(downloaded = 0, total = 0), progress.progress.value)
    }

    @Test
    fun `a read that fails keeps the last good value`() = runTest {
        r.joined()
        progress.refresh()
        val good = progress.progress.value

        r.unreadable()
        progress.refresh()
        assertEquals(good, progress.progress.value)

        val locked = Receiver().apply { device.databases.lock() }
        ReceivingProgress(locked.counts, locked.device.membership).let {
            it.refresh()
            assertEquals(DownloadProgress.UNREAD, it.progress.value, "the membership cannot be read")
        }
    }
}
