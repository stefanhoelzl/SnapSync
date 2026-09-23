package app.snapsync.integration

import app.snapsync.model.ResourceRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The manifest declares intent** (capability `device-manifest`), over the real stack.
 *
 * The unit tests assert the projection over a list of rows. This asserts the consequence the change exists for,
 * through the composed core the device shells actually run and the faithful mini-edge, driven through the control
 * protocol: a device declares what it will provide, and the backend — not the device — is what keeps a
 * half-uploaded asset out of the event union until every declared role has arrived.
 *
 * The shape is the one that produced the defect this closes. A Live Photo is two resources, and they can complete
 * in different cycles. With a manifest that listed only COMPLETED rows, the asset was declared with `primary` alone
 * in between, so the union served it as a complete one-resource asset — and a recipient reconciling in that window
 * imported it as a plain still, marked the asset settled, and never took the video, because a recipient plans per
 * ASSET (capability `photo-download`).
 */
class DeclaredIntentIntegrationTest {

    private val bothRoles = setOf(ResourceRole.LIVE.wire, ResourceRole.PRIMARY.wire)

    @Test
    fun a_live_photo_is_declared_whole_and_stays_hidden_until_it_is_whole() = rigTest {
        val event = createAndJoin()
        addPhoto("LIVE", kind = "live-photo")

        // One slot: the cycle can start exactly one of the two resources, so the asset is genuinely half-uploaded
        // when the manifest is published — the state the old projection mis-described.
        device("jobs/limit", "n" to "1")
        cycle()
        // The operator plays the OS: the one job that was created lands. The second resource never got a slot, so
        // the asset is genuinely half-uploaded.
        val first = jobs().live.single()
        completeJobs(first)
        cycle() // settle the terminal and publish

        val declared = manifest(event).orEmpty()
        assertEquals(setOf("LIVE"), declared.keys, "the asset is declared even though it is not fully uploaded")
        assertEquals(
            bothRoles,
            declared.getValue("LIVE"),
            "BOTH roles are declared — that declaration is what the backend measures arrival against",
        )
        assertEquals(setOf(first), objects(), "and only one resource's bytes have actually landed")
        assertTrue(
            "LIVE" !in union(event),
            "so the union hides it: a declared role with no stored byte makes the asset incomplete",
        )

        // The remaining slot frees up and the second resource lands.
        device("jobs/limit", "n" to "8")
        cycle()
        completeJobs()
        cycle() // settle the terminal and publish

        assertEquals(2, objects().size)
        assertEquals(
            bothRoles,
            union(event)["LIVE"],
            "and it is served WHOLE — never as the still-only asset the old projection offered",
        )
    }

    @Test
    fun a_discovered_asset_is_declared_before_any_byte_moves() = rigTest {
        val event = createAndJoin()
        addPhoto("A")

        // No slots at all: the cycle records what it discovered and publishes, but creates no job. The declaration
        // is complete even though nothing uploaded.
        device("jobs/limit", "n" to "0")
        cycle()

        assertEquals(setOf("A"), manifest(event)?.keys, "declared on the strength of discovery alone")
        assertEquals(0, jobs().created)
        assertTrue(objects().isEmpty(), "with no byte uploaded")
        assertTrue(union(event).isEmpty(), "and invisible to every other member until its bytes arrive")
    }
}
