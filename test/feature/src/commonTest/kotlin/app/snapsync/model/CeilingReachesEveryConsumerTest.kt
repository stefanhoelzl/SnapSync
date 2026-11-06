package app.snapsync.model

import app.snapsync.application.EntryPointCall
import app.snapsync.application.sharing.CountOwnPhotos
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.sharing.AdmittedPhotos
import app.snapsync.data.sharing.Contribution
import app.snapsync.data.sharing.DiscoveryRead
import app.snapsync.data.sharing.PreviewRead
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.DeviceManifest
import app.snapsync.domain.sharing.ManifestVersion
import app.snapsync.domain.sharing.ShareDeclaration
import app.snapsync.domain.upload.MintsUploadRow
import app.snapsync.domain.upload.RowDetail
import app.snapsync.domain.upload.UploadKey
import app.snapsync.domain.upload.UploadRow
import app.snapsync.feature.support.OwnLibrary
import app.snapsync.feature.support.SharedStore
import app.snapsync.feature.support.captureRange
import app.snapsync.feature.support.photo
import app.snapsync.format.TimestampText
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
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

    /** The settled upload rows those photos would have produced — what the manifest is published from. */
    @OptIn(MintsUploadRow::class)
    private fun settled(asset: String, taken: String): UploadRow = UploadRow.Completed(
        UploadKey.of(testAssetId(asset), ResourceRole.PRIMARY, "IMG.HEIC"),
        RowDetail(CaptureDate.Dated(TimestampText.parse(taken)), "image/heic", "IMG.HEIC"),
    )

    private val rows = listOf(
        settled("IN", "2026-07-04T12:00:00Z"),
        settled("AFTER", "2026-07-20T12:00:00Z"),
        settled("BEFORE", "2026-06-01T12:00:00Z"),
    )

    /** The assets the device manifest lists, admitted by the membership's policy. */
    private suspend fun listed(): Set<AssetId> {
        val policy = contribution().policy
        val admitted = policy.admitRecorded(rows.map { it.recorded })
        val manifest = DeviceManifest.of(
            testDeviceId("D"),
            rows,
            admitted,
            ManifestVersion(1),
            ShareDeclaration.Sharing,
        )
        return manifest.assets.mapTo(mutableSetOf()) { it.asset }
    }

    /** The admitted set the byte upload reads: one discovery, admitted by the membership's policy. */
    private suspend fun uploaded(range: CaptureRange = window): Set<AssetId> {
        val read = assertIs<DiscoveryRead.Discovered>(library.photos.discover(contribution(range)))
        return read.discovery.admitted.mapTo(mutableSetOf()) { it.facts.assetId }
    }

    /** The status total's admitted set, as the count publishes it — for a membership sharing [window]. */
    @OptIn(EntryPointCall::class)
    private suspend fun counted(): Set<AssetId>? {
        val store = SharedStore().apply {
            persist(
                EventConfig(
                    eventId = testEventId("11111111-1111-4111-8111-111111111111"),
                    name = "Picnic",
                    minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-01T00:00:00Z")),
                    maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-08T00:00:00Z")),
                    endsAt = EventEnd(TimestampText.parse("2026-07-08T00:00:00Z")),
                    deletesAt = DeletesAt(TimestampText.parse("2099-12-31T00:00:00Z")),
                ),
            )
        }
        val admitted = AdmittedPhotos()
        CountOwnPhotos(MembershipRepository(store.db), library.received, library.photos, admitted)()
        return admitted.state.value
    }

    @Test
    fun `the byte upload admits only the in-window asset`() = runTest {
        assertEquals(setOf(testAssetId("IN")), uploaded())
    }

    @Test
    fun `the device manifest lists only the in-window asset`() = runTest {
        // The projection used to take a bare `startDate` and apply the floor alone — so AFTER was listed
        // in `device.json`, entered the event union, and was offered to every other member as bytes that
        // were never uploaded. A 404 for everyone.
        assertEquals(setOf(testAssetId("IN")), listed())
    }

    @Test
    fun `the status total counts only the in-window asset`() = runTest {
        // `N` used to apply the floor alone too, which is the half the user could actually see: an asset
        // that counts toward the total but never uploads pegs completeness below 100% permanently.
        assertEquals(setOf(testAssetId("IN")), counted())
    }

    @Test
    fun `the join preview counts only the in-window asset`() = runTest {
        val preview = assertIs<PreviewRead.Counted>(library.photos.preview(window))
        assertEquals(1, preview.photos)
    }

    @Test
    fun `every consumer resolves the identical admitted set`() = runTest {
        // The property the whole change exists to make true. Stated over the SAME library the four
        // consumers see, so a future rule added to one of them fails here rather than on a device.
        val fromUpload = uploaded()
        val fromManifest = listed()
        val fromPreview = assertIs<PreviewRead.Counted>(library.photos.preview(window)).photos

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
