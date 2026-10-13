package app.snapsync.services.upload

import app.snapsync.inbound.CompositionOnly
import app.snapsync.model.PlatformUploadJob
import app.snapsync.model.TerminalOutcome
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadRequest
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.Transfer
import app.snapsync.ports.TransferFailure
import app.snapsync.ports.TransferHandle
import app.snapsync.ports.TransferHandlers
import app.snapsync.ports.TransferNetwork
import app.snapsync.ports.TransferSession
import app.snapsync.ports.TransferSource
import app.snapsync.ports.UploadTarget
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The cycle's transfer over the app's own session (capability `background-upload`): what a session that sends files is
 * handed, the staged file each transfer leaves until it ends, and an end recorded the moment the session tells it. The
 * session below is scripted and records every call.
 */
class SessionTransferTest {

    private class ScriptedSession(
        override val takesResources: Boolean = false,
        var live: List<Transfer> = emptyList(),
        var createAnswer: CreateOutcome = CreateOutcome.CREATED,
    ) : TransferSession {
        val calls = mutableListOf<String>()
        val created = mutableListOf<TransferSource>()
        val networks = mutableListOf<TransferNetwork>()

        @CompositionOnly
        override fun inbound(handlers: TransferHandlers) = Unit

        override suspend fun create(source: TransferSource, target: UploadTarget, tag: String): CreateOutcome {
            calls += "create($tag)"
            created += source
            networks += target.network
            return createAnswer
        }

        override suspend fun live(): List<Transfer> = live

        override suspend fun cancel(transfer: Transfer) {
            calls += "cancel(${transfer.tag})"
        }
    }

    private object Handle : TransferHandle

    private fun transferTagged(tag: String?) = Transfer(Handle, tag)

    private fun transfer(
        session: TransferSession,
        record: TransferRecord = Record(),
        library: Library = Library(),
        files: SharedArea = SharedArea(),
        network: () -> TransferNetwork = { TransferNetwork.ANY },
    ) = SessionTransfer(session, setup(record, library = library, files = files, network = network))

    @Test
    fun `a destination that is not a URL is not a transfer before the session sees it`() = runTest {
        val session = ScriptedSession()
        val outcome = transfer(session).createJob(UploadRequest("", emptyMap(), libraryResource()), libraryResource())
        assertEquals(UploadCreateOutcome.FAILED, outcome)
        assertTrue(session.calls.isEmpty())
    }

    @Test
    fun `a file session is handed the exported file which goes when no transfer was made`() = runTest {
        val session = ScriptedSession(createAnswer = CreateOutcome.LIMIT_EXCEEDED)
        val library = Library(exportable = setOf(KEY))
        val files = SharedArea()
        val outcome = transfer(session, library = library, files = files)
            .createJob(UploadRequest(URL, emptyMap(), libraryResource()), libraryResource())
        assertEquals(UploadCreateOutcome.LIMIT_EXCEEDED, outcome)
        assertEquals(listOf(KEY to "/shared/upload-staging/$KEY"), library.exports)
        assertEquals("/shared/upload-staging/$KEY", assertIs<TransferSource.File>(session.created.single()).path)
        assertFalse("upload-staging/$KEY" in files.files, "a creation the session refused leaves no file")
    }

    @Test
    fun `a session that takes resources is handed the resource itself`() = runTest {
        val session = ScriptedSession(takesResources = true)
        val outcome = transfer(session).createJob(UploadRequest(URL, emptyMap(), libraryResource()), libraryResource())
        assertEquals(UploadCreateOutcome.CREATED, outcome)
        assertEquals(KeyHandle(KEY), assertIs<TransferSource.Resource>(session.created.single()).handle)
    }

    @Test
    fun `a staged upload file no live transfer sends is released and a live one is kept`() = runTest {
        // What an export leaves when no transfer outlived it — the process died before the transfer existed, or the
        // transfer ended unreported — and nothing else ever reaches.
        val session = ScriptedSession(live = listOf(transferTagged(KEY)))
        val files = SharedArea().apply {
            this.files += listOf("upload-staging/$KEY", "upload-staging/B-primary.jpg", "elsewhere/C.bin")
        }
        transfer(session, files = files).releaseUnclaimedStaging()
        assertEquals(setOf("upload-staging/$KEY", "elsewhere/C.bin"), files.files)
    }

    @Test
    fun `a sweep with every staged upload file claimed deletes nothing`() = runTest {
        // A live transfer with no tag claims nothing, and is no reason to stop.
        val session = ScriptedSession(live = listOf(transferTagged(null), transferTagged(KEY)))
        val files = SharedArea(deletable = false).apply { this.files += "upload-staging/$KEY" }
        transfer(session, files = files).releaseUnclaimedStaging()
        assertEquals(setOf("upload-staging/$KEY"), files.files)
    }

    @Test
    fun `an unclaimed staged upload file that cannot be deleted stays and the sweep goes on`() = runTest {
        val files = SharedArea(deletable = false).apply {
            this.files += listOf("upload-staging/B-primary.jpg", "upload-staging/C-primary.jpg")
        }
        transfer(ScriptedSession(), files = files).releaseUnclaimedStaging() // must not throw
        assertEquals(setOf("upload-staging/B-primary.jpg", "upload-staging/C-primary.jpg"), files.files)
    }

    @Test
    fun `an upload staging directory that cannot be read releases nothing`() = runTest {
        val files = SharedArea(reachable = false).apply { this.files += "upload-staging/B-primary.jpg" }
        transfer(ScriptedSession(), files = files).releaseUnclaimedStaging()
        assertEquals(setOf("upload-staging/B-primary.jpg"), files.files, "a listing never made is not an empty one")
    }

