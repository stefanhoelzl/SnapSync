package app.snapsync.model

import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.upload.LedgerState
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
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
        assetId = testAssetId(id),
        state = LedgerState.COMPLETED,
        creationDate = "2026-06-01T10:00:00Z",
        role = ResourceRole.PRIMARY,
        contentType = "image/jpeg",
        originalFilename = "IMG_$id.JPG",
    )

    @Test
    fun `assets are listed by id and their resources by key whatever order the rows come in`() = runTest {
        val rows = listOf(row("B", "B-primary.jpg"), row("C", "C-primary.jpg"), row("A", "A-primary.jpg"))
        val admitted = rows.mapTo(mutableSetOf()) { it.assetId }

        val shuffled = projectDeviceManifest(testDeviceId("D"), rows, admitted)
        val sorted = projectDeviceManifest(testDeviceId("D"), rows.sortedBy { it.key }, admitted)

        assertEquals(listOf("A", "B", "C"), shuffled.assets.map { it.assetId.value })
        assertEquals(sorted.encodeToJson(), shuffled.encodeToJson())
    }
}
