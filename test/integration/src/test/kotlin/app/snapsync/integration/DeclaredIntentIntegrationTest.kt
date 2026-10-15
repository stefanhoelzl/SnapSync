package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.LibraryPhotoKind
import app.snapsync.levers.WorldEvent
import app.snapsync.model.ResourceRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The manifest declares intent**, over the real stack.
 *
 * The unit tests assert the projection over a list of rows. This asserts the consequence the change exists for,
 * through the composed core the device shells actually run and the backend mock, driven through the control
 * protocol: a device declares what it will provide, and the backend — not the device — is what keeps a
 * half-uploaded asset out of the event union until every declared role has arrived.
 *
 * The shape is the one that produced the defect this closes. A Live Photo is two resources, and they can complete
 * in different cycles. With a manifest that listed only COMPLETED rows, the asset was declared with `primary` alone
 * in between, so the union served it as a complete one-resource asset — and a recipient reconciling in that window
 * imported it as a plain still, marked the asset settled, and never took the video, because a recipient plans per
 * ASSET.
 */
@Verifies(spec = "photo-sharing", requirement = "Other members see a photo only once it is complete")
class DeclaredIntentIntegrationTest {

    private val bothRoles = setOf(ResourceRole.LIVE.wire, ResourceRole.PRIMARY.wire)

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Other members see a photo only once it is complete",
        scenario = "Half a Live Photo is never offered",
    )
    fun a_live_photo_is_declared_whole_and_stays_hidden_until_it_is_whole() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            photoTaken("LIVE", LibraryPhotoKind.LIVE_PHOTO)
            // One slot: the cycle can start exactly one of the two resources, so the asset is genuinely half-uploaded
            // when the manifest is published — the state the old projection mis-described.
            step("one slot", act = { uploadQueue.jobLimit(1) }, until = {})
        }

        // The asset is declared even though it is not fully uploaded.
        lateinit var first: Seen<WorldEvent.UploadJobCreated>
        whenever { cycle() }
        then {
            first = event<WorldEvent.UploadJobCreated>()
            times(1) { event<WorldEvent.UploadJobCreated>() }
            event<WorldEvent.ManifestPublished> { "LIVE" in it.added }
        }

        // The operator plays the OS: the one job that was created lands, and a cycle settles it and publishes. The
        // second resource never got a slot, so the asset is genuinely half-uploaded.
        lateinit var landed: Seen<WorldEvent.BytesStored>
        whenever {
            uploadQueue.completeJob(first.value.job)
            cycle()
        }
        then {
            // Only one resource's bytes have actually landed…
            landed = event<WorldEvent.BytesStored> { it.asset == "LIVE" }
            times(1) { event<WorldEvent.BytesStored>() }
            never<WorldEvent.ManifestPublished> { "LIVE" in it.withdrawn }
            // …so the union hides it: the declared role with no stored byte — the declaration of BOTH roles, which is
            // what the backend measures arrival against — keeps the asset incomplete.
            never<WorldEvent.UnionChanged>()
        }

        // The remaining slot frees up and the second resource lands.
        lateinit var second: Seen<WorldEvent.BytesStored>
        lateinit var served: Seen<WorldEvent.UnionChanged>
        whenever {
            uploadQueue.jobLimit(8)
            cycle()
            uploadQueue.completeJob()
            cycle()
        }
        then {
            second = event<WorldEvent.BytesStored> { it.asset == "LIVE" }
            times(1) { event<WorldEvent.BytesStored>() }
            // And it is served WHOLE — never as the still-only asset the old projection offered.
            served = event<WorldEvent.UnionChanged> { it.asset == "LIVE" }
        }
        assertEquals(bothRoles, setOf(landed.value.role, second.value.role), "both roles' bytes landed")
        assertTrue(served.value.gained, "the union serves the asset once both are in")
    }

    @Test
    fun a_discovered_asset_is_declared_before_any_byte_moves() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            photos("A")
            // No slots at all: the cycle records what it discovered and publishes, but creates no job.
            step("no slot", act = { uploadQueue.jobLimit(0) }, until = {})
        }

        // The declaration is complete even though nothing uploaded.
        whenever { cycle() }
        then {
            event<WorldEvent.ManifestPublished> { "A" in it.added } // declared on the strength of discovery alone
            never<WorldEvent.UploadJobCreated>()
            never<WorldEvent.BytesStored>() // with no byte uploaded
            never<WorldEvent.UnionChanged>() // and invisible to every other member until its bytes arrive
        }
    }
}
