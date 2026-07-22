package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [Contribution] — the per-membership selection inputs (capability `photo-selection-policy`).
 *
 * The properties worth pinning are the ones that make the *type* do the work: a non-contributor carries no
 * cutoff (so the meaningless "contributes nothing, with a cutoff" state cannot be built), and both consumers
 * can ask "should I walk?" without destructuring.
 */
class ContributionTest {

    @Test
    fun none_carries_no_cutoff_to_walk_by() {
        // The whole point of the sealed form: there is no cutoff on the non-contributing branch, so no
        // consumer can accidentally scope a walk by one.
        assertNull(Contribution.None.cutoffOrNull)
        assertFalse(Contribution.None.uploads)
    }

    @Test
    fun since_carries_the_membership_cutoff() {
        val c: Contribution = Contribution.Since("2026-07-16T10:00:00Z", until = null)
        assertEquals("2026-07-16T10:00:00Z", c.cutoffOrNull)
        assertTrue(c.uploads)
    }

    @Test
    fun the_two_states_are_distinct_and_exhaustive() {
        // Exhaustiveness is the enforcement: a `when` over Contribution with no else must compile, so a
        // future third state forces every consumer to state its answer rather than inherit one.
        val cases = listOf(Contribution.None, Contribution.Since("2026-01-01T00:00:00Z", until = null))
        val described = cases.map { c ->
            when (c) {
                Contribution.None -> "none"
                is Contribution.Since -> "since:${c.cutoff}"
            }
        }
        assertEquals(listOf("none", "since:2026-01-01T00:00:00Z"), described)
    }

    @Test
    fun of_maps_a_membership_s_two_facts_and_is_the_only_place_that_decision_is_made() {
        // The roots pass facts; this makes the decision. Five untested composition roots would otherwise
        // each carry this `if` — which is how the download arm's root binding acquired its `?: true`.
        assertEquals(
            Contribution.Since("2026-07-16T10:00:00Z", until = null),
            Contribution.of(true, "2026-07-16T10:00:00Z", until = null),
        )
        assertEquals(Contribution.None, Contribution.of(false, "2026-07-16T10:00:00Z", until = null))
    }

    @Test
    fun of_drops_the_cutoff_when_nothing_is_contributed() {
        // A download-only membership still HAS a cutoff on its EventConfig — it is just meaningless. The
        // mapping discards it rather than carrying it into a state where it could be mistaken for scope.
        assertNull(Contribution.of(includesUpload = false, cutoff = "2026-01-01T00:00:00Z", until = null).cutoffOrNull)
    }

    @Test
    fun an_empty_cutoff_is_representable_but_is_not_the_way_to_say_nothing() {
        // `Since("")` admits every creationDate — it is "share the whole library from the beginning of
        // time", NOT "share nothing". Anyone reaching for a default lands on this; the type offers None as
        // the only way to say nothing, and no default at all.
        val everything = Contribution.Since("", until = null)
        assertTrue(everything.uploads)
        assertEquals("", everything.cutoffOrNull)
    }
}
