package app.snapsync.domain.receiving

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnionTest {

    @Test
    fun foreign_photos_are_the_others_and_open_ones_are_not_settled_here() {
        val mine = photo("M", device = ME)
        val settled = photo("S")
        val open = photo("O")
        val foreign = ForeignPhotos.of(listOf(mine, settled, open), ME, settled = setOf(settled.ref))
        assertEquals(listOf(settled, open), foreign.all, "this device's own photos are in its library already")
        assertEquals(listOf(open), foreign.open)
        assertEquals(listOf(settled.ref, open.ref), foreign.refs, "every foreign ref, settled ones included")
    }

    @Test
    fun a_read_with_no_stored_position_reads_the_whole_union() {
        assertEquals(UnionReadPlan.Whole, UnionReadPlan.of(whole = false, stored = null, announced = 3))
        assertEquals(UnionReadPlan.Whole, UnionReadPlan.of(whole = true, stored = null, announced = null))
    }

    @Test
    fun a_delta_trigger_reads_from_the_stored_position_and_a_whole_one_everything() {
        assertEquals(
            5,
            assertIs<UnionReadPlan.Since>(UnionReadPlan.of(whole = false, stored = 5, announced = null)).from,
        )
        assertEquals(5, assertIs<UnionReadPlan.Since>(UnionReadPlan.of(whole = false, stored = 5, announced = 9)).from)
        assertEquals(UnionReadPlan.Whole, UnionReadPlan.of(whole = true, stored = 5, announced = null))
    }

    @Test
    fun an_announced_position_already_read_reads_nothing_whatever_the_trigger() {
        assertEquals(UnionReadPlan.AlreadyRead, UnionReadPlan.of(whole = false, stored = 5, announced = 5))
        assertEquals(UnionReadPlan.AlreadyRead, UnionReadPlan.of(whole = true, stored = 6, announced = 5))
    }

    @Test
    fun a_read_moves_the_store_only_when_its_position_or_the_others_declaration_moved() {
        val page = UnionPage(emptyList(), position = 4, othersDeclared = 7)
        assertFalse(page.moved(stored = 4, storedOthers = 7))
        assertTrue(page.moved(stored = 3, storedOthers = 7))
        assertTrue(page.moved(stored = 4, storedOthers = 6))
        assertFalse(
            UnionPage(emptyList(), 0, null).moved(stored = null, storedOthers = null),
            "no position is position 0",
        )
    }

    @Test
    fun the_others_declaration_is_the_total_less_this_devices_and_unknown_awaits_nothing() {
        assertEquals(6L, OthersDeclared.of(total = 10, own = 4))
        assertEquals(0L, OthersDeclared.of(total = 3, own = 4), "a publish crossing the read is clamped")
        assertNull(OthersDeclared.of(total = null, own = 4))
        assertNull(OthersDeclared.of(total = 10, own = null))
    }

    @Test
    fun only_a_push_and_a_background_wake_read_a_delta() {
        assertEquals(
            setOf(UnionTrigger.PUSH, UnionTrigger.WAKE),
            UnionTrigger.entries.filterNot { it.whole }.toSet(),
        )
    }
}
