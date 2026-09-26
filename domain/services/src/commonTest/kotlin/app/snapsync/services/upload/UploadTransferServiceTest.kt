package app.snapsync.services.upload

import app.snapsync.model.AlbumId
import app.snapsync.model.AlbumRecord
import app.snapsync.model.AssetFacts
import app.snapsync.model.AssetId
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.ChangeOutcome
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.FileTail
import app.snapsync.model.GalleryAccess
import app.snapsync.model.GalleryRead
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.RawAsset
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.TerminalOutcome
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadJobSet
import app.snapsync.model.UploadJobState
import app.snapsync.model.UploadRequest
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadSourceKind
import app.snapsync.model.UploadTarget
import app.snapsync.model.WriteOutcome
import app.snapsync.services.gallery.Discovery
import app.snapsync.ports.Files
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.Upload
import app.snapsync.services.gallery.UploadDiscovery
import app.snapsync.ports.UploadHandlers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The upload service over the thin `Upload` port (capability `background-upload`): the settling, recording and
 * exporting each platform adapter used to do itself, now done once. The port below is scripted and records every call,
 * because what the service owes the platform — an acknowledgement for EVERY presented job — is as much the point as
 * what it writes.
 */
class UploadTransferServiceTest {

    private val destination = "/api/v2/files/devices/D/A/primary"
    private val url = "https://edge.example$destination"

    private class ScriptedUpload(
        override val accepts: UploadSourceKind = UploadSourceKind.RESOURCE,
        var terminal: List<UploadJob> = emptyList(),
        var offered: List<UploadJob> = emptyList(),
        var inFlight: List<UploadJob> = emptyList(),
        var createAnswer: UploadCreateOutcome = UploadCreateOutcome.CREATED,
        var changeAnswer: ChangeOutcome = ChangeOutcome.Applied,
    ) : Upload {
        val calls = mutableListOf<String>()
        val created = mutableListOf<Pair<UploadSource, String>>()

        override fun listen(handlers: UploadHandlers) = Unit

        override suspend fun create(source: UploadSource, target: UploadTarget, tag: String): UploadCreateOutcome {
            calls += "create($tag)"
            created += source to tag
            return createAnswer
        }

        override suspend fun jobs(set: UploadJobSet): List<UploadJob> = when (set) {
            UploadJobSet.TERMINAL -> terminal
            UploadJobSet.RETRY_OFFERED -> offered
            UploadJobSet.IN_FLIGHT -> inFlight
        }

        override suspend fun retry(job: UploadJob, target: UploadTarget): ChangeOutcome {
            calls += "retry(${job.destinationPath} -> ${target.url})"
            return changeAnswer
        }

        override suspend fun acknowledge(job: UploadJob): ChangeOutcome {
            calls += "acknowledge(${job.destinationPath})"
            return changeAnswer
        }

        override suspend fun cancel(job: UploadJob): ChangeOutcome {
            calls += "cancel(${job.tag})"
            return ChangeOutcome.Applied
        }
    }

    /** A ledger of rows by destination, recording the guarded terminal writes — applied only to a REQUESTED row. */
    private class Record(val rows: MutableMap<String, LedgerEntry> = mutableMapOf()) : TransferRecord {
        val terminals = mutableListOf<Pair<String, TerminalOutcome>>()

        override suspend fun entryForDestination(destinationPath: String): LedgerEntry? = rows[destinationPath]

        override fun markTerminal(key: String, outcome: TerminalOutcome): Boolean {
            val (at, row) = rows.entries.firstOrNull { it.value.key == key }?.toPair() ?: return false
            if (row.state != LedgerState.REQUESTED) return false
            terminals += key to outcome
            val settled = if (outcome == TerminalOutcome.COMPLETED) LedgerState.COMPLETED else LedgerState.DISCOVERED
            rows[at] = LedgerEntry(key = row.key, assetId = row.assetId, state = settled, destinationPath = row.destinationPath)
            return true
        }
    }

    private class Resources(val live: Map<String, Resource> = emptyMap()) : UploadDiscovery {
        override suspend fun discover(policy: SelectionPolicy) = Discovery(emptyList(), fullEnumeration = false)
        override suspend fun resourcesFor(keys: Set<String>) = keys.mapNotNull(live::get)
    }

