package app.snapsync.model

import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.CaptureRangeVerdict
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.sharing.SelectionInputs
import app.snapsync.domain.sharing.SelectionPolicy
import app.snapsync.domain.upload.LedgerState
import app.snapsync.format.TimestampText
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

    /** A member sharing the event's whole week, which every [row] lies in, with nothing received or denied. */
    private fun admittingAll(): SelectionPolicy {
        val start = EventStart(TimestampText.parse("2026-06-01T00:00:00Z"))
        val end = EventEnd(TimestampText.parse("2026-06-07T00:00:00Z"))
        val range = (
            CaptureRange.of(
                CaptureCutoff(start.at),
                CaptureCeiling(end.at),
                start,
                end,
            ) as CaptureRangeVerdict.Ok
            )
            .range
        return SelectionPolicy.of(SelectionInputs(Direction.Both, range, emptySet(), emptySet()))
    }

    @Test
    fun `assets are listed by id and their resources by key whatever order the rows come in`() = runTest {
        val rows = listOf(row("B", "B-primary.jpg"), row("C", "C-primary.jpg"), row("A", "A-primary.jpg"))

        val shuffled = projectDeviceManifest(testDeviceId("D"), rows, admittingAll())
        val sorted = projectDeviceManifest(testDeviceId("D"), rows.sortedBy { it.key }, admittingAll())

        assertEquals(listOf("A", "B", "C"), shuffled.assets.map { it.assetId.value })
        assertEquals(sorted.encodeToJson(), shuffled.encodeToJson())
    }
}
