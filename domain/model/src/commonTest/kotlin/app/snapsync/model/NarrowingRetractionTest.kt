package app.snapsync.model

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A narrowing scope change retracts the member's listings** (capability `reconfigure-membership`).
 *
 * These began as pinning tests for the opposite claim. `reconfigure-membership` used to state that a
 * narrowing change — raising the cutoff, or turning a direction off — did **not** retract photos already
 * shared, and `ReconfigureEvent` carried a comment saying a raised cutoff "un-shares nothing". Both were
 * false: [projectDeviceManifest] re-filters already-`COMPLETED` rows through the *current* policy, so a
 * narrowing has always dropped them from the published manifest. The tests were written to fail, and did.
 *
 * The resolution was to change the **spec**, not the behaviour: the manifest answers *what does this member
 * share now?*, so a narrowing SHOULD retract there. What changed underneath is that the retraction is now
 * confined to the listing — the ledger rows survive (capability `sync-ledger`), so widening again restores
 * the listing without re-uploading a byte, and the drain requirement keeps its meaning.
 *
 * The retraction is **partial by nature**: a member who already downloaded the photo holds it in their own
 * library, and no manifest change reaches it.
 */
class NarrowingRetractionTest {

    private fun completedRow(id: String, capturedAt: String) = LedgerEntry(
        key = "$id-primary.jpg",
        assetId = id,
        state = LedgerState.COMPLETED,
        attempt = 0,
        eventId = "E",
        creationDate = capturedAt,
        role = ResourceRole.PRIMARY,
        contentType = "image/jpeg",
        originalFilename = "IMG_$id.JPG",
    )

    private suspend fun policyWithFloor(floor: String): SelectionPolicy =
        SelectionPolicy(selectionRulesFor(includesUpload = true, cutoff = captureCutoff(floor), ceiling = null, suppressedAssetIds = { emptySet() }, albumExcludedAssetIds = { emptySet() }))

    @Test
    fun `raising the cutoff past a shared photo stops listing it`() = runTest {
        val uploaded = completedRow("A", capturedAt = "2026-06-01T10:00:00Z")

        val before = projectDeviceManifest("D", listOf(uploaded), policyWithFloor("2026-01-01T00:00:00Z"))
        assertEquals(listOf("A"), before.assets.map { it.assetId }, "shared under the original floor")

        val after = projectDeviceManifest("D", listOf(uploaded), policyWithFloor("2026-07-01T00:00:00Z"))

        assertTrue(
            after.assets.isEmpty(),
            "the member narrowed their scope past this photo, so it is no longer listed to the event",
        )
    }

    @Test
    fun `turning the direction off stops listing everything`() = runTest {
        val uploaded = completedRow("A", capturedAt = "2026-06-01T10:00:00Z")

        val after = projectDeviceManifest(
            "D",
            listOf(uploaded),
            SelectionPolicy(selectionRulesFor(
                includesUpload = false,
                cutoff = captureCutoff("2026-01-01T00:00:00Z"),
                ceiling = null, suppressedAssetIds = { emptySet() }, albumExcludedAssetIds = { emptySet() })),
        )

        assertTrue(after.assets.isEmpty(), "a membership that shares nothing publishes an empty manifest")
    }

    @Test
    fun `widening again re-lists the same photo`() = runTest {
        // The half the ledger change buys: the row was never pruned, so this costs no upload.
        val uploaded = completedRow("A", capturedAt = "2026-06-01T10:00:00Z")

        val narrowed = projectDeviceManifest("D", listOf(uploaded), policyWithFloor("2026-07-01T00:00:00Z"))
        assertTrue(narrowed.assets.isEmpty())

        val widened = projectDeviceManifest("D", listOf(uploaded), policyWithFloor("2026-01-01T00:00:00Z"))

        assertEquals(
            listOf("A"), widened.assets.map { it.assetId },
            "the ledger row survived the narrowing, so widening re-lists it with no re-upload",
        )
    }
}
