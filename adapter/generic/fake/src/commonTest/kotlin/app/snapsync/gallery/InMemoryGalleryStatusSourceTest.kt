package app.snapsync.gallery

import app.snapsync.fake.InMemoryGalleryStatusSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InMemoryGalleryStatusSourceTest {

    @Test
    fun seeds_a_real_value_available_synchronously() {
        val source = InMemoryGalleryStatusSource(initial = 5)
        assertEquals(5, source.size.value)
    }

    @Test
    fun defaults_to_not_counted() {
        // NOT `0`. A fake that seeded a counted zero made the device's cold-launch state unreachable
        // from any test, which is how a status projection that settled over unread inputs shipped.
        assertNull(InMemoryGalleryStatusSource().size.value)
    }

    @Test
    fun a_counted_zero_is_distinct_from_not_counted() {
        assertEquals(0, InMemoryGalleryStatusSource(initial = 0).size.value)
        assertNull(InMemoryGalleryStatusSource(initial = null).size.value)
    }

    @Test
    fun writing_the_owned_cell_re_emits_the_new_count() = runTest {
        // The honest fake exposes only the port; whoever constructs it owns the cell (fake-honesty gate).
        val cell = MutableStateFlow<Int?>(1)
        val source = InMemoryGalleryStatusSource(cell)
        cell.value = 47
        assertEquals(47, source.size.first())
        assertEquals(47, source.size.value)
    }
}
