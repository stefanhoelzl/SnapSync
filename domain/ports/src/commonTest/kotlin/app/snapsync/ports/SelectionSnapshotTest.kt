package app.snapsync.ports

import kotlin.test.Test
import kotlin.test.assertSame

/** A selection snapshot is the platform's payload handed through untouched: read by the core, never compared. */
class SelectionSnapshotTest {

    @Test
    fun a_selection_snapshot_hands_its_assets_through_untouched() {
        val assets = emptyList<GalleryAssetResources>()
        assertSame(assets, SelectionSnapshot(assets).assets)
    }
}
