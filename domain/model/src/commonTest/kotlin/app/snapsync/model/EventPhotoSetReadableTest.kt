package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * **The one unwrap of [CandidateRead]** (capability `gallery-status`; law `module-architecture`,
 * "Absence is never silent").
 *
 * The property under test is the distinction itself: an unreadable library and a library holding nothing
 * the policy admits must not arrive at the same answer. A count of `0` settles the status screen as
 * "everything shared" and — because the projection may not regress to `Loading` (capability
 * `sync-status`) — that frame cannot be taken back, so the two answers have to be separable *before*
 * anything counts.
 *
 * Exercised on JVM **and** `iosSimulatorArm64` (capability `testing-architecture`), because this is the
 * seam both device tiers reach through.
 */
class EventPhotoSetReadableTest {

    private val cutoff = captureCutoff("2026-06-01T00:00:00Z")
    private val inWindow = "2026-06-15T12:00:00Z"

    private suspend fun policy(): SelectionPolicy = SelectionPolicy(
        selectionRulesFor(
            includesUpload = true,
            cutoff = cutoff,
            ceiling = null,
            suppressedAssetIds = { emptySet() },
            albumExcludedAssetIds = { emptySet() },
        ),
    )

    private fun camera(assetId: String, creationDate: String = inWindow) = candidatesFromFacts(
        listOf(AssetFacts(assetId = assetId, creationDate = CaptureDate(creationDate))),
    )

    @Test
    fun a_readable_library_yields_a_set_that_counts() = runTest {
        val set = EventPhotoSet.readable(policy()) { CandidateRead.Readable(camera("A") + camera("B")) }

        assertEquals(2, set?.count())
    }

    @Test
    fun an_unreadable_library_yields_no_set_at_all() = runTest {
        val set = EventPhotoSet.readable(policy()) { CandidateRead.NotReadable }

        assertNull(set, "an unreadable library must not become an admitted set")
    }

    @Test
    fun a_readable_library_admitting_nothing_still_counts_zero() = runTest {
        // Out of scope by capture date — read successfully, admitted by nothing. This is the answer that
        // must stay distinguishable from the one above: it is a COUNTED zero, and it settles the screen.
        val set = EventPhotoSet.readable(policy()) {
            CandidateRead.Readable(camera("OLD", creationDate = "2020-01-01T00:00:00Z"))
        }

        assertEquals(0, set?.count(), "nothing qualifies is a count, not an absence")
    }

    @Test
    fun the_policy_reaches_the_read() = runTest {
        // The lambda's hazard, pinned at the one site that now stands between every consumer and the
        // seam: if this unwrap ever dropped its parameter, no consumer's policy would reach the platform
        // that narrows on it — the bug `EventPhotoSetSourceTest` exists to remember, relocated here.
        var seen: SelectionPolicy? = null
        val expected = policy()

        EventPhotoSet.readable(expected) { p ->
            seen = p
            CandidateRead.Readable(emptyList())
        }

        assertTrue(seen === expected, "the unwrap must hand the policy to the read")
    }
}
