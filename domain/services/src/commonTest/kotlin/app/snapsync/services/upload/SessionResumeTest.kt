package app.snapsync.services.upload

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.upload.LedgerState
import app.snapsync.inbound.CompositionOnly
import app.snapsync.model.AesKey
import app.snapsync.model.FileAccess
import app.snapsync.model.FileArea
import app.snapsync.model.FileLocation
import app.snapsync.model.FileResult
import app.snapsync.model.FileTail
import app.snapsync.model.GcmNonce
import app.snapsync.model.LedgerEntry
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadRequest
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.Crypto
import app.snapsync.ports.Files
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.Transfer
import app.snapsync.ports.TransferFailure
import app.snapsync.ports.TransferHandle
import app.snapsync.ports.TransferHandlers
import app.snapsync.ports.TransferSession
import app.snapsync.ports.TransferSource
import app.snapsync.ports.UploadTarget
import app.snapsync.ports.WriteOutcome
import app.snapsync.wire.api.Routes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A file upload that RESUMES (`docs/architecture.md`, "Resumable uploads"): the staged file and its digest outlive a
 * failed attempt, the retry asks where its bytes stand and sends the rest, and a fresh upload never asks. Over files
 * that hold their bytes, since what is hashed, kept and sent from where is the point.
 */
class SessionResumeTest {

    private val sha256Header = Routes.UploadResource.HEADER_X_SNAPSYNC_SHA256
    private val rangeHeader = Routes.UploadResource.HEADER_CONTENT_RANGE
    private val staged = uploadStagingPath(KEY)
    private val bytes = ByteArray(10_000) { (it * 7).toByte() }

    private class MemFiles : Files {
        val held = mutableMapOf<String, ByteArray>()
        var unreadable = false
        override fun read(area: FileArea, path: String): FileResult<ByteArray> =
            held[path]?.let { FileResult.Ok(it) } ?: FileResult.NotFound
        override fun readTail(area: FileArea, path: String, maxBytes: Int): FileResult<FileTail> = FileResult.NotFound
        override fun readRange(area: FileArea, path: String, offset: Long, maxBytes: Int): FileResult<ByteArray> {
            if (unreadable) return FileResult.Failed("I/O error")
            val all = held[path] ?: return FileResult.NotFound
            val from = offset.toInt().coerceAtMost(all.size)
            return FileResult.Ok(all.copyOfRange(from, (from + maxBytes).coerceAtMost(all.size)))
        }
        override fun write(area: FileArea, path: String, bytes: ByteArray) =
            FileResult.Ok(Unit).also { held[path] = bytes }
        override fun append(area: FileArea, path: String, bytes: ByteArray) =
            FileResult.Ok(Unit).also { held[path] = (held[path] ?: ByteArray(0)) + bytes }
        override fun delete(area: FileArea, path: String): FileResult<Unit> =
            if (held.remove(path) != null) FileResult.Ok(Unit) else FileResult.NotFound
        override fun exists(area: FileArea, path: String) = FileResult.Ok(path in held)
        override fun locate(area: FileArea, path: String): FileLocation<String> = FileResult.Ok("/os/$path")
        override fun move(area: FileArea, from: String, to: String): FileResult<Unit> = FileResult.NotFound
        override fun adopt(osPath: String, area: FileArea, to: String): FileResult<Unit> = FileResult.NotFound
        override fun list(area: FileArea, directory: String): FileAccess<List<String>> =
            FileResult.Ok(held.keys.filter { it.startsWith("$directory/") })
    }

    /** A library whose export writes [content] where the file was located. */
    private class ExportingLibrary(private val files: MemFiles, var content: ByteArray) : GalleryReader by Library() {
        var exports = 0
        override suspend fun export(resource: GalleryResourceHandle, to: String): WriteOutcome {
            exports++
            files.held[to.removePrefix("/os/")] = content
            return WriteOutcome.Ok
        }
    }

