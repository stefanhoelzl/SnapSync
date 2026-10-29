package app.snapsync.services.upload

import app.snapsync.domain.upload.LedgerState
import app.snapsync.model.PlatformUploadJob
import app.snapsync.model.TerminalOutcome
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadFailure
import app.snapsync.ports.ChangeOutcome
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.OsUploadJob
import app.snapsync.ports.OsUploadJobHandle
import app.snapsync.ports.OsUploadQueue
import app.snapsync.ports.TransferFailure
import app.snapsync.ports.TransferNetwork
import app.snapsync.ports.UploadJobState
import app.snapsync.ports.UploadTarget
import app.snapsync.services.gallery.GalleryResourceRef
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cycle's transfer over the OS's job queue (capability `background-upload`): which row a presented job is, what its
 * end means, which offered retry is a key's — decided once here. The queue below is scripted and records every call,
 * because what the transfer owes the OS — an acknowledgement for EVERY presented job — is as much the point as what it
 * writes.
 */
class OsQueueTransferTest {

    private class ScriptedQueue(
        var terminal: List<OsUploadJob> = emptyList(),
        var offered: List<OsUploadJob> = emptyList(),
        var createAnswer: CreateOutcome = CreateOutcome.CREATED,
        var changeAnswer: ChangeOutcome = ChangeOutcome.Applied,
    ) : OsUploadQueue {
        val calls = mutableListOf<String>()
        val created = mutableListOf<GalleryResourceHandle>()
        val networks = mutableListOf<TransferNetwork>()

        override suspend fun create(resource: GalleryResourceHandle, target: UploadTarget): CreateOutcome {
            calls += "create(${target.url})"
            created += resource
            networks += target.network
            return createAnswer
        }

        override suspend fun retryOffered(): List<OsUploadJob> = offered

        override suspend fun terminals(): List<OsUploadJob> = terminal

        override suspend fun retry(job: OsUploadJob, target: UploadTarget): ChangeOutcome {
            calls += "retry(${job.destinationUrl} -> ${target.url})"
            return changeAnswer
        }

        override suspend fun acknowledge(job: OsUploadJob): ChangeOutcome {
            calls += "acknowledge(${job.destinationUrl})"
            return changeAnswer
        }
    }

    private object Job : OsUploadJobHandle

    private fun job(
        state: UploadJobState,
        path: String? = DESTINATION,
        resource: GalleryResourceHandle? = null,
        contentType: String? = "image/jpeg",
        error: TransferFailure? = null,
    ) = OsUploadJob(Job, path?.let { "https://edge.example$it" }, contentType, state, error, resource)

    private fun transfer(queue: OsUploadQueue, record: TransferRecord = Record(), resources: Resources = Resources()) =
        OsQueueTransfer(queue, setup(record, resources))

    @Test
    fun `a presented success is recorded COMPLETED and acknowledged and not handed up`() = runTest {
        val queue = ScriptedQueue(terminal = listOf(job(UploadJobState.SUCCEEDED)))
        val record = rowA()
        val handedUp = transfer(queue, record).drainTerminals()
        assertEquals(listOf(KEY to TerminalOutcome.COMPLETED), record.terminals)
        assertEquals(listOf("acknowledge($URL)"), queue.calls)
        assertTrue(handedUp.isEmpty(), "a terminal fact never crosses to the cycle")
    }

    @Test
    fun `a retry-spent failure whose photo lives is recorded FAILED and handed up for re-creation`() = runTest {
        val queue = ScriptedQueue(terminal = listOf(job(UploadJobState.FAILED)))
        val record = rowA()
        val handedUp = transfer(queue, record, Resources(mapOf(KEY to libraryResource()))).drainTerminals()
        assertEquals(listOf(KEY to TerminalOutcome.FAILED), record.terminals)
        assertEquals(listOf(KEY), handedUp.map { it.key })
        assertEquals(
            GalleryResourceRef(KeyHandle(KEY)),
            handedUp.single().data,
            "the photo's live resource, by its key",
        )
    }

    @Test
    fun `a retry-spent failure while the library cannot be read is recorded FAILED and re-creates nothing`() = runTest {
        // Could not look offers no live resource, as a gone photo does: nothing is handed up to be re-created.
        val queue = ScriptedQueue(terminal = listOf(job(UploadJobState.FAILED)))
        val record = rowA()
        val resources = Resources(mapOf(KEY to libraryResource()), readable = false)
        val handedUp = transfer(queue, record, resources).drainTerminals()
        assertEquals(1, resources.calls, "the resolve was asked")
        assertEquals(listOf(KEY to TerminalOutcome.FAILED), record.terminals)
        assertTrue(handedUp.isEmpty(), "an unreadable library re-creates nothing")
    }

