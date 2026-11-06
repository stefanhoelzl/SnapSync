package app.snapsync.domain.sharing

import app.snapsync.domain.asset.AssetId
import app.snapsync.testids.testAssetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** How far the member's own photos are shared, counted over the admitted set (capability `sync-status`). */
class SharingProgressTest {

    private fun ids(vararg names: String): Set<AssetId> = names.mapTo(mutableSetOf(), ::testAssetId)

    @Test
    fun `shared counts the admitted photos whose rows are done`() {
        assertEquals(
            SharingProgress(shared = 2, total = 3, handedOver = 1),
            SharingProgress.of(ids("a", "b", "c"), done = ids("a", "b"), underWay = ids("c")),
        )
    }

    @Test
    fun `under way is clamped to what is left`() {
        // A deleted photo whose row lingers can over-count: what is under way never exceeds what is left to share.
        assertEquals(1, SharingProgress.of(ids("a", "b"), done = ids("a"), underWay = ids("a", "b")).handedOver)
    }

    @Test
    fun `rows outside the admitted set count for nothing`() {
        // A raised cutoff leaves done rows of photos it no longer admits: they must not mask an in-window photo left.
        assertEquals(
            SharingProgress(shared = 0, total = 1, handedOver = 0),
            SharingProgress.of(ids("in"), done = ids("old-1", "old-2"), underWay = emptySet()),
        )
    }

    @Test
    fun `an admitted photo with no row is neither shared nor under way`() {
        assertEquals(
            SharingProgress(shared = 0, total = 1, handedOver = 0),
            SharingProgress.of(ids("new"), emptySet(), emptySet()),
        )
    }

    @Test
    fun `progress read is told apart from nothing read yet`() {
        val counted = SharingProgress(shared = 1, total = 2, handedOver = 0)
        assertEquals(counted, assertIs<SharingProgressRead.Read>(SharingProgressRead.Read(counted)).progress)
        assertIs<SharingProgressRead.NotYetRead>(SharingProgressRead.NotYetRead)
    }

    @Test
    fun `nothing admitted is a counted zero`() {
        assertEquals(
            SharingProgress(shared = 0, total = 0, handedOver = 0),
            SharingProgress.of(emptySet(), ids("a"), ids("b")),
        )
    }
}
