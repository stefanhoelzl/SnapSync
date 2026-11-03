package app.snapsync.data.receiving

import app.snapsync.domain.receiving.StagedPath
import app.snapsync.domain.receiving.TransferSlots
import app.snapsync.levers.WriteKind
import app.snapsync.testids.testAssetId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A photo on its way into the library (capability `receiving-photos`): a staging recorded — or its file discarded when
 * no row takes it — the next fully staged photo claimed once, and what its import came to settled onto its row.
 */
class ImportQueueTest {
    private val device = Store()

    @Test
    fun `a staging a row takes is recorded, and one no row takes has its file discarded`() = runTest {
        val a = photo(ref("A"))
        device.planned(page(a))
        val at = StagedPath("download-staging/x/a")
        device.files.staged(at.value)
        assertSame(StageRecord.Recorded, device.queue.staged(a.ref, a.resources[0].key, at).ok())
        assertTrue(device.files.has(at.value))

        val stray = StagedPath("download-staging/x/stray")
        device.files.staged(stray.value)
        assertSame(StageRecord.NoRow, device.queue.staged(ref("NONE"), "none", stray).ok())
        assertEquals(false, device.files.has(stray.value), "nothing names it: it would sit unreclaimable")

        device.databases.lock()
        assertIs<DownloadRead.Unavailable>(device.queue.staged(a.ref, a.resources[0].key, at))
    }

    @Test
    fun `the next fully staged photo is claimed once, with what its import needs`() = runTest {
        val a = photo(ref("A"), captured(), resource("a-primary.heic"), resource("a-live.mov", role = "live"))
        val b = photo(ref("B"))
        val unstaged = photo(ref("C"))
        device.planned(page(a, b, unstaged))
        device.stagedAll(a)
        device.stagedAll(b)

        val first = device.queue.claimNext(emptySet()).ok()!!
        assertEquals(a.ref, first.ref)
        assertEquals(captured(), first.captured)
        assertEquals(setOf("a-primary.heic", "a-live.mov"), first.resources.map { it.resourceKey }.toSet())
        assertEquals(b.ref, device.queue.claimNext(emptySet()).ok()!!.ref, "a claimed one is never claimed twice")
        assertNull(device.queue.claimNext(emptySet()).ok(), "C is not fully staged")

        device.queue.deferred(first)
        assertNull(device.queue.claimNext(setOf(a.ref)).ok(), "skipped in this pass")
        assertEquals(a.ref, device.queue.claimNext(emptySet()).ok()!!.ref, "released: a later pass imports it again")

        device.databases.lock()
        assertIs<DownloadRead.Unavailable>(device.queue.claimNext(emptySet()))
    }

    @Test
    fun `an import settles its row onto the created asset and releases the claim`() = runTest {
        val a = photo(ref("A"))
        device.planned(page(a))
        device.stagedAll(a)
        val claim = device.queue.claimNext(emptySet()).ok()!!

        val imported = device.queue.imported(claim, testAssetId("LOCAL-A")).ok()
        assertEquals(a.ref, imported.ref)
        assertEquals(testAssetId("LOCAL-A"), imported.created)
        assertEquals("IMPORTED", device.state(a.ref))
        assertEquals("LOCAL-A", device.marker(a.ref))

        device.databases.fail(DOWNLOADS_DB_NAME, "downloadAsset", WriteKind.UPDATE)
        assertIs<DownloadRead.Unavailable>(device.queue.imported(claim, testAssetId("LOCAL-A")))
    }

    @Test
    fun `an import the library refused for good settles the row as unimportable, unless it moved on`() = runTest {
        val a = photo(ref("A"))
        val b = photo(ref("B"))
        device.planned(page(a, b))
        device.stagedAll(a)
        device.stagedAll(b)
        val claimA = device.queue.claimNext(emptySet()).ok()!!
        val claimB = device.queue.claimNext(emptySet()).ok()!!

        assertEquals(true, device.queue.unimportable(claimA).ok())
        assertEquals("UNIMPORTABLE", device.state(a.ref))
        assertEquals(emptyList(), device.strings("SELECT resourceKey FROM downloadResource WHERE sourceAssetId = 'A'"))

        device.markers.placeholder(ImportCorrelation.of(b.ref), app.snapsync.ports.PlatformAssetId("LOCAL-B"))
        assertEquals(false, device.queue.unimportable(claimB).ok(), "an asset was created for it meanwhile")
        assertEquals("PENDING", device.state(b.ref))
    }

    @Test
    fun `a transfer that ends frees its slot for the next queued one`() = runTest {
        val photos = (1..TransferSlots.MAX + 1).map { photo(ref("P$it")) }
        device.planned(page(*photos.toTypedArray()))
        assertEquals(TransferSlots.MAX, device.download.started.size, "the window is full")

        device.queue.transferEnded(device.download.started.first())

        assertEquals(TransferSlots.MAX + 1, device.download.started.size)
    }

    @Test
    fun `a staging is recorded or not as a row took it`() {
        assertSame(StageRecord.Recorded, StageRecord.of(true))
        assertSame(StageRecord.NoRow, StageRecord.of(false))
    }
}
