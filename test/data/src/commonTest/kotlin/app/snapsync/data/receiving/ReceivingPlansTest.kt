package app.snapsync.data.receiving

import app.snapsync.domain.receiving.UnionReadPlan
import app.snapsync.levers.WriteKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * What the store plans of a union page (capability `receiving-photos`): its open foreign photos recorded and their
 * transfers started, every foreign photo tagged as the event's, the position kept only when it moved, and a whole read
 * pruning what the union no longer lists — sparing a claimed import.
 */
class ReceivingPlansTest {
    private val device = Store()

    @Test
    fun `a first plan records the open foreign photos, tags every foreign one and starts their transfers`() = runTest {
        val own = photo(ref("MINE", ME))
        val a = photo(ref("A"))
        val b = photo(ref("B", BEN), captured(), resource("b-primary.heic"), resource("b-live.mov", role = "live"))
        assertNull(device.plans.position(EVENT).ok().position, "no read yet")

        val planned = device.planned(page(own, a, b, position = 7, othersDeclared = 3))

        assertEquals(2, planned.planned, "this device's own photo is not received")
        assertEquals(3, planned.pending)
        assertEquals(0, planned.pruned)
        assertEquals(3, device.download.started.size)
        val stored = device.plans.position(EVENT).ok()
        assertEquals(7L, stored.position)
        assertEquals(3L, stored.othersDeclared)
        assertEquals(listOf("PENDING", "PENDING"), listOf(device.state(a.ref), device.state(b.ref)))
        assertNull(device.state(own.ref))
        assertEquals(2, device.counts.counts(EVENT).ok().known)
        assertEquals(2, device.counts.counts(EVENT).ok().inFlight, "each sent to the platform")
    }

    @Test
    fun `a read that moved nothing writes no position, and a settled photo is tagged but never planned again`() =
        runTest {
            val a = photo(ref("A"))
            device.planned(page(a, position = 4))
            device.stagedAll(a)
            val claim = device.queue.claimNext(emptySet()).ok()!!
            device.queue.imported(claim, app.snapsync.testids.testAssetId("LOCAL-A"))

            val again = device.planned(page(a, position = 4), UnionReadPlan.Since(4))

            assertEquals(0, again.planned, "an imported photo is settled")
            assertEquals(0, again.pending)
            assertEquals(4L, device.plans.position(EVENT).ok().position)
        }

    @Test
    fun `a whole read drops what the union no longer lists, its staged bytes freed, sparing a claimed import`() =
        runTest {
            val gone = photo(ref("GONE"))
            val claimed = photo(ref("CLAIMED"))
            val kept = photo(ref("KEPT"))
            device.planned(page(gone, claimed, kept, position = 3))
            device.files.staged(TransferTag.parse(tagOf(gone))!!.stagedAt.value)
            device.queue.staged(gone.ref, gone.resources[0].key, TransferTag.parse(tagOf(gone))!!.stagedAt)
                .ok()
            device.stagedAll(claimed)
            assertEquals(claimed.ref, device.queue.claimNext(setOf(gone.ref)).ok()!!.ref)

            val whole = device.planned(page(kept, position = 5))

            assertEquals(1, whole.pruned, "the withdrawn photo's staged file")
            assertNull(device.state(gone.ref), "withdrawn: never settled, so a photo that comes back is planned again")
            assertEquals("PENDING", device.state(claimed.ref), "a claimed import is spared")
            assertEquals(false, device.files.has(TransferTag.parse(tagOf(gone))!!.stagedAt.value))
        }

    @Test
    fun `a delta read prunes nothing`() = runTest {
        val gone = photo(ref("GONE"))
        device.planned(page(gone, position = 3))

        val delta = device.planned(page(position = 5), UnionReadPlan.Since(3))

        assertEquals(0, delta.pruned)
        assertEquals("PENDING", device.state(gone.ref))
        assertEquals(0, device.planned(page(position = 5), UnionReadPlan.AlreadyRead).pruned)
    }

    @Test
    fun `a store that cannot be read or written plans nothing`() = runTest {
        val a = photo(ref("A"))
        device.counts.census()
        device.databases.fail(DOWNLOADS_DB_NAME, "downloadAsset", WriteKind.INSERT)
        assertIs<DownloadRead.Unavailable>(device.plan(page(a)))

        device.databases.fail(DOWNLOADS_DB_NAME, "downloadResource", WriteKind.DELETE)
        assertIs<DownloadRead.Unavailable>(device.plan(page(a)), "the whole read's prune failed")

        device.databases.fail(DOWNLOADS_DB_NAME, "downloadResource", WriteKind.UPDATE)
        assertIs<DownloadRead.Unavailable>(device.plan(page(a)), "the in-flight marker failed")

        device.databases.lock()
        assertIs<DownloadRead.Unavailable>(device.plans.position(EVENT))
        assertIs<DownloadRead.Unavailable>(device.plan(page(a)))
    }

    private suspend fun Store.plan(page: app.snapsync.domain.receiving.UnionPage) =
        plans.plan(EVENT, page, WHOLE, ME, StoredPosition(null, null))

    private fun tagOf(photo: app.snapsync.domain.receiving.UnionPhoto): String =
        TransferTag(photo.ref, photo.resources[0].key, app.snapsync.domain.receiving.StagingFolder.of(EVENT.value)).text
}