    private class FileSession : TransferSession {
        override val takesResources = false
        val created = mutableListOf<Pair<TransferSource.File, Map<String, String>>>()
        var live: List<Transfer> = emptyList()
        var answer = CreateOutcome.CREATED

        @CompositionOnly
        override fun inbound(handlers: TransferHandlers) = Unit
        override suspend fun create(source: TransferSource, target: UploadTarget, tag: String): CreateOutcome {
            created += (source as TransferSource.File) to target.headers
            return answer
        }
        override suspend fun live(): List<Transfer> = live
        override suspend fun cancel(transfer: Transfer) = Unit
    }

    private object Handle : TransferHandle

    /** Not cryptography: a stand-in hash over the bytes' concatenation, 32 bytes. */
    private object Hash : Crypto {
        override fun randomBytes(count: Int) = ByteArray(count)
        override fun hmacSha256(key: ByteArray, message: ByteArray) = ByteArray(32)
        override fun aesGcmSeal(key: AesKey, nonce: GcmNonce, plaintext: ByteArray) = plaintext
        override fun aesGcmOpen(key: AesKey, nonce: GcmNonce, sealed: ByteArray) = sealed
        override fun sha256(chunks: Sequence<ByteArray>): ByteArray {
            var h = 17L
            chunks.forEach { chunk -> chunk.forEach { h = h * 31 + it } }
            return ByteArray(32) { (h ushr (it % 8 * 8)).toByte() }
        }
    }

    private val files = MemFiles()
    private val library = ExportingLibrary(files, bytes)
    private val session = FileSession()
    private val record = Record()
    private val asked = mutableListOf<String>()
    private var standing: ResumePoint = ResumePoint.From(0)

    private val resource = libraryResource(KEY)

    private fun transfer(resuming: Boolean = true) = SessionTransfer(
        session,
        setup(record, library = library, files = files),
        if (resuming) Resuming({ _, sha -> standing.also { asked += sha } }, Hash) else null,
    )

    private fun request() = UploadRequest(URL, mapOf("Content-Type" to "video/quicktime"), resource)

    private val failure = TransferFailure("network")

    @Test
    fun `a fresh upload is hashed - declares its SHA-256 and never asks where it stands`() = runTest {
        assertEquals(UploadCreateOutcome.CREATED, transfer().createJob(request(), resource))
        val (source, headers) = session.created.single()
        assertEquals(0, source.from)
        assertTrue(headers.getValue(sha256Header).matches(Regex("[0-9a-f]{64}")))
        assertNull(headers[rangeHeader], "a whole file names no range")
        assertEquals(emptyList(), asked, "a fresh upload has nothing to ask about")
    }

    @Test
    fun `a failed upload keeps its staged file and digest - a success releases both`() = runTest {
        val transfer = transfer()
        transfer.createJob(request(), resource)
        transfer.recordFinished(KEY, failure)
        assertTrue(staged in files.held && "$staged.sha256" in files.held, "kept for the retry")
        transfer.recordFinished(KEY, null)
        assertFalse(staged in files.held || "$staged.sha256" in files.held)
    }

    @Test
    fun `the retry sends the same file from where its bytes stand - naming the range`() = runTest {
        val transfer = transfer()
        transfer.createJob(request(), resource)
        transfer.recordFinished(KEY, failure)
        library.content = ByteArray(10_000) { 9 } // an export now would differ — the retry must not export again
        standing = ResumePoint.From(6_000)
        transfer.createJob(request(), resource)
        assertEquals(1, library.exports, "the kept file goes again")
        val first = session.created.first().second.getValue(sha256Header)
        assertEquals(listOf(first), asked, "asked about the very bytes it sent")
        val (source, headers) = session.created.last()
        assertEquals(6_000, source.from)
        assertEquals("bytes 6000-9999/10000", headers[rangeHeader])
        assertEquals(first, headers[sha256Header])
    }

