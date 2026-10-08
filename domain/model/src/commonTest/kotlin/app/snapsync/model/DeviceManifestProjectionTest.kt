package app.snapsync.model

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The manifest a device publishes is a deterministic snapshot of its ledger: assets in asset-id order and each asset's
 * resources in key order, whatever order the rows arrive in — so the producer's skip-if-unchanged comparison sees
 * one manifest for one ledger (capability `photo-sharing`).
 */
class DeviceManifestProjectionTest {

    private fun row(id: String, key: String) = LedgerEntry(
        key = key,
        assetId = AssetId(id),
        state = LedgerState.COMPLETED,
        creationDate = "2026-06-01T10:00:00Z",
        role = ResourceRole.PRIMARY,
        contentType = "image/jpeg",
        originalFilename = "IMG_$id.JPG",
    )

    private suspend fun admittingAll() = SelectionPolicy(
        selectionRulesFor(
            includesUpload = true,
            cutoff = captureCutoff("2026-01-01T00:00:00Z"),
            ceiling = null,
            suppressedAssetIds = { emptySet() },
            albumExcludedAssetIds = { emptySet() },
        ),
    )

    @Test
    fun `assets are listed by id and their resources by key whatever order the rows come in`() = runTest {
        val rows = listOf(row("B", "B-primary.jpg"), row("C", "C-primary.jpg"), row("A", "A-primary.jpg"))

        val shuffled = projectDeviceManifest("D", rows, admittingAll())
        val sorted = projectDeviceManifest("D", rows.sortedBy { it.key }, admittingAll())

        assertEquals(listOf("A", "B", "C"), shuffled.assets.map { it.assetId.value })
        assertEquals(sorted.encodeToJson(), shuffled.encodeToJson())
    }
}
