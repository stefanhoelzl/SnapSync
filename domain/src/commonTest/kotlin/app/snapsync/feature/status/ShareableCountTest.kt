package app.snapsync.feature.status

import app.snapsync.model.Contribution
import app.snapsync.model.MEDIA_TYPE_IMAGE
import app.snapsync.model.PermissionStatus
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.RawAsset
import app.snapsync.model.Resource
import app.snapsync.model.SUBTYPE_NONE
import app.snapsync.model.SUBTYPE_SCREENSHOT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** Every candidate carries a cutoff (capability `photo-selection-policy`). */
private const val CUTOFF = "2026-07-06T00:00:00Z"
private const val IN_SCOPE = "2026-07-10T00:00:00Z"
private const val PRE_CUTOFF = "2026-07-01T00:00:00Z"

/** An admitted camera photo unless it opts into an exclusion (RawAsset defaults are a 12 MP capture). */
private fun asset(
    id: String,
    creationDate: String = IN_SCOPE,
    subtypes: Long = SUBTYPE_NONE,
    width: Long = 4032,
    height: Long = 3024,
) = RawAsset(
    assetId = id,
    creationDate = creationDate,
    rawResources = emptyList(), // facts-only, exactly what the cheap walk produces
    mediaSubtypes = subtypes,
    mediaType = MEDIA_TYPE_IMAGE,
    pixelWidth = width,
    pixelHeight = height,
)

class ShareableCountTest {

    // ---- The pure count over cheap facts -------------------------------------------------------------

    @Test
    fun `counts distinct admitted assets at or after the cutoff`() {
        val n = shareableCountFromAssets(
            listOf(asset("A"), asset("B"), asset("OLD", creationDate = PRE_CUTOFF)),
            CUTOFF, emptySet(), emptySet(),
        )
        assertEquals(2, n, "OLD precedes the cutoff and is not shared")
    }

    @Test
    fun `origin-excluded assets are not counted`() {
        val n = shareableCountFromAssets(
            listOf(
                asset("CAM"),
                asset("SHOT", subtypes = SUBTYPE_SCREENSHOT),
                asset("WA", width = 1600, height = 1200), // 1.9 MP → below the 3 MP floor
            ),
            CUTOFF, emptySet(), emptySet(),
        )
        assertEquals(1, n, "only the camera photo is shared — the same policy the cycle applies")
    }

    @Test
    fun `denylisted-album and suppressed assets are subtracted`() {
        val assets = listOf(asset("CAM"), asset("WA"), asset("DL"))
        assertEquals(
            1,
            shareableCountFromAssets(assets, CUTOFF, suppressed = setOf("DL"), albumExcluded = setOf("WA")),
            "a downloaded echo (DL) and a denylisted-album member (WA) do not count",
        )
    }

    // ---- The permission-branching source -------------------------------------------------------------

    @Test
    fun `GRANTED reads the cheap facts walk`() = runTest {
        var walked = 0
        val source = ShareableCountSource(
            factsSince = { walked++; listOf(asset("A"), asset("B")) },
        )
        val n = source.count(Contribution.Since(CUTOFF), PermissionStatus.GRANTED, selectionSnapshot = null)
        assertEquals(2, n)
        assertEquals(1, walked)
    }

    @Test
    fun `a non-contributing candidate counts zero without any read`() = runTest {
        var walked = 0
        val source = ShareableCountSource(factsSince = { walked++; listOf(asset("A")) })
        val n = source.count(Contribution.None, PermissionStatus.GRANTED, selectionSnapshot = null)
        assertEquals(0, n)
        assertEquals(0, walked, "Share off / DownloadOnly reaches zero before any walk")
    }

    @Test
    fun `LIMITED counts the selection snapshot without walking`() = runTest {
        var walked = 0
        val source = ShareableCountSource(factsSince = { walked++; emptyList() })
        val snapshot = listOf(
            Resource("A-primary.jpg", "A", "image/jpeg", mapOf(RESOURCE_META_CREATION_DATE to IN_SCOPE), Unit),
            Resource("O-primary.jpg", "O", "image/jpeg", mapOf(RESOURCE_META_CREATION_DATE to PRE_CUTOFF), Unit),
        )
        val n = source.count(Contribution.Since(CUTOFF), PermissionStatus.LIMITED, snapshot)
        assertEquals(1, n, "the in-scope selected photo counts; the pre-cutoff one does not")
        assertEquals(0, walked, "no autonomous library read under LIMITED")
    }

    @Test
    fun `DENIED and unresolved grants yield no count`() = runTest {
        val source = ShareableCountSource(factsSince = { error("must not read") })
        assertNull(source.count(Contribution.Since(CUTOFF), PermissionStatus.DENIED, selectionSnapshot = null))
        assertNull(source.count(Contribution.Since(CUTOFF), PermissionStatus.NOT_DETERMINED, selectionSnapshot = null))
    }
}
