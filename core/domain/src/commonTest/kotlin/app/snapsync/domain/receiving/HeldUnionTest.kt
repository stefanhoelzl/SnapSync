package app.snapsync.domain.receiving

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A whole union as a holding check counts it: refs and a position, or no count of a page that is not one. */
class HeldUnionTest {

    @Test
    fun a_page_of_refs_at_a_position_is_counted_and_owes_this_device_the_others_photos() {
        val held = HeldUnion.of(7, listOf(ref("A", ME), ref("B"), ref("C")))!!

        assertEquals(7L, held.position)
        assertEquals(listOf(ref("B"), ref("C")), held.foreignTo(ME))
    }

    @Test
    fun a_page_with_no_position_or_an_id_that_is_no_id_is_no_count() {
        assertNull(HeldUnion.of(null, listOf(ref("A"))))
        assertNull(HeldUnion.of(7, listOf(ref("A"), null)))
    }
}
