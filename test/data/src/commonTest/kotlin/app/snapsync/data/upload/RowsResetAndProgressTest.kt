package app.snapsync.data.upload

import app.snapsync.data.membership.sharedOver
import app.snapsync.data.store.StoreWrite
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.upload.AssetProgress
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
import app.snapsync.levers.WriteKind
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
    fun `a purge returns the kept event's in-flight rows to needing a job, keeps its completed ones, moves no version`() {
        walk.discover(other, listOf(FoundResource(key("OLD"), RowDetail(CaptureDate.Undated, "image/heic", "x"))))
        walk.discover(
            event,
            listOf(
                FoundResource(key("SENDING"), RowDetail(CaptureDate.Undated, "image/heic", "x")),
                FoundResource(key("DONE"), RowDetail(CaptureDate.Undated, "image/heic", "x")),
            ),
        )
        moves.requested(event, CreatedJob(key("SENDING")), DestinationPath("/sending"))
        moves.requested(event, CreatedJob(key("DONE")), DestinationPath("/done"))
        moves.landed(event, ReportedLanding(key("DONE")))
        val before = assertIs<VersionRead.Read>(reads.manifestVersion()).version.value

        assertEquals(StoreWrite.Written, reset.purgeExcept(event))

        val rows = rowsOf(event).associateBy { it.key }
        assertIs<UploadRow.Discovered>(rows.getValue(key("SENDING")), "the stranded upload needs a job again")
        assertIs<UploadRow.Completed>(rows.getValue(key("DONE")), "what was stored stays stored")
        assertTrue(rowsOf(other).isEmpty(), "another event's rows are purged")
        assertEquals(
            before + 1,
            assertIs<VersionRead.Read>(reads.manifestVersion()).version.value,
            "only the purge's delete",
        )
    }

    @Test
    fun `a purge whose write fails leaves the store as it was`() {
        walk.discover(other, listOf(FoundResource(key("OLD"), RowDetail(CaptureDate.Undated, "image/heic", "x"))))
        walk.discover(event, listOf(FoundResource(key("SENDING"), RowDetail(CaptureDate.Undated, "image/heic", "x"))))
        moves.requested(event, CreatedJob(key("SENDING")), DestinationPath("/sending"))
        device.fail("ledger.db", "ledgerRow", WriteKind.UPDATE)

        assertIs<StoreWrite.Failed>(reset.purgeExcept(event))

        assertEquals(listOf(key("OLD")), rowsOf(other).map { it.key }, "the purge rolled back with the requeue")
        val kept = assertIs<UploadRow.Requested>(rowsOf(event).single())
        assertEquals(DestinationPath("/sending"), kept.destination)
    }

    @Test
    fun `progress is per photo - done once every row is, else handed over while a job carries one, else waiting`() {
        assertEquals(emptyMap(), assertIs<ProgressRead.Read>(progress.progress(event)).photos)

        reset.seed(event, listOf(StoredBytes(key("DONE"))))
        walk.discover(
            event,
            listOf(
                FoundResource(key("LIVE"), RowDetail(CaptureDate.Undated, "image/heic", "x")),
                FoundResource(key("LIVE", ResourceRole.LIVE), RowDetail(CaptureDate.Undated, "video/quicktime", "x")),
                FoundResource(key("SENDING"), RowDetail(CaptureDate.Undated, "image/heic", "x")),
            ),
        )
        moves.requested(event, CreatedJob(key("LIVE")), DestinationPath("/p"))
        moves.landed(event, ReportedLanding(key("LIVE")))
        moves.requested(event, CreatedJob(key("SENDING")), DestinationPath("/s"))

        assertEquals(
            mapOf(
                AssetId.parse("DONE")!! to AssetProgress.DONE,
                AssetId.parse("LIVE")!! to AssetProgress.WAITING,
                AssetId.parse("SENDING")!! to AssetProgress.HANDED_OVER,
            ),
            assertIs<ProgressRead.Read>(progress.progress(event)).photos,
            "a Live Photo whose video still waits for a job is waiting",
        )
    }

    @Test
    fun `a ledger that cannot be read or written says so, never a throw`() {
        device.lock()

        assertIs<StoreWrite.Failed>(reset.seed(event, listOf(StoredBytes(key("A")))))
        assertIs<StoreWrite.Failed>(reset.purgeExcept(event))
        assertIs<ProgressRead.Unavailable>(progress.progress(event))
    }
}
