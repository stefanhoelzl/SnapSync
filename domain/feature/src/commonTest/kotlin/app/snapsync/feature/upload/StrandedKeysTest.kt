package app.snapsync.feature.upload

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The stranded-row recovery rule (capability `ios-url-session-upload`, "Precise in-flight reconciliation replaces
 * blanket clear"), now the cycle's. It used to be tested only in `:adapter:ios:app-only`'s `iosTest`, which runs
 * on a Mac; here it runs on every target `:domain:feature` declares.
 */
class StrandedKeysTest {

    /**
     * The recovery rule for a transfer the OS dropped: `REQUESTED` in the ledger, with no live transfer.
     * Without it the row stays `REQUESTED` forever, the engine treats it as in-flight and never re-issues
     * it, and the photo is abandoned with no error anywhere.
     */
    @Test
    fun a_requested_key_with_no_live_transfer_is_stranded() {
        assertEquals(
            listOf("lost.heic"),
            strandedKeys(pending = setOf("lost.heic", "running.heic"), live = setOf("running.heic")),
        )
    }

    @Test
    fun nothing_is_stranded_when_every_requested_key_is_still_held() {
        assertEquals(
            emptyList<String>(),
            strandedKeys(pending = setOf("a.heic", "b.heic"), live = setOf("a.heic", "b.heic")),
        )
        assertEquals(emptyList<String>(), strandedKeys(pending = emptySet(), live = setOf("a.heic")))
    }

    @Test
    fun a_live_key_that_is_not_requested_is_never_surfaced() {
        assertEquals(emptyList<String>(), strandedKeys(pending = emptySet(), live = setOf("x.heic")))
    }

    /**
     * The candidate set is what keeps a settled row out, and that is the caller's contract to honour: this
     * function subtracts live transfers and nothing else, so handing it a `FAILED` or `COMPLETED` key would
     * surface it as lost. The narrowing is pinned where the read happens — `LedgerStoreContract`'s
     * "requestedKeys is REQUESTED only" — because that is where it can actually be got wrong.
     */
    @Test
    fun whatever_is_handed_in_as_pending_is_taken_at_face_value() {
        assertEquals(
            listOf("settled.heic"),
            strandedKeys(pending = setOf("settled.heic"), live = emptySet()),
            "no state filtering happens here — the caller must hand in REQUESTED keys only",
        )
    }
}
