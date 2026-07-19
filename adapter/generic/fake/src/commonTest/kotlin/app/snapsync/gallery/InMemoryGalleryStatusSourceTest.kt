package app.snapsync.gallery

import app.snapsync.fake.InMemoryGalleryStatusSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class InMemoryGalleryStatusSourceTest {

    @Test
    fun seeds_a_real_value_available_synchronously() {
        val source = InMemoryGalleryStatusSource(initial = 5)
        assertEquals(5, source.size.value)
    }

    @Test
    fun defaults_to_zero() {
        assertEquals(0, InMemoryGalleryStatusSource().size.value)
    }

    @Test
    fun writing_the_owned_cell_re_emits_the_new_count() = runTest {
        // The honest fake exposes only the port; whoever constructs it owns the cell (fake-honesty gate).
        val cell = MutableStateFlow(1)
        val source = InMemoryGalleryStatusSource(cell)
        cell.value = 47
        assertEquals(47, source.size.first())
        assertEquals(47, source.size.value)
    }
}
