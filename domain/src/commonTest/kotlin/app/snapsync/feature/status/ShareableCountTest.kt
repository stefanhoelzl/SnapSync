package app.snapsync.feature.status

import app.snapsync.model.AssetFacts
import app.snapsync.model.Candidate
import app.snapsync.model.CaptureDate
import app.snapsync.model.PermissionStatus
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

    override suspend fun candidates(policy: SelectionPolicy): List<Candidate> {
        consulted++
        return facts.map { f ->
            object : Candidate {
                override val facts = f
                override suspend fun resources(): List<Resource> = error("a count must not read resources")
            }
        }
    }
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
        permission: PermissionStatus = PermissionStatus.GRANTED,
    ) = count(includesUpload, cutoff, ceiling, permission)

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
    fun `a non-contributing candidate counts zero without consulting the source`() = runTest {
        val source = FactsSource(listOf(asset("A")))
        assertEquals(0, countSource(source).countFor(includesUpload = false))
        assertEquals(0, source.consulted, "Share off / DownloadOnly reaches zero before any read")
    }

    @Test
    fun `the count reads no resources`() = runTest {
        // Structural, not asserted by inspection: FactsSource.resources() throws, so a count that ever
        // started reading them would fail here rather than merely become slow.
        val n = countSource(FactsSource(listOf(asset("A"), asset("B")))).countFor()
        assertEquals(2, n)
    }

    @Test
    fun `DENIED and unresolved grants yield no count rather than a zero`() = runTest {
        // The distinction the surface depends on: no count renders NO ROW, while a zero renders "0
        // photos". This is the one grant question the consumer keeps — where candidates come from is the
        // source's business, whether an answer exists at all is not.
        val source = FactsSource(listOf(asset("A")))
        val s = countSource(source)
        assertNull(s.countFor(permission = PermissionStatus.DENIED))
        assertNull(s.countFor(permission = PermissionStatus.NOT_DETERMINED))
        assertEquals(0, source.consulted, "an unusable grant is answered without reading anything")
    }
}
