package app.snapsync.model

import app.snapsync.data.sharing.Contribution
import app.snapsync.data.sharing.DiscoveryRead
import app.snapsync.data.sharing.PreviewRead
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.upload.LedgerState
import app.snapsync.feature.status.OwnDeviceGalleryStatusSource
import app.snapsync.feature.support.OwnLibrary
import app.snapsync.feature.support.captureRange
import app.snapsync.feature.support.photo
import app.snapsync.feature.support.resourcesOf
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **The regression fixture the old suite could not express** (capability `photo-sharing`).
 *
 * `add-event-date-range` added the capture-date **ceiling** to the byte filter and the join preview and
 * missed the device-manifest projection and the status total `N`. Every existing test passed, because
 * every fixture used `until = null` — a closed window with a photo captured after it was a shape the
 * suite never built. On a real device it read as: 26 of 28 photos uploaded, two post-ceiling photos
 * listed in `device.json` with no bytes behind them, and the screen stuck at "Synchronization pending…"
 * forever.
 *
 * So this file builds exactly that shape — a **closed window** and an asset captured after it — and
 * asserts every consumer agrees, each asked through the real `OwnPhotos` over the in-memory library (whose walk
 * narrows by the floor only, as a platform that cannot express the ceiling would). It is deliberately about the
 * *set*, not about any one consumer's plumbing: each consumer's own tests cover its plumbing, and all four of those
 * passed while the bug was live.
 */
class CeilingReachesEveryConsumerTest {

    /** The membership under test: a **closed** capture window, exactly as a late joiner's would be. */
    private val window = captureRange("2026-07-01T00:00:00Z", until = "2026-07-08T00:00:00Z")

    private val inWindow = photo("IN", "2026-07-04T12:00:00Z")
    private val postCeiling = photo("AFTER", "2026-07-20T12:00:00Z") // the event's window closed twelve days earlier
    private val preCutoff = photo("BEFORE", "2026-06-01T12:00:00Z")

    private val library = OwnLibrary().apply { add(inWindow, postCeiling, preCutoff) }

    private suspend fun contribution(range: CaptureRange = window): Contribution = library.contribution(range)

    /** The COMPLETED ledger rows those photos would have produced — what the manifest projects from. */
    private fun ledgerRows(): List<LedgerEntry> =
        resourcesOf(inWindow, postCeiling, preCutoff).map { it.toLedgerRow(LedgerState.COMPLETED) }

    /** The admitted set the byte upload reads: one discovery, admitted by the membership's policy. */
    private suspend fun uploaded(range: CaptureRange = window): Set<AssetId> {
        val read = assertIs<DiscoveryRead.Discovered>(library.photos.discover(contribution(range)))
        return read.discovery.admitted.mapTo(mutableSetOf()) { it.facts.assetId }
    }

    /** The status total's admitted set, as the status source publishes it. */
    private suspend fun counted(): Set<AssetId>? = OwnDeviceGalleryStatusSource(library.photos)
        .apply { refresh(contribution()) }
        .admitted.value

    @Test
    fun `the byte upload admits only the in-window asset`() = runTest {
        assertEquals(setOf(testAssetId("IN")), uploaded())
    }

    @Test
    fun `the device manifest lists only the in-window asset`() = runTest {
        // The projection used to take a bare `startDate` and apply the floor alone — so AFTER was listed
        // in `device.json`, entered the event union, and was offered to every other member as bytes that
        // were never uploaded. A 404 for everyone.
        val manifest = projectDeviceManifest(testDeviceId("dev"), ledgerRows(), contribution().policy)
        assertEquals(listOf(testAssetId("IN")), manifest.assets.map { it.assetId })
    }

    @Test
    fun `the status total counts only the in-window asset`() = runTest {
        // `N` used to apply the floor alone too, which is the half the user could actually see: an asset
        // that counts toward the total but never uploads pegs completeness below 100% permanently.
        assertEquals(setOf(testAssetId("IN")), counted())
    }

    @Test
    fun `the join preview counts only the in-window asset`() = runTest {
        val preview = assertIs<PreviewRead.Counted>(library.photos.preview(window, received = emptySet()))
        assertEquals(1, preview.photos)
    }

    @Test
    fun `every consumer resolves the identical admitted set`() = runTest {
        // The property the whole change exists to make true. Stated over the SAME library the four
        // consumers see, so a future rule added to one of them fails here rather than on a device.
        val fromUpload = uploaded()
        val fromManifest = projectDeviceManifest(testDeviceId("dev"), ledgerRows(), contribution().policy)
            .assets.map { it.assetId }.toSet()
        val fromPreview = assertIs<PreviewRead.Counted>(library.photos.preview(window, emptySet())).photos

        assertEquals(fromUpload, counted())
        assertEquals(fromUpload, fromManifest)
        assertEquals(fromUpload.size, fromPreview)
        assertTrue(testAssetId("AFTER") !in fromUpload, "the post-ceiling asset is admitted by no consumer")
    }

    @Test
    fun `a ceiling past the asset admits the post-window asset`() = runTest {
        // The control. Without it the tests above would pass just as well against a policy that dropped
        // the asset for some unrelated reason — which is precisely how the original bug hid.
        val wider = captureRange("2026-07-01T00:00:00Z", until = "2026-07-25T00:00:00Z")
        assertEquals(setOf(testAssetId("IN"), testAssetId("AFTER")), uploaded(wider))
    }
}