    @Test
    fun `a retry-spent failure whose photo is gone is recorded FAILED and re-creates nothing`() = runTest {
        // The resolve answers, but nothing it answers is this key's resource: the photo left the library.
        val queue = ScriptedQueue(terminal = listOf(job(UploadJobState.FAILED)))
        val record = rowA()
        val resources = Resources(mapOf(KEY to libraryResource(key = "B-primary.jpg")))
        val handedUp = transfer(queue, record, resources).drainTerminals()
        assertEquals(1, resources.calls, "the resolve was asked")
        assertEquals(listOf(KEY to TerminalOutcome.FAILED), record.terminals)
        assertTrue(handedUp.isEmpty(), "a gone photo re-creates nothing")
    }

    @Test
    fun `every presented job is acknowledged - an unmappable and a pruned one included`() = runTest {
        val queue = ScriptedQueue(
            terminal = listOf(
                job(UploadJobState.SUCCEEDED, path = null),
                job(UploadJobState.SUCCEEDED, path = "/api/v2/files/devices/D/GONE/primary"),
            ),
        )
        transfer(queue).drainTerminals()
        assertEquals(2, queue.calls.count { it.startsWith("acknowledge") }, "PhotoKit reports error 50008 otherwise")
    }

    @Test
    fun `an offered retry is re-pointed by the destination its row recorded`() = runTest {
        val queue = ScriptedQueue(offered = listOf(job(UploadJobState.FAILED)))
        val transfer = transfer(queue, rowA())
        transfer.retryJob(transfer.fetchRetryJobs().single(), request(libraryResource()))
        assertEquals(listOf("retry($URL -> $URL)"), queue.calls)
    }

    @Test
    fun `a destination that is not a URL is not a job before the queue sees it`() = runTest {
        val queue = ScriptedQueue()
        val outcome = transfer(queue).createJob(request(libraryResource(), url = ""), libraryResource())
        assertEquals(UploadCreateOutcome.FAILED, outcome)
        assertTrue(queue.calls.isEmpty())
    }

    @Test
    fun `the queue is handed the library's own resource and its answer is the cycle's`() = runTest {
        val queue = ScriptedQueue(createAnswer = CreateOutcome.LIMIT_EXCEEDED)
        val outcome = transfer(queue).createJob(request(libraryResource()), libraryResource())
        assertEquals(UploadCreateOutcome.LIMIT_EXCEEDED, outcome)
        assertEquals(listOf<GalleryResourceHandle>(KeyHandle(KEY)), queue.created)
    }

    @Test
    fun `a resource the library did not read is not a job`() = runTest {
        val queue = ScriptedQueue()
        val outcome = transfer(queue).createJob(request(unreadResource()), unreadResource())
        assertEquals(UploadCreateOutcome.FAILED, outcome)
        assertTrue(queue.created.isEmpty())
    }

    /** Capability `mobile-data`: a job carries the rule in force when it is CREATED; a later change governs later jobs. */
    @Test
    fun `a job carries the network rule in force when it is created`() = runTest {
        var rule = TransferNetwork.UNRESTRICTED_ONLY
        val queue = ScriptedQueue()
        val transfer = OsQueueTransfer(queue, setup(network = { rule }))
        val a = libraryResource("A-primary.jpg")
        val b = libraryResource("B-primary.jpg")
        transfer.createJob(request(a), a)
        rule = TransferNetwork.ANY
        transfer.createJob(request(b), b)
        assertEquals(listOf(TransferNetwork.UNRESTRICTED_ONLY, TransferNetwork.ANY), queue.networks)
    }

    @Test
    fun `offered retries whose rows are gone or unmappable are answered and not handed up`() = runTest {
        val queue = ScriptedQueue(
            offered = listOf(
                job(UploadJobState.FAILED, path = null),
                job(UploadJobState.FAILED, path = "/api/v2/files/devices/D/GONE/primary"),
                job(UploadJobState.FAILED, path = "/not/a/byte/route"),
            ),
        )
        assertTrue(transfer(queue).fetchRetryJobs().isEmpty())
        assertEquals(3, queue.calls.count { it.startsWith("acknowledge") })
    }

    @Test
    fun `a failure the queue still holds the resource of is re-created from it`() = runTest {
        val queue = ScriptedQueue(terminal = listOf(job(UploadJobState.UNKNOWN, resource = KeyHandle("own"))))
        val record = rowA()
        val handedUp = transfer(queue, record).drainTerminals()
        assertEquals(GalleryResourceRef(KeyHandle("own")), handedUp.single().data, "an untaught state is a failure")
        assertEquals(listOf(KEY to TerminalOutcome.FAILED), record.terminals)
    }

    @Test
    fun `a held resource of a job that kept no type is re-created with the generic one`() = runTest {
        val queue = ScriptedQueue(
            terminal = listOf(job(UploadJobState.FAILED, resource = KeyHandle("own"), contentType = null)),
        )
        assertEquals("application/octet-stream", transfer(queue, rowA()).drainTerminals().single().contentType)
    }

