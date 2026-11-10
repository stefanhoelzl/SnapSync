package app.snapsync.data.upload

import app.snapsync.data.membership.sharedOver
import app.snapsync.data.store.StoreWrite
import app.snapsync.data.store.Stored
import app.snapsync.data.store.use
import app.snapsync.data.walkClock
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.upload.CreatedJob
import app.snapsync.domain.upload.DestinationPath
import app.snapsync.domain.upload.FoundResource
import app.snapsync.domain.upload.LedgerState
import app.snapsync.domain.upload.MintsCreatedJob
import app.snapsync.domain.upload.MintsEndedJob
import app.snapsync.domain.upload.ReportedFailure
import app.snapsync.domain.upload.ReportedLanding
import app.snapsync.domain.upload.RowDetail
import app.snapsync.domain.upload.UploadKey
import app.snapsync.domain.upload.UploadRow
import app.snapsync.format.TimestampText
import app.snapsync.format.UploadKeyText
import app.snapsync.mock.DatabasesMock
import app.snapsync.testids.testEventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * This device's own upload rows over the real `ledger.db` schema: what each read answers, that a walk's record never
 * replaces a row, and that each transition moves a row only from the state it names — answering what the row is when
 * another writer came first.
 */
@OptIn(MintsCreatedJob::class, MintsEndedJob::class)
class OwnUploadRowsTest {

    private val device = DatabasesMock()
    private val shared = sharedOver(device)
    private val reads = OwnUploadRows(shared)
    private val walk = WalkRecord(shared, walkClock)
    private val moves = RowTransitions(shared)
    private val event = testEventId("E1")
    private val other = testEventId("E2")

    private val captured = CaptureDate.Dated(TimestampText.parse("2026-07-01T10:00:00Z"))
    private val detail = RowDetail(captured, "image/heic", "IMG_0001.HEIC")

    private fun key(asset: String, role: ResourceRole = ResourceRole.PRIMARY) =
        UploadKey.of(AssetId.parse(asset)!!, role, "IMG.HEIC")

    private fun found(asset: String, role: ResourceRole = ResourceRole.PRIMARY, with: RowDetail = detail) =
        FoundResource(key(asset, role), with)

    private fun all(): List<UploadRow> = assertIs<RowsRead.Read>(reads.rows(event)).rows

    private fun only(asset: String): UploadRow = all().single { it.key == key(asset) }

    private val to = DestinationPath("/files/events/E1/devices/D/A/primary")

    @Test
    fun `a find keeps when it was first found and by which entry point, and a later walk changes neither`() {
        walk.discover(event, listOf(found("A")), "OnForeground")
        walk.discover(event, listOf(found("A")), "OnScheduledWake")

        val discovered = assertNotNull(only("A").detail.discovered)
        assertEquals("2026-07-14T12:00:00Z", TimestampText.format(discovered.at))
        assertEquals("OnForeground", discovered.by)
        walk.discover(event, listOf(found("B")))
        assertEquals(null, assertNotNull(only("B").detail.discovered).by, "a walk off an entry point names none")
    }

    @Test
    fun `a row recorded before discoveries were kept, or holding one that does not read, has none`() {
        for ((asset, foundAt) in listOf("A" to null, "B" to "yesterday")) {
            shared.execute {
                it.ledgerQueries.recordDiscovered(
                    event.value,
                    UploadKeyText.of(key(asset)),
                    Stored.Valid(key(asset).asset),
                    LedgerState.DISCOVERED,
                    "2026-07-01T10:00:00Z",
                    "primary",
                    "image/heic",
                    "IMG.HEIC",
                    foundAt,
                    "OnForeground",
                )
            }
        }

        assertNull(only("A").detail.discovered)
        assertNull(only("B").detail.discovered)
    }

    @Test
    fun `a walk's finds are recorded as needing a job, with their detail, in key order`() {
        assertEquals(
            StoreWrite.Written,
            walk.discover(event, listOf(found("B"), found("A"), found("A", ResourceRole.LIVE))),
        )

        val read = all()
        assertEquals(
            listOf(key("A"), key("A", ResourceRole.LIVE), key("B")).map(UploadKeyText::of).sorted(),
            read.map { UploadKeyText.of(it.key) },
        )
        read.forEach { assertIs<UploadRow.Discovered>(it) }
        assertEquals(captured, only("A").detail.captured)
        assertEquals("image/heic", only("A").detail.contentType)
        assertEquals("IMG_0001.HEIC", only("A").detail.originalFilename)
        assertEquals(read.map { it.key }, assertIs<RowsRead.Read>(reads.needingJob(event)).rows.map { it.key })
    }