    /** A library that exports only [exportable] keys, recording where it wrote. */
    private class Library(val exportable: Set<String> = emptySet()) : GalleryReader {
        val exports = mutableListOf<Pair<String, String>>()
        override fun access() = GalleryAccess.GRANTED
        override suspend fun assets(policy: SelectionPolicy): GalleryRead<List<AssetFacts>> = GalleryRead.Read(emptyList())
        override suspend fun assetsById(ids: Set<AssetId>): GalleryRead<List<AssetFacts>> = GalleryRead.Read(emptyList())
        override suspend fun resources(ids: Set<AssetId>): GalleryRead<List<RawAsset>> = GalleryRead.Read(emptyList())
        override suspend fun albums(): GalleryRead<List<AlbumRecord>> = GalleryRead.Read(emptyList())
        override suspend fun albumsById(ids: Set<AlbumId>): GalleryRead<List<AlbumRecord>> = GalleryRead.Read(emptyList())
        override suspend fun albumMembers(album: AlbumId, since: CaptureCutoff?): GalleryRead<Set<AssetId>> =
            GalleryRead.Read(emptySet())
        override suspend fun createAlbum(title: String): AlbumId? = null
        override suspend fun addToAlbum(album: AlbumId, assets: Set<AssetId>) = WriteOutcome.Ok
        override suspend fun export(resource: Resource, to: String): WriteOutcome {
            if (resource.filename !in exportable) return WriteOutcome.Failed("gone")
            exports += resource.filename to to
            return WriteOutcome.Ok
        }
    }

    /** The shared area as a set of paths, located under `/shared/` — or unreachable when [reachable] is off. */
    private class SharedArea(private val reachable: Boolean = true) : Files {
        val files = mutableSetOf<String>()
        override fun read(area: FileArea, path: String): FileResult<ByteArray> = FileResult.NotFound
        override fun readTail(area: FileArea, path: String, maxBytes: Int): FileResult<FileTail> = FileResult.NotFound
        override fun write(area: FileArea, path: String, bytes: ByteArray) = FileResult.Ok(Unit).also { files += path }
        override fun delete(area: FileArea, path: String): FileResult<Unit> =
            if (files.remove(path)) FileResult.Ok(Unit) else FileResult.NotFound
        override fun exists(area: FileArea, path: String) = FileResult.Ok(path in files)
        override fun locate(area: FileArea, path: String): FileResult<String> =
            if (reachable) FileResult.Ok("/shared/$path") else FileResult.AreaUnavailable
        override fun move(area: FileArea, from: String, to: String): FileResult<Unit> = FileResult.NotFound
        override fun adopt(osPath: String, area: FileArea, to: String): FileResult<Unit> = FileResult.NotFound
    }

    private fun row(key: String, state: LedgerState = LedgerState.REQUESTED) =
        LedgerEntry(key = key, assetId = AssetId("A"), state = state, destinationPath = destination)

    private fun job(state: UploadJobState, path: String? = destination, source: UploadSource? = null, tag: String? = null) =
        UploadJob(handle = "job", tag = tag, destinationPath = path, contentType = "image/jpeg", state = state, error = null, source = source)

    private fun service(
        upload: Upload,
        record: TransferRecord = Record(),
        resources: UploadDiscovery = Resources(),
        library: GalleryReader = Library(),
        files: Files = SharedArea(),
    ) = UploadTransferService(upload, record, resources, library, files)

    @Test
    fun `a presented success is recorded COMPLETED and acknowledged and not handed up`() = runTest {
        val upload = ScriptedUpload(terminal = listOf(job(UploadJobState.SUCCEEDED)))
        val record = Record(mutableMapOf(destination to row("A-primary.jpg")))
        val handedUp = service(upload, record).drainTerminals()
        assertEquals(listOf("A-primary.jpg" to TerminalOutcome.COMPLETED), record.terminals)
        assertEquals(listOf("acknowledge($destination)"), upload.calls)
        assertTrue(handedUp.isEmpty(), "a terminal fact never crosses to the cycle")
    }

    @Test
    fun `a retry-spent failure whose photo lives is recorded FAILED and handed up for re-creation`() = runTest {
        val live = Resource("A-primary.jpg", AssetId("A"), "image/jpeg", emptyMap(), "handle")
        val upload = ScriptedUpload(terminal = listOf(job(UploadJobState.FAILED)))
        val record = Record(mutableMapOf(destination to row("A-primary.jpg")))
        val handedUp = service(upload, record, Resources(mapOf("A-primary.jpg" to live))).drainTerminals()
        assertEquals(listOf("A-primary.jpg" to TerminalOutcome.FAILED), record.terminals)
        assertEquals(listOf("A-primary.jpg"), handedUp.map { it.key })
        assertEquals("handle", handedUp.single().data, "the photo's live resource, found by its key")
    }

    @Test
    fun `every presented job is acknowledged - an unmappable and a pruned one included`() = runTest {
        val upload = ScriptedUpload(
            terminal = listOf(
                job(UploadJobState.SUCCEEDED, path = null),
                job(UploadJobState.SUCCEEDED, path = "/api/v2/files/devices/D/GONE/primary"),
            ),
        )
        service(upload).drainTerminals()
        assertEquals(2, upload.calls.count { it.startsWith("acknowledge") }, "PhotoKit reports error 50008 otherwise")
    }

