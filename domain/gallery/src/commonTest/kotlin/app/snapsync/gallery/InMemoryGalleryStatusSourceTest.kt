package app.snapsync.gallery

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
    fun set_re_emits_the_new_count() = runTest {
        val source = InMemoryGalleryStatusSource(initial = 1)
        source.set(47)
        assertEquals(47, source.size.first())
        assertEquals(47, source.size.value)
    }
}