    @Test
    fun `a walk never replaces a row it finds again - whatever that row's state`() {
        walk.discover(event, listOf(found("A"), found("B")))
        moves.requested(event, CreatedJob(key("A")), to)
        moves.requested(event, CreatedJob(key("B")), to)
        moves.landed(event, ReportedLanding(key("B")))

        walk.discover(event, listOf(found("A", with = RowDetail(CaptureDate.Undated, "", "")), found("B")))

        assertIs<UploadRow.Requested>(only("A"))
        assertEquals(captured, only("A").detail.captured)
        assertIs<UploadRow.Completed>(only("B"))
    }

    @Test
    fun `a job the platform accepted makes its row requested, addressed where the job is`() {
        walk.discover(event, listOf(found("A")))

        assertEquals(RowTransition.Transitioned, moves.requested(event, CreatedJob(key("A")), to))

        val requested = assertIs<UploadRow.Requested>(only("A"))
        assertEquals(to, requested.destination)
        assertEquals(CountRead.Counted(1), reads.inFlight(event))
        assertEquals(emptyList(), assertIs<RowsRead.Read>(reads.needingJob(event)).rows)
    }

    @Test
    fun `a second process's job for a row already requested moves nothing and reads the row as it is`() {
        walk.discover(event, listOf(found("A")))
        moves.requested(event, CreatedJob(key("A")), to)

        val second = assertIs<RowTransition.Superseded>(
            moves.requested(event, CreatedJob(key("A")), DestinationPath("/x")),
        )

        assertEquals(to, assertIs<UploadRow.Requested>(second.now).destination)
    }

    @Test
    fun `a failure returns a requested row to needing a job, and a landing settles it`() {
        walk.discover(event, listOf(found("A"), found("B")))
        moves.requested(event, CreatedJob(key("A")), to)
        moves.requested(event, CreatedJob(key("B")), to)

        assertEquals(RowTransition.Transitioned, moves.returned(event, ReportedFailure(key("A"))))
        assertEquals(RowTransition.Transitioned, moves.landed(event, ReportedLanding(key("B"))))

        assertIs<UploadRow.Discovered>(only("A"))
        assertIs<UploadRow.Completed>(only("B"))
        assertEquals(CountRead.Counted(0), reads.inFlight(event))
    }

    @Test
    fun `an end reported for a row that is not requested moves nothing - a settled row stays settled`() {
        walk.discover(event, listOf(found("A"), found("B")))
        moves.requested(event, CreatedJob(key("A")), to)
        moves.landed(event, ReportedLanding(key("A")))

        assertIs<UploadRow.Completed>(
            assertIs<RowTransition.Superseded>(moves.returned(event, ReportedFailure(key("A")))).now,
        )
        assertIs<UploadRow.Discovered>(
            assertIs<RowTransition.Superseded>(moves.landed(event, ReportedLanding(key("B")))).now,
        )
    }

    @Test
    fun `a transition for a row that is gone says so`() {
        assertEquals(RowTransition.Gone, moves.requested(event, CreatedJob(key("A")), to))
        assertEquals(RowTransition.Gone, moves.returned(event, ReportedFailure(key("A"))))
        assertEquals(RowTransition.Gone, moves.landed(event, ReportedLanding(key("A"))))
    }

    @Test
    fun `withdrawn rows are deleted by key, whatever their state, and no other`() {
        walk.discover(event, listOf(found("A"), found("A", ResourceRole.LIVE), found("B")))
        moves.requested(event, CreatedJob(key("A", ResourceRole.LIVE)), to)

        assertEquals(StoreWrite.Written, walk.withdraw(event, listOf(key("A", ResourceRole.LIVE), key("B"))))

        assertEquals(listOf(key("A")), all().map { it.key })
    }

    @Test
    fun `a withdrawal names more keys than one statement binds`() {
        val many = (1..1_200).map { found("A$it") }
        walk.discover(event, many)

        walk.withdraw(event, many.map { it.key }.drop(1))

        assertEquals(listOf(many.first().key), all().map { it.key })
    }

    @Test
    fun `a bare row's detail is filled once, and a filled row is never rewritten`() {
        walk.discover(event, listOf(found("A", with = RowDetail(CaptureDate.Undated, "", "")), found("B")))
        assertTrue(only("A").detail.bare)

        walk.backfill(event, listOf(found("A"), found("B", with = RowDetail(CaptureDate.Undated, "x/y", "z"))))

        assertEquals(detail.captured, only("A").detail.captured)
        assertEquals("image/heic", only("A").detail.contentType)
        assertEquals("image/heic", only("B").detail.contentType)
    }

