package app.snapsync.domain.sharing

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.upload.AssetProgress
import app.snapsync.testids.testAssetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** How far the member's own photos are shared, counted over the admitted set (capability `sync-status`). */
class SharingProgressTest {

    private fun ids(vararg names: String): Set<AssetId> = names.mapTo(mutableSetOf(), ::testAssetId)

    private fun photos(vararg each: Pair<String, AssetProgress>): Map<AssetId, AssetProgress> =
        each.associate { (name, progress) -> testAssetId(name) to progress }

    @Test
    fun `shared counts the admitted photos done, handed over those a job carries, never a waiting one`() {
        assertEquals(
            SharingProgress(shared = 2, total = 4, handedOver = 1),
            SharingProgress.of(
                ids("a", "b", "c", "d"),
                photos(
                    "a" to AssetProgress.DONE,
                    "b" to AssetProgress.DONE,
                    "c" to AssetProgress.HANDED_OVER,
                    "d" to AssetProgress.WAITING,
                ),
            ),
        )
    }

    @Test
    fun `rows outside the admitted set count for nothing`() {
        // A raised cutoff leaves done rows of photos it no longer admits: they must not mask an in-window photo left.
        assertEquals(
            SharingProgress(shared = 0, total = 1, handedOver = 0),
            SharingProgress.of(ids("in"), photos("old-1" to AssetProgress.DONE, "old-2" to AssetProgress.HANDED_OVER)),
        )
    }

    @Test
    fun `an admitted photo with no row is neither shared nor under way`() {
        assertEquals(SharingProgress(shared = 0, total = 1, handedOver = 0), SharingProgress.of(ids("new"), emptyMap()))
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
            SharingProgress.of(emptySet(), photos("a" to AssetProgress.DONE, "b" to AssetProgress.HANDED_OVER)),
        )
    }
}
