package app.snapsync.feature.upload

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two stranded-row recovery rules (capability `ios-url-session-upload`, "Stranded reconciliation: scoped each
 * cycle, complete at a start"), both the cycle's and both asserted on every target `:domain:feature` declares.
 */
class StrandedKeysTest {

    // ---- each cycle: only what this transport began and lost ------------------------------------------

    /**
     * The recovery for a transfer the OS dropped: `REQUESTED` in the ledger, and reported lost by the transport
     * that began it. Without it the row stays `REQUESTED` forever and the photo is abandoned with no error.
     */
    @Test
    fun a_requested_key_the_transport_lost_is_stranded_each_cycle() {
        assertEquals(
            listOf("lost.heic"),
            strandedEachCycle(pending = setOf("lost.heic", "running.heic"), lost = setOf("lost.heic")),
        )
    }

    /**
     * The scoping: a `REQUESTED` key this transport never began may be another transport's transfer, still in
     * flight, so the per-cycle rule never demotes it — however long it has had no transfer here.
     */
    @Test
    fun a_requested_key_the_transport_never_began_is_not_stranded_each_cycle() {
        assertEquals(emptyList<String>(), strandedEachCycle(pending = setOf("other.heic"), lost = emptySet()))
    }

    /** A lost transfer whose row is no longer `REQUESTED` (an orphan) is the discard's, not a candidate. */
    @Test
    fun a_lost_key_that_is_not_requested_is_never_surfaced_each_cycle() {
        assertEquals(emptyList<String>(), strandedEachCycle(pending = emptySet(), lost = setOf("orphan.heic")))
    }

    // ---- at a start: every REQUESTED key with no live transfer ----------------------------------------

    @Test
    fun a_requested_key_with_no_live_transfer_is_stranded_at_a_start() {
        assertEquals(
            listOf("lost.heic"),
            strandedAtStart(pending = setOf("lost.heic", "running.heic"), live = setOf("running.heic")),
        )
    }

    @Test
    fun nothing_is_stranded_at_a_start_when_every_requested_key_is_still_held() {
        assertEquals(
            emptyList<String>(),
            strandedAtStart(pending = setOf("a.heic", "b.heic"), live = setOf("a.heic", "b.heic")),
        )
        assertEquals(emptyList<String>(), strandedAtStart(pending = emptySet(), live = setOf("a.heic")))
    }

    /**
     * The candidate set is what keeps a settled row out, and that is the caller's contract to honour: neither
     * rule filters by state, so handing one a `DISCOVERED` or `COMPLETED` key would surface it. The narrowing is
     * pinned where the read happens — `LedgerStoreContract`'s "requestedKeys is REQUESTED only" — because that
     * is where it can actually be got wrong.
     */
    @Test
    fun whatever_is_handed_in_as_pending_is_taken_at_face_value() {
        assertEquals(listOf("settled.heic"), strandedAtStart(pending = setOf("settled.heic"), live = emptySet()))
        assertEquals(listOf("settled.heic"), strandedEachCycle(pending = setOf("settled.heic"), lost = setOf("settled.heic")))
    }
}