    @Test
    fun `an unclaimed staged upload file already gone when the sweep reaches it is no failure`() = runTest {
        val files = SharedArea(vanishing = setOf("upload-staging/B-primary.jpg")).apply {
            this.files += "upload-staging/C-primary.jpg"
        }
        transfer(ScriptedSession(), files = files).releaseUnclaimedStaging()
        assertTrue(files.files.isEmpty())
    }

    @Test
    fun `a resource the library can no longer export is not a transfer`() = runTest {
        val session = ScriptedSession()
        val outcome = transfer(session).createJob(UploadRequest(URL, emptyMap(), libraryResource()), libraryResource())
        assertEquals(UploadCreateOutcome.FAILED, outcome)
        assertTrue(session.created.isEmpty())
    }

    @Test
    fun `a resource the library did not read has nothing to export and is not a transfer`() = runTest {
        val session = ScriptedSession()
        val library = Library(exportable = setOf(KEY))
        val outcome = transfer(session, library = library)
            .createJob(UploadRequest(URL, emptyMap(), unreadResource()), unreadResource())
        assertEquals(UploadCreateOutcome.FAILED, outcome)
        assertTrue(library.exports.isEmpty(), "the library is never asked")
        assertTrue(session.created.isEmpty())
    }

    @Test
    fun `a session with no shared area makes nothing`() = runTest {
        val session = ScriptedSession()
        val transfer = transfer(session, library = Library(setOf(KEY)), files = SharedArea(reachable = false))
        assertEquals(
            UploadCreateOutcome.FAILED,
            transfer.createJob(UploadRequest(URL, emptyMap(), libraryResource()), libraryResource()),
        )
        assertTrue(session.created.isEmpty())
    }

    /** Capability `mobile-data`: a transfer carries the rule in force when it is MADE; a later change governs later ones. */
    @Test
    fun `a transfer carries the network rule in force when it is made`() = runTest {
        var rule = TransferNetwork.UNRESTRICTED_ONLY
        val session = ScriptedSession(takesResources = true)
        val transfer = transfer(session, network = { rule })
        val a = libraryResource("A-primary.jpg")
        val b = libraryResource("B-primary.jpg")
        transfer.createJob(UploadRequest(URL, emptyMap(), a), a)
        rule = TransferNetwork.ANY
        transfer.createJob(UploadRequest(URL, emptyMap(), b), b)
        assertEquals(listOf(TransferNetwork.UNRESTRICTED_ONLY, TransferNetwork.ANY), session.networks)
    }

    @Test
    fun `an end is recorded inline and its staged file goes with it`() {
        val record = rowA()
        val files = SharedArea().apply { this.files += "upload-staging/$KEY" }
        val transfer = transfer(ScriptedSession(), record, files = files)
        assertTrue(transfer.recordFinished(KEY, failure = null))
        assertEquals(listOf(KEY to TerminalOutcome.COMPLETED), record.terminals)
        assertTrue(files.files.isEmpty())
        assertFalse(
            transfer.recordFinished(KEY, TransferFailure("network")),
            "a row no longer REQUESTED takes nothing: the guard is the store's",
        )
    }

    @Test
    fun `a failed end is recorded as a failure whatever the session said of it`() {
        val record = rowA()
        assertTrue(transfer(ScriptedSession(), record).recordFinished(KEY, TransferFailure("http:500")))
        assertEquals(listOf(KEY to TerminalOutcome.FAILED), record.terminals)
    }

    @Test
    fun `an ended transfer is recorded even when its staged file cannot go now`() {
        // A locked area keeps the file (the next sweep releases it); an unavailable one has nothing to release.
        for (files in listOf(SharedArea(deletable = false), SharedArea(reachable = false))) {
            files.files += "upload-staging/$KEY"
            val record = rowA()
            assertTrue(transfer(ScriptedSession(), record, files = files).recordFinished(KEY, failure = null))
            assertEquals(setOf("upload-staging/$KEY"), files.files)
        }
    }

    @Test
    fun `a leave cancels every live transfer - a tagless one included - and their staged files`() = runTest {
        val files = SharedArea().apply { this.files += "upload-staging/$KEY" }
        val session = ScriptedSession(live = listOf(transferTagged(KEY), transferTagged(null)))
        transfer(session, files = files).cancelAll()
        assertEquals(listOf("cancel($KEY)", "cancel(null)"), session.calls)
        assertTrue(files.files.isEmpty())
    }

    @Test
    fun `a session that takes resources has nothing to send for a resource the library did not read`() = runTest {
        val session = ScriptedSession(takesResources = true)
        val outcome = transfer(session).createJob(UploadRequest(URL, emptyMap(), unreadResource()), unreadResource())
        assertEquals(UploadCreateOutcome.FAILED, outcome)
        assertTrue(session.created.isEmpty())
    }

    @Test
    fun `a session offers the cycle no retries and no terminals to drain`() = runTest {
        val session = ScriptedSession()
        val transfer = transfer(session, rowA())
        assertTrue(transfer.fetchRetryJobs().isEmpty())
        assertTrue(transfer.drainTerminals().isEmpty())
        transfer.retryJob(
            PlatformUploadJob(KEY, "image/jpeg", null, null),
            UploadRequest(URL, emptyMap(), libraryResource()),
        )
        assertTrue(session.calls.isEmpty(), "an end is recorded as it is told, so there is nothing to re-point")
    }
}