    @Test
    fun `bytes that already landed are finished with their last byte`() = runTest {
        val transfer = transfer()
        transfer.createJob(request(), resource)
        transfer.recordFinished(KEY, failure)
        standing = ResumePoint.Landed
        transfer.createJob(request(), resource)
        assertEquals(9_999, session.created.last().first.from)
        assertEquals("bytes 9999-9999/10000", session.created.last().second[rangeHeader])
    }

    @Test
    fun `a retry that cannot ask makes nothing now and keeps the file`() = runTest {
        val transfer = transfer()
        transfer.createJob(request(), resource)
        transfer.recordFinished(KEY, failure)
        standing = ResumePoint.Deferred
        assertEquals(UploadCreateOutcome.FAILED, transfer.createJob(request(), resource))
        assertEquals(1, session.created.size)
        assertTrue(staged in files.held)
    }

    @Test
    fun `a digest whose staged file is gone is dropped and the file exported again`() = runTest {
        val transfer = transfer()
        transfer.createJob(request(), resource)
        transfer.recordFinished(KEY, failure)
        files.held.remove(staged)
        transfer.createJob(request(), resource)
        assertEquals(2, library.exports)
        assertEquals(0, session.created.last().first.from)
        assertEquals(emptyList(), asked, "a fresh export never asks")
    }

    @Test
    fun `a digest that does not read as one is dropped and the file exported again`() = runTest {
        val transfer = transfer()
        transfer.createJob(request(), resource)
        transfer.recordFinished(KEY, failure)
        files.held["$staged.sha256"] = "not a digest".encodeToByteArray()
        transfer.createJob(request(), resource)
        assertEquals(2, library.exports)
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `the staging sweep spares a failed uploads file while its row still needs it`() = runTest {
        val transfer = transfer()
        transfer.createJob(request(), resource)
        transfer.recordFinished(KEY, failure)
        files.held["$UPLOAD_STAGING_DIR/orphan.jpg"] = byteArrayOf(1)
        record.rows[DESTINATION] = LedgerEntry(key = KEY, assetId = AssetId("A"), state = LedgerState.DISCOVERED)
        transfer.releaseUnclaimedStaging()
        assertTrue(staged in files.held && "$staged.sha256" in files.held, "its retry resumes it")
        assertFalse("$UPLOAD_STAGING_DIR/orphan.jpg" in files.held, "nothing will send this one")
        record.rows[DESTINATION] = LedgerEntry(key = KEY, assetId = AssetId("A"), state = LedgerState.COMPLETED)
        transfer.releaseUnclaimedStaging()
        assertFalse(staged in files.held || "$staged.sha256" in files.held, "landed: nothing resumes it")
    }

    @Test
    fun `the digest of an upload in flight is never swept`() = runTest {
        val transfer = transfer()
        transfer.createJob(request(), resource)
        session.live = listOf(Transfer(Handle, KEY))
        transfer.releaseUnclaimedStaging()
        assertTrue("$staged.sha256" in files.held)
    }

    @Test
    fun `a transfer the session refuses now keeps a hashed file for the next attempt`() = runTest {
        session.answer = CreateOutcome.LIMIT_EXCEEDED
        assertEquals(UploadCreateOutcome.LIMIT_EXCEEDED, transfer().createJob(request(), resource))
        assertTrue(staged in files.held && "$staged.sha256" in files.held, "not exported again")
    }

    @Test
    fun `a staged file that cannot be read whole is sent as it always was and never resumes`() = runTest {
        files.unreadable = true
        assertEquals(UploadCreateOutcome.CREATED, transfer().createJob(request(), resource))
        assertNull(session.created.single().second[sha256Header])
        assertFalse("$staged.sha256" in files.held)
    }

    @Test
    fun `without resuming a failure releases the file and nothing is declared`() = runTest {
        val transfer = transfer(resuming = false)
        transfer.createJob(request(), resource)
        assertNull(session.created.single().second[sha256Header])
        transfer.recordFinished(KEY, failure)
        assertFalse(staged in files.held)
    }
}