    @Test
    fun `an offered retry is re-pointed by the destination its row recorded`() = runTest {
        val upload = ScriptedUpload(offered = listOf(job(UploadJobState.FAILED)))
        val record = Record(mutableMapOf(destination to row("A-primary.jpg")))
        val transfer = service(upload, record)
        val offered = transfer.fetchRetryJobs().single()
        transfer.retryJob(offered, UploadRequest(url, emptyMap(), Resource("A-primary.jpg", AssetId("A"), "image/jpeg", emptyMap(), Unit)))
        assertEquals(listOf("retry($destination -> $url)"), upload.calls)
    }

    @Test
    fun `a destination that is not a URL is not a job before any platform sees it`() = runTest {
        val upload = ScriptedUpload()
        val outcome = service(upload).createJob(
            UploadRequest("", emptyMap(), Resource("A-primary.jpg", AssetId("A"), "image/jpeg", emptyMap(), Unit)),
            Resource("A-primary.jpg", AssetId("A"), "image/jpeg", emptyMap(), Unit),
        )
        assertEquals(UploadCreateOutcome.FAILED, outcome)
        assertTrue(upload.calls.isEmpty())
    }

    @Test
    fun `a file uploader is handed the exported file which goes when no job was created`() = runTest {
        val resource = Resource("A-primary.jpg", AssetId("A"), "image/jpeg", emptyMap(), "handle")
        val upload = ScriptedUpload(accepts = UploadSourceKind.FILE, createAnswer = UploadCreateOutcome.LIMIT_EXCEEDED)
        val library = Library(exportable = setOf("A-primary.jpg"))
        val files = SharedArea()
        val outcome = service(upload, library = library, files = files).createJob(UploadRequest(url, emptyMap(), resource), resource)
        assertEquals(UploadCreateOutcome.LIMIT_EXCEEDED, outcome)
        assertEquals(listOf("A-primary.jpg" to "/shared/upload-staging/A-primary.jpg"), library.exports)
        assertEquals(UploadSource.File("/shared/upload-staging/A-primary.jpg"), upload.created.single().first)
        assertFalse("upload-staging/A-primary.jpg" in files.files, "a creation the platform refused leaves no file")
    }

    @Test
    fun `a resource the library can no longer export is not a job`() = runTest {
        val resource = Resource("A-primary.jpg", AssetId("A"), "image/jpeg", emptyMap(), "handle")
        val upload = ScriptedUpload(accepts = UploadSourceKind.FILE)
        val outcome = service(upload).createJob(UploadRequest(url, emptyMap(), resource), resource)
        assertEquals(UploadCreateOutcome.FAILED, outcome)
        assertTrue(upload.created.isEmpty())
    }

    @Test
    fun `a transfer's end is recorded inline and its staged file goes with it`() {
        val record = Record(mutableMapOf(destination to row("A-primary.jpg")))
        val files = SharedArea().apply { this.files += "upload-staging/A-primary.jpg" }
        val transfer = service(ScriptedUpload(accepts = UploadSourceKind.FILE), record, files = files)
        assertTrue(transfer.recordFinished(job(UploadJobState.SUCCEEDED, tag = "A-primary.jpg")))
        assertEquals(listOf("A-primary.jpg" to TerminalOutcome.COMPLETED), record.terminals)
        assertTrue(files.files.isEmpty())
        val failed = job(UploadJobState.FAILED, tag = "A-primary.jpg")
        assertFalse(transfer.recordFinished(failed), "a row no longer REQUESTED takes nothing: the guard is the store's")
    }

    @Test
    fun `an end with no tag records nothing`() {
        assertFalse(service(ScriptedUpload()).recordFinished(job(UploadJobState.SUCCEEDED, tag = null)))
    }

    @Test
    fun `a leave cancels every in-flight job and its staged file`() = runTest {
        val files = SharedArea().apply { this.files += "upload-staging/A-primary.jpg" }
        val upload = ScriptedUpload(inFlight = listOf(job(UploadJobState.PENDING, tag = "A-primary.jpg")))
        service(upload, files = files).cancelAll()
        assertEquals(listOf("cancel(A-primary.jpg)"), upload.calls)
        assertTrue(files.files.isEmpty())
    }

    @Test
    fun `offered retries whose rows are gone or unmappable are answered and not handed up`() = runTest {
        val upload = ScriptedUpload(
            offered = listOf(
                job(UploadJobState.FAILED, path = null),
                job(UploadJobState.FAILED, path = "/api/v2/files/devices/D/GONE/primary"),
                job(UploadJobState.FAILED, path = "/not/a/byte/route"),
            ),
        )
        assertTrue(service(upload).fetchRetryJobs().isEmpty())
        assertEquals(3, upload.calls.count { it.startsWith("acknowledge") })
    }

