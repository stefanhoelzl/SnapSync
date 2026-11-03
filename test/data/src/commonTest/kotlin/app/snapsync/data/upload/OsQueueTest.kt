package app.snapsync.data.upload

import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.domain.event.Encryption
import app.snapsync.domain.event.SealVerdict
import app.snapsync.domain.membership.LeftMembership
import app.snapsync.domain.membership.MintsLeftMembership
import app.snapsync.domain.upload.UploadRow
import app.snapsync.format.EncryptedFileFormat
import app.snapsync.mock.mobileDataAnswering
import app.snapsync.ports.ChangeOutcome
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.OsUploadJob
import app.snapsync.ports.TransferNetwork
import app.snapsync.ports.UploadJobState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The OS's own upload queue as an upload tier: a row's upload made a job sending the library's bytes — sealed by the
 * edge for an encrypted event — and every presented job found its row by the destination the row recorded, recorded
 * and acknowledged; an offered retry re-pointed at a fresh request, its row still requested.
 */
@OptIn(MintsLeftMembership::class)
class OsQueueTest {

    private object Held : GalleryResourceHandle

    private val world = SessionWorld()
    private val queue = RecordingQueue()
    private val tier =
        OsQueueTier(queue, world.requests, world.sealing, mobileDataAnswering { world.network }, world.record)
    private val drain = OsQueueDrain(queue, JobOwners(world.reads), JobAcknowledgement(queue), world.record)

    private fun job(url: String?, state: UploadJobState = UploadJobState.FAILED, resource: GalleryResourceHandle? = Held) =
        OsUploadJob(RecordingQueue.JobHandle, url, "video/quicktime", state, null, resource)

    /** [asset]'s row given a job, and that job's destination URL. */
    private suspend fun requested(asset: String = "A1"): String {
        tier.create(world.event, world.discovered(asset), SessionWorld.From)
        return queue.created.last().second.url
    }

    @Test
    fun `a job sends the library's own bytes to the event, and its row is requested`() = runTest {
        world.joined()
        world.network = TransferNetwork.UNRESTRICTED_ONLY

        val answer = tier.create(world.event, world.discovered(), SessionWorld.From)

        assertEquals(RowTransition.Transitioned, assertIs<CreateAnswer.Created>(answer).recorded)
        val (handle, target) = queue.created.single()
        assertSame(SessionWorld.Handle, handle)
        assertEquals(TransferNetwork.UNRESTRICTED_ONLY, target.network)
        assertTrue(target.url.contains("/events/${world.event.value}/files/devices/"))
        assertEquals(
            target.url.substringAfter("backend.test").substringBefore('?'),
            assertIs<UploadRow.Requested>(world.row()).destination?.path,
        )
    }

    @Test
    fun `an encrypted event's job carries the one file's key for the edge to seal with, or is withheld`() = runTest {
        world.joinedEncrypted()
        tier.create(world.event, world.discovered(), SessionWorld.From)
        assertTrue(EncryptedFileFormat.FILE_KEY_HEADER in queue.created.single().second.headers)

        val unkeyed = SessionWorld()
        unkeyed.joined(Encryption.Encrypted(unkeyed.ids.mint().id))
        val other =
            OsQueueTier(
                queue,
                unkeyed.requests,
                unkeyed.sealing,
                mobileDataAnswering { unkeyed.network },
                unkeyed.record,
            )
        val withheld = other.create(unkeyed.event, unkeyed.discovered(), SessionWorld.From)
        assertEquals(SealVerdict.Withheld.Because.KEY_LOST, assertIs<CreateAnswer.Withheld>(withheld).because)
        assertEquals(1, queue.created.size)
    }

    @Test
    fun `a queue that takes no more, or makes no job, leaves the row needing one`() = runTest {
        world.joined()
        val row = world.discovered()
        queue.answer = CreateOutcome.LIMIT_EXCEEDED
        assertEquals(CreateAnswer.Full, tier.create(world.event, row, SessionWorld.From))
        queue.answer = CreateOutcome.FAILED
        assertIs<CreateAnswer.Failed>(tier.create(world.event, row, SessionWorld.From))
        assertIs<UploadRow.Discovered>(world.row())
    }

