package app.snapsync.integration

import app.snapsync.model.RawResource
import app.snapsync.model.ResourceRole
import app.snapsync.world.World
import app.snapsync.world.World.Companion.primaryResource
import app.snapsync.world.worldTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The manifest declares intent** (capability `device-manifest`), over the real stack.
 *
 * The unit tests assert the projection over a list of rows. This asserts the consequence the change
 * exists for, through the composed core the device shells actually run and the world's faithful
 * mini-edge: a device declares what it will provide, and the backend — not the device — is what keeps a
 * half-uploaded asset out of the event union until every declared role has arrived.
 *
 * The shape is the one that produced the defect this closes. A Live Photo is two resources, and they can
 * complete in different cycles. With a manifest that listed only COMPLETED rows, the asset was declared
 * with `primary` alone in between, so the union served it as a complete one-resource asset — and a
 * recipient reconciling in that window imported it as a plain still, marked the asset settled, and never
 * took the video, because a recipient plans per ASSET (capability `photo-download`).
 */
class DeclaredIntentIntegrationTest {

    private fun liveResource() = RawResource(
        role = ResourceRole.LIVE,
        mimeContentType = "video/quicktime",
        originalFilename = "IMG.MOV",
        handle = Unit,
    )

    @Test
    fun a_live_photo_is_declared_whole_and_stays_hidden_until_it_is_whole() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("LIVE", resources = listOf(primaryResource(), liveResource()))

        // One slot: the cycle can start exactly one of the two resources, so the asset is genuinely
        // half-uploaded when the manifest is published — the state the old projection mis-described.
        w.platform.jobLimit = 1
        w.runUploadCycle()
        // The operator plays the OS: the one job that was created lands. The second resource never got a
        // slot, so the asset is genuinely half-uploaded.
        val first = w.platform.created.single().filename
        w.platform.completeJob(first)
        w.runUploadCycle() // settle the terminal, promote, and publish

        val declared = w.store.manifestOf("E", w.ownDeviceId)?.assets.orEmpty()
        assertEquals(1, declared.size, "the asset is declared even though it is not fully uploaded")
        assertEquals(
            listOf(ResourceRole.LIVE, ResourceRole.PRIMARY),
            declared.single().resources.map { it.role }.sortedBy { it.name },
            "BOTH roles are declared — that declaration is what the backend measures arrival against",
        )
        assertEquals(
            1, w.store.objectsOf(w.ownDeviceId).size,
            "and only one resource's bytes have actually landed",
        )
        assertTrue(
            w.store.union("E").orEmpty().none { it.assetId == "LIVE" },
            "so the union hides it: a declared role with no stored byte makes the asset incomplete",
        )

        // The remaining slot frees up and the second resource lands.
        w.platform.jobLimit = 8
        w.runUploadCycle()
        w.platform.created.map { it.filename }.filter { it != first }.forEach { w.platform.completeJob(it) }
        w.runUploadCycle() // settle the terminal, promote, and publish

        assertEquals(2, w.store.objectsOf(w.ownDeviceId).size)
        val served = w.store.union("E").orEmpty().single { it.assetId == "LIVE" }
        assertEquals(
            listOf(ResourceRole.LIVE.wire, ResourceRole.PRIMARY.wire),
            served.resources.map { it.role }.sorted(),
            "and it is served WHOLE — never as the still-only asset the old projection offered",
        )
    }

    @Test
    fun a_discovered_asset_is_declared_before_any_byte_moves() = worldTest {
        val w = World(this)
        w.provision("E")
        w.addOwnAsset("A")

        // No slots at all: the cycle records what it discovered and publishes, but creates no job. The
        // ledger's rows are the intent set, so the declaration is complete even though nothing uploaded.
        w.platform.jobLimit = 0
        w.runUploadCycle()

        assertEquals(
            listOf("A"),
            w.store.manifestOf("E", w.ownDeviceId)?.assets.orEmpty().map { it.assetId },
            "declared on the strength of discovery alone",
        )
        assertTrue(w.store.objectsOf(w.ownDeviceId).isEmpty(), "with no byte uploaded")
        assertTrue(
            w.store.union("E").orEmpty().isEmpty(),
            "and invisible to every other member until its bytes arrive",
        )
    }
}
