package app.snapsync.gallery

import app.snapsync.fake.InMemoryGalleryStatusSource
import app.snapsync.model.AssetId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InMemoryGalleryStatusSourceTest {

    @Test
    fun seeds_a_real_value_available_synchronously() {
        val source = InMemoryGalleryStatusSource(initial = setOf(AssetId("a"), AssetId("b"), AssetId("c"), AssetId("d"), AssetId("e")))
        assertEquals(setOf(AssetId("a"), AssetId("b"), AssetId("c"), AssetId("d"), AssetId("e")), source.admitted.value)
    }

    @Test
    fun defaults_to_not_counted() {
        // NOT `0`. A fake that seeded a counted zero made the device's cold-launch state unreachable
        // from any test, which is how a status projection that settled over unread inputs shipped.
        assertNull(InMemoryGalleryStatusSource().admitted.value)
    }

    @Test
    fun a_counted_zero_is_distinct_from_not_counted() {
        assertEquals(emptySet(), InMemoryGalleryStatusSource(initial = emptySet()).admitted.value)
        assertNull(InMemoryGalleryStatusSource(initial = null).admitted.value)
    }

    @Test
    fun writing_the_owned_cell_re_emits_the_new_set() = runTest {
        // The honest fake exposes only the port; whoever constructs it owns the cell (fake-honesty gate).
        val cell = MutableStateFlow<Set<AssetId>?>(setOf(AssetId("a")))
        val source = InMemoryGalleryStatusSource(cell)
        cell.value = setOf(AssetId("a"), AssetId("b"))
        assertEquals(setOf(AssetId("a"), AssetId("b")), source.admitted.first())
        assertEquals(setOf(AssetId("a"), AssetId("b")), source.admitted.value)
    }
}