    @Test
    fun `every read and write is scoped to the event it names`() {
        walk.discover(other, listOf(found("A")))
        moves.requested(other, CreatedJob(key("A")), to)

        assertEquals(emptyList(), all())
        assertEquals(CountRead.Counted(0), reads.inFlight(event))
        assertEquals(RowTransition.Gone, moves.landed(event, ReportedLanding(key("A"))))
        assertEquals(CountRead.Counted(1), reads.inFlight(other))
    }

    @Test
    fun `a row recorded before the ledger kept a destination reads as requested to nowhere`() {
        shared.use {
            it.ledgerQueries.insert(
                event.value, UploadKeyText.of(key("A")), Stored.Valid(key("A").asset), LedgerState.REQUESTED,
                "", "primary", "", "", null,
            )
        }
        assertNull(assertIs<UploadRow.Requested>(only("A")).destination)
        assertTrue(only("A").detail.bare)
        assertEquals(RowRead.Absent, reads.byDestination(event, to), "a row with no destination is never matched")
    }

    @Test
    fun `the manifest version moves with what the manifest declares, never with an upload's progress`() {
        fun version() = assertIs<VersionRead.Read>(reads.manifestVersion()).version.value
        val bare = RowDetail(CaptureDate.Undated, "image/heic", "IMG_0001.HEIC")
        walk.discover(event, listOf(found("A", with = bare)))
        val discovered = version()

        walk.discover(event, listOf(found("A", with = bare)))
        assertEquals(discovered, version(), "a walk that finds nothing new")
        moves.requested(event, CreatedJob(key("A")), to)
        moves.returned(event, ReportedFailure(key("A")))
        moves.requested(event, CreatedJob(key("A")), to)
        moves.landed(event, ReportedLanding(key("A")))
        assertEquals(discovered, version(), "an upload's progress declares nothing new")

        walk.backfill(event, listOf(found("A")))
        val filled = version()
        assertTrue(filled > discovered, "a bare row dated is a new declaration")
        walk.withdraw(event, listOf(key("ZZ")))
        assertEquals(filled, version(), "a withdrawal that matched nothing")
        walk.withdraw(event, listOf(key("A")))
        assertTrue(version() > filled, "a withdrawn row")
    }

    @Test
    fun `a row is found by the destination its own request carried, across a route change`() {
        walk.discover(event, listOf(found("A"), found("B")))
        val old = DestinationPath("/files/devices/D/A/primary")
        moves.requested(event, CreatedJob(key("A")), old)
        moves.requested(event, CreatedJob(key("B")), to)

        assertEquals(key("A"), assertIs<RowRead.Found>(reads.byDestination(event, old)).row.key)
        assertEquals(key("B"), assertIs<RowRead.Found>(reads.byDestination(event, to)).row.key)
    }

    @Test
    fun `a row whose key spells none is left out of every read`() {
        walk.discover(event, listOf(found("A")))
        shared.use {
            it.ledgerQueries.insert(
                event.value, "not-a-key", Stored.Empty, LedgerState.DISCOVERED, "", "", "", "", null,
            )
            it.ledgerQueries.insert(
                event.value, "B-still.heic", Stored.Valid(key("B").asset), LedgerState.REQUESTED, "", "", "", "", null,
            )
        }

        assertEquals(listOf(key("A")), all().map { it.key })
        assertEquals(listOf(key("A")), assertIs<RowsRead.Read>(reads.needingJob(event)).rows.map { it.key })
        assertEquals(RowTransition.Gone, moves.landed(event, ReportedLanding(key("B"))))
    }

    @Test
    fun `a ledger that cannot be read or written says so, never a throw`() {
        device.lock()

        assertIs<RowsRead.Unavailable>(reads.rows(event))
        assertIs<RowsRead.Unavailable>(reads.needingJob(event))
        assertIs<CountRead.Unavailable>(reads.inFlight(event))
        assertNull(reads.inFlight(event).count)
        assertIs<StoreWrite.Failed>(walk.discover(event, listOf(found("A"))))
        assertIs<StoreWrite.Failed>(walk.backfill(event, listOf(found("A"))))
        assertIs<StoreWrite.Failed>(walk.withdraw(event, listOf(key("A"))))
        assertIs<RowTransition.Unavailable>(moves.requested(event, CreatedJob(key("A")), to))
    }
}
