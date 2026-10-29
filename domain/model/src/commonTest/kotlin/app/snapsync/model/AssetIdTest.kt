package app.snapsync.model

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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Why an asset id is a type, at its consumers: the two id-matching selection rules once went silently inert on device
 * because the facts reader handed a raw PhotoKit `localIdentifier` to sets holding the converted form. A ledger row's
 * id and the echo and album sets match on the one canonical form (the rule itself is `AssetIdTest` in the domain
 * layer).
 */
class AssetIdTest {

    private val row = LedgerEntry(
        key = "$CANONICAL-primary.heic",
        assetId = testAssetId(CANONICAL),
        state = LedgerState.COMPLETED,
        creationDate = "2026-07-21T07:46:58Z",
    )

    private fun policy(received: Boolean, denied: Boolean): SelectionPolicy {
        val start = EventStart(TimestampText.parse("2026-07-20T00:00:00Z"))
        val end = EventEnd(TimestampText.parse("2026-07-27T00:00:00Z"))
        val range = (
            CaptureRange.of(
                CaptureCutoff(start.at),
                CaptureCeiling(end.at),
                start,
                end,
            ) as CaptureRangeVerdict.Ok
            )
            .range
        val id = setOf(testAssetId(CANONICAL))
        return SelectionPolicy.of(
            SelectionInputs(
                Direction.Both,
                range,
                received = if (received) id else emptySet(),
                inDeniedAlbums = if (denied) id else emptySet(),
            ),
        )
    }

    @Test
    fun `the echo and album rules match on the one form`() {
        assertEquals(
            setOf(testAssetId(CANONICAL)),
            admittedAssetIds(listOf(row), policy(received = false, denied = false)),
        )
        assertEquals(emptySet(), admittedAssetIds(listOf(row), policy(received = true, denied = false)), "echo")
        assertEquals(emptySet(), admittedAssetIds(listOf(row), policy(received = false, denied = true)), "album")
    }

    private companion object {
        const val CANONICAL = "5C33E0C1-4E39-4EE0-891F-BAFB943BC168_L0_001"
    }
}
