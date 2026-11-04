package app.snapsync.data.upload

import app.snapsync.data.membership.sharedOver
import app.snapsync.data.store.StoreWrite
import app.snapsync.data.store.use
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.upload.CreatedJob
import app.snapsync.domain.upload.DestinationPath
import app.snapsync.domain.upload.FoundResource
import app.snapsync.domain.upload.MintsCreatedJob
import app.snapsync.domain.upload.MintsEndedJob
import app.snapsync.domain.upload.MintsStoredBytes
import app.snapsync.domain.upload.ReportedLanding
import app.snapsync.domain.upload.RowDetail
import app.snapsync.domain.upload.StoredBytes
import app.snapsync.domain.upload.UploadKey
import app.snapsync.domain.upload.UploadRow
import app.snapsync.mock.DatabasesMock
import app.snapsync.testids.testEventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The upload rows' reset family — the join's seed and purge — and the per-photo progress the status
 * and the diagnostics read, over the real `ledger.db` schema.
 */
@OptIn(MintsStoredBytes::class, MintsCreatedJob::class, MintsEndedJob::class)
class RowsResetAndProgressTest {

    private val device = DatabasesMock()
    private val shared = sharedOver(device)
    private val reads = OwnUploadRows(shared)
    private val reset = UploadRowsReset(shared)
    private val progress = UploadProgress(shared)
    private val walk = WalkRecord(shared)
    private val moves = RowTransitions(shared)
    private val event = testEventId("E1")
    private val other = testEventId("E2")

    private fun key(asset: String, role: ResourceRole = ResourceRole.PRIMARY) =
        UploadKey.of(AssetId.parse(asset)!!, role, "IMG.HEIC")

    private fun rowsOf(of: app.snapsync.domain.event.EventId): List<UploadRow> =
        assertIs<RowsRead.Read>(reads.rows(of)).rows

    @Test
    fun `a seed makes the listed resources an event's settled bare rows and purges every other event's`() {
        walk.discover(other, listOf(FoundResource(key("OLD"), RowDetail(CaptureDate.Undated, "image/heic", "x"))))
        walk.discover(event, listOf(FoundResource(key("STALE"), RowDetail(CaptureDate.Undated, "image/heic", "x"))))

        assertEquals(
            StoreWrite.Written,
            reset.seed(event, listOf(StoredBytes(key("A")), StoredBytes(key("A", ResourceRole.LIVE)))),
        )

        assertEquals(setOf(key("A"), key("A", ResourceRole.LIVE)), rowsOf(event).mapTo(mutableSetOf()) { it.key })
        assertTrue(rowsOf(event).all { it is UploadRow.Completed && it.detail.bare })
        assertTrue(rowsOf(other).isEmpty(), "another event's rows are purged")
    }

    @Test
    fun `a purge keeps the named event's rows`() {
        walk.discover(other, listOf(FoundResource(key("OLD"), RowDetail(CaptureDate.Undated, "image/heic", "x"))))
        walk.discover(event, listOf(FoundResource(key("KEEP"), RowDetail(CaptureDate.Undated, "image/heic", "x"))))

        assertEquals(StoreWrite.Written, reset.purgeExcept(event))
        assertEquals(listOf(key("KEEP")), rowsOf(event).map { it.key })
        assertTrue(rowsOf(other).isEmpty())

    }

    @Test
    fun `progress is per photo - done only once every one of its rows is, an empty ledger counting none`() {
        assertEquals(emptyMap(), assertIs<ProgressRead.Read>(progress.progress(event)).done)

        reset.seed(event, listOf(StoredBytes(key("DONE"))))
        walk.discover(
            event,
            listOf(
                FoundResource(key("LIVE"), RowDetail(CaptureDate.Undated, "image/heic", "x")),
                FoundResource(key("LIVE", ResourceRole.LIVE), RowDetail(CaptureDate.Undated, "video/quicktime", "x")),
            ),
        )
        moves.requested(event, CreatedJob(key("LIVE")), DestinationPath("/p"))
        moves.landed(event, ReportedLanding(key("LIVE")))

        assertEquals(
            mapOf(AssetId.parse("DONE")!! to true, AssetId.parse("LIVE")!! to false),
            assertIs<ProgressRead.Read>(progress.progress(event)).done,
            "a Live Photo whose video still waits is not done",
        )
    }

    @Test
    fun `a row whose asset spells no id is no photo's progress`() {
        shared.use {
            it.ledgerQueries.insert(
                event.value, "bad-primary.heic", app.snapsync.data.store.Stored.Invalid("bad id!"),
                app.snapsync.domain.upload.LedgerState.COMPLETED, "", "primary", "", "", null,
            )
        }
        assertEquals(emptyMap(), assertIs<ProgressRead.Read>(progress.progress(event)).done)
    }

    @Test
    fun `a ledger that cannot be read or written says so, never a throw`() {
        device.lock()

        assertIs<StoreWrite.Failed>(reset.seed(event, listOf(StoredBytes(key("A")))))
        assertIs<StoreWrite.Failed>(reset.purgeExcept(event))
        assertIs<ProgressRead.Unavailable>(progress.progress(event))
    }
}