    @Test
    fun `a failure on a row already settled is not recorded and not handed up`() = runTest {
        val queue = ScriptedQueue(terminal = listOf(job(UploadJobState.FAILED)))
        val record = rowA(LedgerState.COMPLETED)
        assertTrue(transfer(queue, record).drainTerminals().isEmpty())
        assertTrue(record.terminals.isEmpty(), "a settled row takes no terminal write")
    }

    @Test
    fun `a refused acknowledgement or retry is reported and changes nothing else`() = runTest {
        val queue = ScriptedQueue(
            terminal = listOf(job(UploadJobState.SUCCEEDED)),
            offered = listOf(job(UploadJobState.FAILED)),
            changeAnswer = ChangeOutcome.Refused(3202, "refused"),
        )
        val transfer = transfer(queue, rowA())
        transfer.drainTerminals()
        transfer.retryJob(transfer.fetchRetryJobs().single(), request(libraryResource()))
        assertEquals(1, queue.calls.count { it.startsWith("retry") })
    }

    @Test
    fun `a retry finds nothing to re-point once its job settled and never re-points to a bad destination`() = runTest {
        val queue = ScriptedQueue()
        val gone = PlatformUploadJob(KEY, "image/jpeg", null, null)
        val transfer = transfer(queue, rowA())
        transfer.retryJob(gone, request(libraryResource()))
        queue.offered = listOf(job(UploadJobState.FAILED))
        transfer.retryJob(gone, request(libraryResource(), url = ""))
        assertTrue(queue.calls.none { it.startsWith("retry") })
    }

    @Test
    fun `a drained job on a route this build cannot map is acknowledged and reported and a pruned one is not recorded`() =
        runTest {
            val queue = ScriptedQueue(
                terminal = listOf(
                    job(UploadJobState.FAILED, path = "/not/a/byte/route"),
                    job(UploadJobState.SUCCEEDED, path = "/api/v2/files/devices/D/GONE/primary"),
                ),
            )
            val record = rowA()
            assertTrue(transfer(queue, record).drainTerminals().isEmpty())
            assertTrue(record.terminals.isEmpty(), "neither job is a row's")
            assertEquals(2, queue.calls.count { it.startsWith("acknowledge") })
        }

    @Test
    fun `an offered retry carries the resource the queue still holds and one it no longer holds carries none`() =
        runTest {
            val queue = ScriptedQueue(
                offered = listOf(
                    job(UploadJobState.FAILED, resource = KeyHandle("own")),
                    job(UploadJobState.FAILED, resource = null),
                ),
            )
            assertEquals(
                listOf(GalleryResourceRef(KeyHandle("own")), null),
                transfer(queue, rowA()).fetchRetryJobs().map { it.data },
            )
        }

    @Test
    fun `a success the queue still holds the resource of is recorded and not handed up`() = runTest {
        val queue = ScriptedQueue(terminal = listOf(job(UploadJobState.SUCCEEDED, resource = KeyHandle("own"))))
        val record = rowA()
        assertTrue(transfer(queue, record).drainTerminals().isEmpty())
        assertEquals(listOf(KEY to TerminalOutcome.COMPLETED), record.terminals)
    }

    @Test
    fun `a re-created failure reports the type it was created with - else the live photo's - else a generic one`() =
        runTest {
            assertEquals("image/heic", reCreatedType(stored = "image/heic", live = "image/jpeg"))
            assertEquals("image/jpeg", reCreatedType(stored = null, live = "image/jpeg"))
            assertEquals("application/octet-stream", reCreatedType(stored = null, live = ""))
        }

    private suspend fun reCreatedType(stored: String?, live: String): String {
        val queue = ScriptedQueue(terminal = listOf(job(UploadJobState.FAILED, contentType = stored)))
        val resources = Resources(mapOf(KEY to libraryResource(contentType = live)))
        return transfer(queue, rowA(), resources).drainTerminals().single().contentType
    }

    @Test
    fun `a retry passes over an offered job whose row is gone to the one that is its key's`() = runTest {
        val queue = ScriptedQueue(
            offered = listOf(
                job(UploadJobState.FAILED, path = "/api/v2/files/devices/D/GONE/primary"),
                job(UploadJobState.FAILED),
            ),
        )
        transfer(queue, rowA()).retryJob(
            PlatformUploadJob(KEY, "image/jpeg", null, null),
            request(libraryResource()),
        )
        assertEquals(listOf("retry($URL -> $URL)"), queue.calls)
    }

    @Test
    fun `an offered retry carries the error the queue reported`() = runTest {
        val queue = ScriptedQueue(offered = listOf(job(UploadJobState.FAILED, error = TransferFailure("http:503"))))
        assertEquals(UploadFailure("http:503"), transfer(queue, rowA()).fetchRetryJobs().single().error)
    }

    @Test
    fun `a resource the queue cannot take is no job`() = runTest {
        val queue = ScriptedQueue(createAnswer = CreateOutcome.FAILED)
        val outcome = transfer(queue).createJob(request(libraryResource()), libraryResource())
        assertEquals(UploadCreateOutcome.FAILED, outcome)
    }
}
