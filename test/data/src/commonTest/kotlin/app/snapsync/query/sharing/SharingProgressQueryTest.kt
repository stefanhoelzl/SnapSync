@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.query.sharing

import app.snapsync.application.receiving.Receiver
import app.snapsync.data.foreground.AppForeground
import app.snapsync.data.membership.sharedOver
import app.snapsync.data.sharing.AdmittedPhotos
import app.snapsync.data.store.ChangedElsewhere
import app.snapsync.data.store.LEDGER_DB_NAME
import app.snapsync.data.upload.RowTransitions
import app.snapsync.data.upload.UploadProgress
import app.snapsync.data.upload.UploadRowsReset
import app.snapsync.data.upload.WalkRecord
import app.snapsync.data.walkClock
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.sharing.SharingProgress
import app.snapsync.domain.sharing.SharingProgressRead
import app.snapsync.domain.upload.CreatedJob
import app.snapsync.domain.upload.DestinationPath
import app.snapsync.domain.upload.FoundResource
import app.snapsync.domain.upload.MintsCreatedJob
import app.snapsync.domain.upload.MintsStoredBytes
import app.snapsync.domain.upload.RowDetail
import app.snapsync.domain.upload.StoredBytes
import app.snapsync.domain.upload.UploadKey
import app.snapsync.mock.ClockMock
import app.snapsync.testids.testAssetId
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How far the member's own photos are shared (capability `sync-status`): the upload rows read against the admitted
 * total, re-read each time the shared database changes — this process's commit or the upload extension's — only while
 * the app is in front; never "read" before both halves are; never back to unread; a refused read keeps the last.
 */
@OptIn(MintsStoredBytes::class, MintsCreatedJob::class)
class SharingProgressQueryTest {

    private val r = Receiver()
    private val foreground = AppForeground(ClockMock().port())
    private val admitted = AdmittedPhotos()
    private val walk = WalkRecord(r.device.shared, walkClock)
    private val reset = UploadRowsReset(r.device.shared)
    private val moves = RowTransitions(r.device.shared)

    private fun key(asset: String) = UploadKey.of(testAssetId(asset), ResourceRole.PRIMARY, "IMG.HEIC")

    private fun ids(vararg names: String): Set<AssetId> = names.mapTo(mutableSetOf(), ::testAssetId)

    private fun TestScope.query() = SharingProgressQuery(
        foreground,
        r.device.shared.changes,
        r.device.membership,
        UploadProgress(r.device.shared),
        admitted,
        backgroundScope,
    )

    /** The query, followed as a screen follows it, the app in front. */
    private fun TestScope.followed(): SharingProgressQuery = query().also {
        it.state.launchIn(backgroundScope)
        foreground.entered()
        runCurrent()
    }

    /** The joined event, entered on first use. */
    private val event by lazy { r.joined().event.id }

    private fun found(vararg assets: String) =
        assets.map { FoundResource(key(it), RowDetail(CaptureDate.Undated, "image/heic", "IMG.HEIC")) }

    /** [assets] shared: settled rows, as the join's seed writes them. */
    private fun shared(vararg assets: String) = reset.seed(event, assets.map { StoredBytes(key(it)) })

    /** [assets] found by a walk and still to share. */
    private fun discovered(vararg assets: String) = walk.discover(event, found(*assets))

    /** [assets] found and handed over: a job carries each. */
    private fun handedOver(vararg assets: String) = handedOver(walk, moves, *assets)

    private fun handedOver(through: WalkRecord, by: RowTransitions, vararg assets: String) {
        through.discover(event, found(*assets))
        assets.forEach { by.requested(event, CreatedJob(key(it)), DestinationPath("/$it")) }
    }

    private fun read(shared: Int, total: Int, handedOver: Int) =
        SharingProgressRead.Read(SharingProgress(shared, total, handedOver))

    @Test
    fun `nothing is read before a screen follows it`() = runTest {
        shared("A")
        admitted.counted(ids("A"))
        val unfollowed = query()
        foreground.entered()
        runCurrent()
        assertEquals(SharingProgressRead.NotYetRead, unfollowed.state.value)
    }

    @Test
    fun `read rows beside an uncounted total are not read yet`() = runTest {
        shared("A")
        val query = followed()
        assertEquals(SharingProgressRead.NotYetRead, query.state.value, "the total is not counted")

        admitted.counted(ids("A"))
        runCurrent()
        assertEquals(read(shared = 1, total = 1, handedOver = 0), query.state.value)
    }

    @Test
    fun `the rows are counted against the admitted total, only a photo a job carries as handed over`() = runTest {
        shared("A")
        handedOver("B")
        discovered("D")
        admitted.counted(ids("A", "B", "C", "D"))

        assertEquals(read(shared = 1, total = 4, handedOver = 1), followed().state.value)
    }

    @Test
    fun `this process's commit is read again at once`() = runTest {
        shared("A")
        admitted.counted(ids("A", "B"))
        val query = followed()
        assertEquals(read(shared = 1, total = 2, handedOver = 0), query.state.value)

        handedOver("B")
        runCurrent()
        assertEquals(read(shared = 1, total = 2, handedOver = 1), query.state.value)
    }

    @Test
    fun `the upload extension's commit, told by the port, is read again`() = runTest {
        shared("A")
        admitted.counted(ids("A", "B"))
        val query = followed()

        // The other process writes through its own view of the database: nothing in this one announces it.
        val elsewhere = sharedOver(r.device.databases)
        handedOver(WalkRecord(elsewhere, walkClock), RowTransitions(elsewhere), "B")
        runCurrent()
        assertEquals(read(shared = 1, total = 2, handedOver = 0), query.state.value, "not yet told")

        ChangedElsewhere(r.device.shared, r.file).changed(LEDGER_DB_NAME)
        runCurrent()
        assertEquals(read(shared = 1, total = 2, handedOver = 1), query.state.value)
    }

    @Test
    fun `nothing is read while the app is away, and its return reads afresh`() = runTest {
        shared("A")
        admitted.counted(ids("A", "B"))
        val query = followed()
        foreground.left()
        runCurrent()

        handedOver("B")
        runCurrent()
        assertEquals(read(shared = 1, total = 2, handedOver = 0), query.state.value, "away: not read")

        foreground.entered()
        runCurrent()
        assertEquals(read(shared = 1, total = 2, handedOver = 1), query.state.value)
    }

    @Test
    fun `a refused read keeps the last, and nothing goes back to unread`() = runTest {
        shared("A")
        admitted.counted(ids("A"))
        val query = followed()
        val good = query.state.value

        r.device.databases.unreadable(LEDGER_DB_NAME, on = true)
        r.device.shared.changes.announce()
        runCurrent()
        assertEquals(good, query.state.value)
    }

    @Test
    fun `no membership counts a zero, not an unread`() = runTest {
        admitted.counted(emptySet())
        assertEquals(read(shared = 0, total = 0, handedOver = 0), followed().state.value)
    }

    @Test
    fun `a membership that cannot be read counts no one's rows`() = runTest {
        r.device.databases.lock()
        admitted.counted(emptySet())
        assertEquals(read(shared = 0, total = 0, handedOver = 0), followed().state.value)
    }
}
