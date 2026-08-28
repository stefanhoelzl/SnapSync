package app.snapsync.feature.status

import app.snapsync.model.AssetFacts
import app.snapsync.model.Candidate
import app.snapsync.model.CandidateRead
import app.snapsync.model.CaptureDate
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.ports.CandidateSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** Every candidate carries a cutoff (capability `photo-selection-policy`). */
private val CUTOFF = captureCutoff("2026-07-06T00:00:00Z")
private const val IN_SCOPE = "2026-07-10T00:00:00Z"
private const val PRE_CUTOFF = "2026-07-01T00:00:00Z"
private val UNTIL = captureCeiling("2026-07-14T00:00:00Z")
private const val POST_UNTIL = "2026-07-20T00:00:00Z"

/** An admitted camera photo unless it opts into an exclusion (a 12 MP capture by default). */
private fun asset(
    id: String,
    creationDate: String = IN_SCOPE,
    isScreenshot: Boolean = false,
    width: Long = 4032,
    height: Long = 3024,
) = AssetFacts(
    assetId = id,
    creationDate = CaptureDate(creationDate),
    isScreenshot = isScreenshot,
    pixelArea = width * height,
)

/**
 * A source of facts-only candidates — what a real facts-only walk hands back — that **counts its own
 * consultations** and **throws** if anyone asks for resources.
 *
 * Both are load-bearing rather than decorative: the consultation count proves a non-contributing
 * membership short-circuits before any read, and the throwing `resources()` makes "a count reads no
 * resources" structural instead of a comment (capability `photo-selection-policy`).
 */
private class FactsSource(private val facts: List<AssetFacts>) : CandidateSource {
    var consulted = 0
        private set

    override suspend fun candidates(policy: SelectionPolicy): CandidateRead {
        consulted++
        return CandidateRead.Readable(
            facts.map { f ->
                object : Candidate {
                    override val facts = f
                    override suspend fun resources(): List<Resource> = error("a count must not read resources")
                }
            },
        )
    }
}

/** A source that cannot answer at all — no grant, or a partial grant with no snapshot yet. */
private object UnreadableSource : CandidateSource {
    override suspend fun candidates(policy: SelectionPolicy): CandidateRead = CandidateRead.NotReadable
}

private fun countSource(
    source: CandidateSource,
    suppressed: Set<String> = emptySet(),
    albumExcluded: Set<String> = emptySet(),
) = ShareableCountSource(
    source = source,
    suppressedLocalIds = { suppressed },
    albumExcludedAssetIds = { albumExcluded },
)

class ShareableCountTest {

    private suspend fun ShareableCountSource.countFor(
        cutoff: app.snapsync.model.CaptureCutoff = CUTOFF,
        ceiling: app.snapsync.model.CaptureCeiling? = null,
        includesUpload: Boolean = true,
    ) = count(includesUpload, cutoff, ceiling)

    @Test
    fun `counts distinct admitted assets at or after the cutoff`() = runTest {
        val n = countSource(FactsSource(listOf(asset("A"), asset("B"), asset("OLD", creationDate = PRE_CUTOFF))))
            .countFor()
        assertEquals(2, n, "OLD precedes the cutoff and is not shared")
    }

    @Test
    fun `an upper bound excludes assets captured after it`() = runTest {
        // The count is a policy consumer (capability `photo-selection-policy`): it must respect the
        // capture-date range [cutoff, until] exactly as the upload cycle does, or the join surface
        // over-reports what will be shared.
        val n = countSource(FactsSource(listOf(asset("IN"), asset("AFTER", creationDate = POST_UNTIL))))
            .countFor(ceiling = UNTIL)
        assertEquals(1, n, "AFTER is past the upper bound; a null ceiling would count both")
    }

    @Test
    fun `origin-excluded assets are not counted`() = runTest {
        val n = countSource(
            FactsSource(
                listOf(
                    asset("CAM"),
                    asset("SHOT", isScreenshot = true),
                    asset("WA", width = 1600, height = 1200), // 1.9 MP → below the 3 MP floor
                ),
            ),
        ).countFor()
        assertEquals(1, n, "only the camera photo is shared — the same policy the cycle applies")
    }

    @Test
    fun `denylisted-album and suppressed assets are subtracted`() = runTest {
        val n = countSource(
            FactsSource(listOf(asset("CAM"), asset("WA"), asset("DL"))),
            suppressed = setOf("DL"),
            albumExcluded = setOf("WA"),
        ).countFor()
        assertEquals(1, n, "a downloaded echo (DL) and a denylisted-album member (WA) do not count")
    }

    @Test
    fun `a non-contributing candidate counts zero at the cost of one narrowed fetch`() = runTest {
        val source = FactsSource(listOf(asset("A")))

        assertEquals(0, countSource(source).countFor(includesUpload = false), "Share off counts nothing")

        // The caller-side short-circuit is gone with `enumerates` (capability `photo-selection-policy`).
        // The cost it avoided has not moved to the caller — it is removed where it actually arises: a
        // deny-everything policy is translated into a fetch predicate matching NO asset, so a real
        // platform returns nothing rather than a library's worth of round-trips. This fake does not
        // translate rules, so it is consulted once and its (unnarrowed) list is refused by `admits`.
        //
        // One fetch is the price, and it is charged per cycle rather than per asset — which is the
        // requirement (capability `gallery-status`: the count costs no PER-ASSET read).
        assertEquals(1, source.consulted, "exactly one fetch, which a real platform narrows to nothing")
    }

    @Test
    fun `the count reads no resources`() = runTest {
        // Structural, not asserted by inspection: FactsSource.resources() throws, so a count that ever
        // started reading them would fail here rather than merely become slow.
        val n = countSource(FactsSource(listOf(asset("A"), asset("B")))).countFor()
        assertEquals(2, n)
    }

    @Test
    fun `an unreadable library yields no count rather than a zero`() = runTest {
        // The distinction the surface depends on: no count renders NO ROW, while a zero renders "0
        // photos". The preview no longer asks the grant to find that out — the seam says so, which is
        // what makes this cover a partial grant with no snapshot as well as a denied one. Keeping the
        // check here covered only the grant, and `grantsPhotoAccess` is TRUE under LIMITED.
        assertNull(countSource(UnreadableSource).countFor())
    }

    @Test
    fun `a readable library holding nothing admitted still counts zero`() = runTest {
        // The other side of the pair: an empty answer that WAS read is a count, and the surface renders
        // "0 photos" rather than omitting the row.
        assertEquals(0, countSource(FactsSource(emptyList())).countFor())
    }
}
