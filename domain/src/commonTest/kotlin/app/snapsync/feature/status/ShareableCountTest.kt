package app.snapsync.feature.status

import app.snapsync.model.AssetFacts
import app.snapsync.model.CaptureDate
import app.snapsync.model.PermissionStatus
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.Resource
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
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

private fun source(
    facts: suspend () -> List<AssetFacts>,
    suppressed: Set<String> = emptySet(),
    albumExcluded: Set<String> = emptySet(),
) = ShareableCountSource(
    factsSince = { facts() },
    suppressedLocalIds = { suppressed },
    albumExcludedAssetIds = { albumExcluded },
)

class ShareableCountTest {

    @Test
    fun `counts distinct admitted assets at or after the cutoff`() = runTest {
        val n = source({ listOf(asset("A"), asset("B"), asset("OLD", creationDate = PRE_CUTOFF)) })
            .count(includesUpload = true, cutoff = CUTOFF, ceiling = null, PermissionStatus.GRANTED, null)
        assertEquals(2, n, "OLD precedes the cutoff and is not shared")
    }

    @Test
    fun `an upper bound excludes assets captured after it`() = runTest {
        // The count is a policy consumer (capability `photo-selection-policy`): it must respect the
        // capture-date range [cutoff, until] exactly as the upload cycle does, or the join surface
        // over-reports what will be shared.
        val n = source({ listOf(asset("IN"), asset("AFTER", creationDate = POST_UNTIL)) })
            .count(includesUpload = true, cutoff = CUTOFF, ceiling = UNTIL, PermissionStatus.GRANTED, null)
        assertEquals(1, n, "AFTER is past the upper bound; a null ceiling would count both")
    }

    @Test
    fun `origin-excluded assets are not counted`() = runTest {
        val n = source({
            listOf(
                asset("CAM"),
                asset("SHOT", isScreenshot = true),
                asset("WA", width = 1600, height = 1200), // 1.9 MP → below the 3 MP floor
            )
        }).count(includesUpload = true, cutoff = CUTOFF, ceiling = null, PermissionStatus.GRANTED, null)
        assertEquals(1, n, "only the camera photo is shared — the same policy the cycle applies")
    }

    @Test
    fun `denylisted-album and suppressed assets are subtracted`() = runTest {
        val n = source(
            { listOf(asset("CAM"), asset("WA"), asset("DL")) },
            suppressed = setOf("DL"),
            albumExcluded = setOf("WA"),
        ).count(includesUpload = true, cutoff = CUTOFF, ceiling = null, PermissionStatus.GRANTED, null)
        assertEquals(1, n, "a downloaded echo (DL) and a denylisted-album member (WA) do not count")
    }

    @Test
    fun `GRANTED reads the cheap facts walk`() = runTest {
        var walked = 0
        val n = source({ walked++; listOf(asset("A"), asset("B")) })
            .count(includesUpload = true, cutoff = CUTOFF, ceiling = null, PermissionStatus.GRANTED, null)
        assertEquals(2, n)
        assertEquals(1, walked)
    }

    @Test
    fun `a non-contributing candidate counts zero without any read`() = runTest {
        var walked = 0
        val n = source({ walked++; listOf(asset("A")) })
            .count(includesUpload = false, cutoff = CUTOFF, ceiling = null, PermissionStatus.GRANTED, null)
        assertEquals(0, n)
        assertEquals(0, walked, "Share off / DownloadOnly reaches zero before any walk")
    }

    @Test
    fun `LIMITED counts the selection snapshot without walking`() = runTest {
        var walked = 0
        val snapshot = listOf(
            Resource("A-primary.jpg", "A", "image/jpeg", mapOf(RESOURCE_META_CREATION_DATE to IN_SCOPE), Unit),
            Resource("O-primary.jpg", "O", "image/jpeg", mapOf(RESOURCE_META_CREATION_DATE to PRE_CUTOFF), Unit),
        )
        val n = source({ walked++; emptyList() })
            .count(includesUpload = true, cutoff = CUTOFF, ceiling = null, PermissionStatus.LIMITED, snapshot)
        assertEquals(1, n, "the in-scope selected photo counts; the pre-cutoff one does not")
        assertEquals(0, walked, "no autonomous library read under LIMITED")
    }

    @Test
    fun `DENIED and unresolved grants yield no count`() = runTest {
        val s = source({ error("must not read") })
        assertNull(s.count(includesUpload = true, cutoff = CUTOFF, ceiling = null, PermissionStatus.DENIED, null))
        assertNull(
            s.count(includesUpload = true, cutoff = CUTOFF, ceiling = null, PermissionStatus.NOT_DETERMINED, null),
        )
    }
}
