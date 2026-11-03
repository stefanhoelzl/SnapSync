package app.snapsync.data.upload

import app.snapsync.domain.upload.UploadRow
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.Transfer
import app.snapsync.ports.TransferFailure
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * How the app's own session transfers end: each end recorded on its row as the session reports it — a landing settles
 * the row and releases its file, a failure returns the row and keeps the file to resume — and a leave cancels every
 * live transfer with its file. Then the sweep of files no transfer outlived.
 */
class SessionEndsTest {

    private suspend fun SessionWorld.sent(asset: String = "A1"): String {
        joined()
        tier.create(event, discovered(asset), SessionWorld.From)
        return tag(asset)
    }

    @Test
    fun `a landing settles its row and releases its file`() = runTest {
        val world = SessionWorld()
        val tag = world.sent()

        val answer = world.ends.finished(world.event, SessionEnd(tag, null))

        assertEquals(RowTransition.Transitioned, assertIs<FinishedAnswer.Recorded>(answer).recorded)
        assertIs<UploadRow.Completed>(world.row())
        assertFalse(world.staged.path(tag) in world.files.held)
        assertFalse(world.staged.digestPath(tag) in world.files.held)
    }

    @Test
    fun `a failure returns its row to needing a job and keeps its file to resume`() = runTest {
        val world = SessionWorld()
        val tag = world.sent()

        world.ends.finished(world.event, SessionEnd(tag, TransferFailure("timed out")))

        assertIs<UploadRow.Discovered>(world.row())
        assertTrue(world.staged.path(tag) in world.files.held)
        assertTrue(world.staged.digestPath(tag) in world.files.held)
    }

    @Test
    fun `a transfer whose tag spells no key is no row's, and its file still goes`() = runTest {
        val world = SessionWorld()
        world.files.held[world.staged.path("odd")] = ByteArray(1)
        world.files.undeletable += world.staged.path("stuck")
        world.files.held[world.staged.path("stuck")] = ByteArray(1)

        assertEquals(FinishedAnswer.Unknown("odd"), world.ends.finished(world.event, SessionEnd("odd", null)))
        world.ends.finished(world.event, SessionEnd("stuck", null))

        assertFalse(world.staged.path("odd") in world.files.held)
        assertTrue(world.staged.path("stuck") in world.files.held, "a file the area refuses to delete stays, logged")
    }

    @Test
    fun `an end after a leave belongs to no row, and its file still goes`() = runTest {
        val world = SessionWorld()
        val tag = world.sent()

        assertEquals(FinishedAnswer.Unjoined, world.ends.finished(null, SessionEnd(tag, null)))

        assertFalse(world.staged.path(tag) in world.files.held)
        assertIs<UploadRow.Requested>(world.row())
    }

    @Test
    fun `a leave cancels every live transfer and its file, touching no row`() = runTest {
        val world = SessionWorld()
        val tag = world.sent()
        val live = listOf(Transfer(RecordingSession.Handle, tag), Transfer(RecordingSession.Handle, null))
        world.session.live = live

        world.ends.cancelAll()

        assertEquals(live, world.session.cancelled)
        assertFalse(world.staged.path(tag) in world.files.held)
        assertIs<UploadRow.Requested>(world.row())
    }

    @Test
    fun `the sweep releases what no transfer sends and no row needs, and keeps a failed attempt's file`() = runTest {
        val world = SessionWorld()
        world.session.answer = CreateOutcome.FAILED
        val failed = world.sent("A1")
        world.session.answer = CreateOutcome.CREATED
        val sending = world.sent("A2")
        world.ends.finished(world.event, SessionEnd(world.sent("A3"), null))
        val landed = world.tag("A3")
        world.files.held[world.staged.path(landed)] = ByteArray(1)
        world.files.held[world.staged.path("orphan.plain")] = ByteArray(1)
        world.session.live = listOf(Transfer(RecordingSession.Handle, sending))

        world.sweep.releaseUnclaimed(world.event)

        assertTrue(world.staged.path(failed) in world.files.held, "its row still needs the upload")
        assertTrue(world.staged.path(sending) in world.files.held, "a live transfer sends it")
        assertTrue(world.staged.digestPath(sending) in world.files.held)
        assertFalse(world.staged.path(landed) in world.files.held, "its row is settled")
        assertFalse(world.staged.path("orphan.plain") in world.files.held, "no row is its")
    }

    @Test
    fun `a sweep that cannot read the files or the rows, or delete one, releases nothing it cannot`() = runTest {
        val world = SessionWorld()
        world.files.held[world.staged.path("orphan")] = ByteArray(1)

        world.files.unlistable = true
        world.sweep.releaseUnclaimed(world.event)
        world.files.unlistable = false

        world.databases.lock()
        world.sweep.releaseUnclaimed(world.event)
        world.databases.lock(on = false)
        assertTrue(world.staged.path("orphan") in world.files.held)

        world.files.undeletable += world.staged.path("orphan")
        world.sweep.releaseUnclaimed(world.event)
        assertTrue(world.staged.path("orphan") in world.files.held)
    }
}
