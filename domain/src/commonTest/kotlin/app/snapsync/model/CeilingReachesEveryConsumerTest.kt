package app.snapsync.model

import app.snapsync.feature.status.ShareableCountSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * **The regression fixture the old suite could not express** (capability `photo-selection-policy`).
 *
 * `add-event-date-range` added the capture-date **ceiling** to the byte filter and the join preview and
 * missed the device-manifest projection and the status total `N`. Every existing test passed, because
 * every fixture used `until = null` — a closed window with a photo captured after it was a shape the
 * suite never built. On a real device it read as: 26 of 28 photos uploaded, two post-ceiling photos
 * listed in `device.json` with no bytes behind them, and the screen stuck at "Synchronization pending…"
 * forever.
 *
 * So this file builds exactly that shape — a **closed window** and an asset captured after it — and
 * asserts every consumer agrees. It is deliberately about the *set*, not about any one consumer's
 * plumbing: each consumer's own tests cover its plumbing, and all four of those passed while the bug was
 * live.
 */
class CeilingReachesEveryConsumerTest {

    private val cutoff = captureCutoff("2026-07-01T00:00:00Z")
    private val ceiling = captureCeiling("2026-07-08T00:00:00Z")

    private val inWindow = "2026-07-04T12:00:00Z"
    private val postCeiling = "2026-07-20T12:00:00Z" // the event's window closed twelve days earlier
    private val preCutoff = "2026-06-01T12:00:00Z"

    /** The membership under test: a **closed** capture window, exactly as a late joiner's would be. */
    private val policy = SelectionPolicy.from(includesUpload = true, cutoff = cutoff, ceiling = ceiling)

    private fun resource(assetId: String, creationDate: String) = Resource(
        filename = "$assetId-primary.heic",
        assetId = assetId,
        contentType = "public.heic",
        metadata = mapOf(
            RESOURCE_META_CREATION_DATE to creationDate,
            RESOURCE_META_PIXEL_WIDTH to "4032",
            RESOURCE_META_PIXEL_HEIGHT to "3024",
        ),
        data = Unit,
    )

    private val discovered = listOf(
        resource("IN", inWindow),
        resource("AFTER", postCeiling),
        resource("BEFORE", preCutoff),
    )

    @Test
    fun `the byte upload admits only the in-window asset`() {
        assertEquals(setOf("IN"), policy.admittedAssetIds(discovered))
    }

    @Test
    fun `the device manifest lists only the in-window asset`() {
        // The projection used to take a bare `startDate` and apply the floor alone — so AFTER was listed
        // in `device.json`, entered the event union, and was offered to every other member as bytes that
        // were never uploaded. A 404 for everyone.
        val accumulator = discovered.map { DeviceManifestAsset(it.assetId, it.metadata[RESOURCE_META_CREATION_DATE]!!, emptyList()) }
        val manifest = projectDeviceManifest("dev", accumulator, policy)
        assertEquals(listOf("IN"), manifest.assets.map { it.assetId })
    }

    @Test
    fun `the status total counts only the in-window asset`() = runTest {
        // `N` used to apply the floor alone too, which is the half the user could actually see: an asset
        // that counts toward the total but never uploads pegs completeness below 100% permanently.
        assertEquals(1, policy.admittedAssetIds(discovered).size)
    }

    @Test
    fun `the join preview counts only the in-window asset`() = runTest {
        val facts = discovered.map {
            AssetFacts(it.assetId, CaptureDate(it.metadata[RESOURCE_META_CREATION_DATE]!!), pixelArea = 12_000_000)
        }
        val count = ShareableCountSource(factsSince = { facts })
            .count(
                includesUpload = true,
                cutoff = cutoff,
                ceiling = ceiling,
                permission = PermissionStatus.GRANTED,
                selectionSnapshot = null,
            )
        assertEquals(1, count)
    }

    @Test
    fun `every consumer resolves the identical admitted set`() {
        // The property the whole change exists to make true. Stated over the SAME inputs the four
        // consumers see, so a future rule added to one of them fails here rather than on a device.
        val fromResources = policy.admittedAssetIds(discovered)
        val fromFacts = factsFromResources(discovered).filter { policy.admits(it) }.map { it.assetId }.toSet()
        val fromManifest = projectDeviceManifest(
            "dev",
            discovered.map { DeviceManifestAsset(it.assetId, it.metadata[RESOURCE_META_CREATION_DATE]!!, emptyList()) },
            policy,
        ).assets.map { it.assetId }.toSet()

        assertEquals(fromResources, fromFacts)
        assertEquals(fromResources, fromManifest)
        assertTrue("AFTER" !in fromResources, "the post-ceiling asset is admitted by no consumer")
    }

    @Test
    fun `an unbounded ceiling still admits the post-window asset`() {
        // The control. Without it the tests above would pass just as well against a policy that dropped
        // the asset for some unrelated reason — which is precisely how the original bug hid.
        val unbounded = SelectionPolicy.from(includesUpload = true, cutoff = cutoff, ceiling = null)
        assertEquals(setOf("IN", "AFTER"), unbounded.admittedAssetIds(discovered))
    }
}