    @Test
    fun `a failure the platform still holds the resource of is re-created from it`() = runTest {
        val upload = ScriptedUpload(terminal = listOf(job(UploadJobState.UNKNOWN, source = UploadSource.Resource("own"))))
        val record = Record(mutableMapOf(destination to row("A-primary.jpg")))
        val handedUp = service(upload, record).drainTerminals()
        assertEquals("own", handedUp.single().data, "an untaught state is adjudicated as a failure")
        assertEquals(listOf("A-primary.jpg" to TerminalOutcome.FAILED), record.terminals)
    }

    @Test
    fun `a failure whose photo left is recorded and not handed up`() = runTest {
        val upload = ScriptedUpload(terminal = listOf(job(UploadJobState.FAILED)))
        val record = Record(mutableMapOf(destination to row("A-primary.jpg", state = LedgerState.COMPLETED)))
        assertTrue(service(upload, record).drainTerminals().isEmpty())
        assertTrue(record.terminals.isEmpty(), "a settled row takes no terminal write")
    }

    @Test
    fun `a refused acknowledgement or retry is reported and changes nothing else`() = runTest {
        val refused = ChangeOutcome.Refused(3202, "refused")
        val upload = ScriptedUpload(terminal = listOf(job(UploadJobState.SUCCEEDED)), offered = listOf(job(UploadJobState.FAILED)), changeAnswer = refused)
        val record = Record(mutableMapOf(destination to row("A-primary.jpg")))
        val transfer = service(upload, record)
        transfer.drainTerminals()
        transfer.retryJob(transfer.fetchRetryJobs().single(), UploadRequest(url, emptyMap(), Resource("A-primary.jpg", AssetId("A"), "", emptyMap(), Unit)))
        assertEquals(1, upload.calls.count { it.startsWith("retry") })
    }

    @Test
    fun `a retry finds nothing to re-point once its job settled and never re-points to a bad destination`() = runTest {
        val upload = ScriptedUpload()
        val record = Record(mutableMapOf(destination to row("A-primary.jpg")))
        val gone = app.snapsync.model.PlatformUploadJob("A-primary.jpg", "image/jpeg", null, null)
        val transfer = service(upload, record)
        transfer.retryJob(gone, UploadRequest(url, emptyMap(), Resource("A-primary.jpg", AssetId("A"), "", emptyMap(), Unit)))
        upload.offered = listOf(job(UploadJobState.FAILED))
        transfer.retryJob(gone, UploadRequest("", emptyMap(), Resource("A-primary.jpg", AssetId("A"), "", emptyMap(), Unit)))
        assertTrue(upload.calls.none { it.startsWith("retry") })
    }

    @Test
    fun `a resource uploader is handed the resource itself`() = runTest {
        val resource = Resource("A-primary.jpg", AssetId("A"), "image/jpeg", emptyMap(), "handle")
        val upload = ScriptedUpload(createAnswer = UploadCreateOutcome.LIMIT_EXCEEDED)
        val outcome = service(upload).createJob(UploadRequest(url, emptyMap(), resource), resource)
        assertEquals(UploadCreateOutcome.LIMIT_EXCEEDED, outcome)
        assertTrue((upload.created.single().first as UploadSource.Resource).handle == "handle")
    }

    @Test
    fun `a file uploader with no shared area creates nothing`() = runTest {
        val resource = Resource("A-primary.jpg", AssetId("A"), "image/jpeg", emptyMap(), "handle")
        val upload = ScriptedUpload(accepts = UploadSourceKind.FILE)
        val transfer = service(upload, library = Library(setOf("A-primary.jpg")), files = SharedArea(reachable = false))
        assertEquals(UploadCreateOutcome.FAILED, transfer.createJob(UploadRequest(url, emptyMap(), resource), resource))
        assertTrue(upload.created.isEmpty())
    }

    @Test
    fun `a failed end is recorded as a failure and a job without a tag is still cancelled`() = runTest {
        val record = Record(mutableMapOf(destination to row("A-primary.jpg")))
        assertTrue(service(ScriptedUpload(), record).recordFinished(job(UploadJobState.FAILED, tag = "A-primary.jpg")))
        assertEquals(listOf("A-primary.jpg" to TerminalOutcome.FAILED), record.terminals)
        val upload = ScriptedUpload(inFlight = listOf(job(UploadJobState.PENDING, tag = null)))
        service(upload).cancelAll()
        assertEquals(listOf("cancel(null)"), upload.calls)
    }
}