    @Test
    fun `nothing is made or re-pointed where this device cannot be addressed`() = runTest {
        world.joined()
        val url = requested()
        queue.offered = listOf(job(url))
        val offered = drain.offered(world.event).single()

        world.identity.read = DeviceIdRead.Absent
        assertIs<CreateAnswer.Unaddressed>(tier.create(world.event, world.discovered("A2"), SessionWorld.From))
        assertIs<RetryAnswer.Unaddressed>(tier.retry(world.event, offered))
        world.identity.read = DeviceIdRead.Unavailable("locked")
        assertEquals(
            CreateAnswer.Unaddressed("locked"),
            tier.create(world.event, world.discovered("A2"), SessionWorld.From),
        )
        assertEquals(RetryAnswer.Unaddressed("locked"), tier.retry(world.event, offered))
        assertEquals(emptyList(), queue.retried)
    }

    @Test
    fun `an offered retry is re-pointed at a fresh request, its row still requested`() = runTest {
        world.joined()
        queue.offered = listOf(job(requested()))

        val offered = drain.offered(world.event).single()
        assertEquals(RetryAnswer.Repointed, tier.retry(world.event, offered))

        assertSame(queue.offered.single(), queue.retried.single().first)
        assertIs<UploadRow.Requested>(world.row())
        queue.change = ChangeOutcome.Refused(3202, "no")
        assertEquals(RetryAnswer.Refused("3202 no"), tier.retry(world.event, offered))
    }

    @Test
    fun `an offered retry of an encrypted event whose key is gone is left as it is`() = runTest {
        world.joinedEncrypted()
        queue.offered = listOf(job(requested()))
        val offered = drain.offered(world.event).single()
        world.keys.forget(LeftMembership(world.event))

        assertIs<RetryAnswer.Withheld>(tier.retry(world.event, offered))
        assertEquals(emptyList(), queue.retried)
    }

    @Test
    fun `a landed job settles its row, a failed one returns it with the resource the queue holds - each acknowledged`() =
        runTest {
            world.joined()
            val landed = job(requested("A1"), UploadJobState.SUCCEEDED)
            val failed = UploadJobState.entries.filter { it != UploadJobState.SUCCEEDED }.mapIndexed { i, state ->
                job(requested("B$i"), state, resource = if (i == 0) null else Held)
            }
            queue.terminals = listOf(landed) + failed

            val returned = drain.drain(world.event)

            assertIs<UploadRow.Completed>(world.row("A1"))
            assertEquals(failed.indices.map { world.key("B$it") }, returned.map { it.key })
            returned.forEach { assertEquals(RowTransition.Transitioned, it.recorded) }
            assertNull(returned.first().from, "a retry-spent job holds no resource")
            assertSame(Held, returned.last().from?.handle)
            failed.indices.forEach { assertIs<UploadRow.Discovered>(world.row("B$it")) }
            assertEquals(queue.terminals, queue.acknowledged)
        }

    @Test
    fun `a job no row owns is acknowledged and nothing is written - a withdrawn row's or an unmappable one`() = runTest {
        world.joined()
        val url = requested()
        world.walk.withdraw(world.event, listOf(world.key()))
        val withdrawn = job(url)
        val elsewhere = job("https://backend.test/api/v2/somewhere/else")
        val nowhere = job(null)
        queue.terminals = listOf(withdrawn, elsewhere, nowhere)
        queue.offered = listOf(withdrawn, nowhere)

        assertEquals(emptyList(), drain.drain(world.event))
        assertEquals(emptyList(), drain.offered(world.event))

        assertEquals(queue.terminals + queue.offered, queue.acknowledged)
        assertNull(world.row())
    }

    @Test
    fun `a job whose row cannot be read is left unanswered, for the queue to present again`() = runTest {
        world.joined()
        val url = requested()
        queue.terminals = listOf(job(url))
        queue.offered = listOf(job(url))
        world.databases.lock()

        assertEquals(emptyList(), drain.drain(world.event))
        assertEquals(emptyList(), drain.offered(world.event))
        assertEquals(emptyList(), queue.acknowledged)
    }

    @Test
    fun `an offered retry answered without a re-point is acknowledged, a refusal logged`() = runTest {
        world.joined()
        queue.offered = listOf(job(requested()))
        queue.change = ChangeOutcome.Refused(null, "busy")

        drain.answer(drain.offered(world.event).single())

        assertEquals(queue.offered, queue.acknowledged)
    }
}
