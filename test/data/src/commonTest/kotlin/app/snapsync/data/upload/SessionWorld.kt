package app.snapsync.data.upload

import app.snapsync.application.membership.FixedIdentity
import app.snapsync.data.Held
import app.snapsync.data.RecordingSecureStore
import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.ScriptedCredential
import app.snapsync.data.backend.ScriptedTransport
import app.snapsync.data.backend.exchangeOver
import app.snapsync.data.backend.ok
import app.snapsync.data.crypto.EventKeyIds
import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.crypto.FileCipher
import app.snapsync.data.crypto.UploadSealing
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.membership.active
import app.snapsync.data.membership.enrolled
import app.snapsync.data.membership.sharedOver
import app.snapsync.data.token
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.event.Encryption
import app.snapsync.domain.event.KeyToKeep
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.upload.FoundResource
import app.snapsync.domain.upload.RowDetail
import app.snapsync.domain.upload.UploadKey
import app.snapsync.domain.upload.UploadRow
import app.snapsync.format.UploadKeyText
import app.snapsync.inbound.CompositionOnly
import app.snapsync.mock.DatabasesMock
import app.snapsync.mock.fakeCrypto
import app.snapsync.mock.inMemoryGallery
import app.snapsync.mock.mobileDataAnswering
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.FileAccess
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileLocation
import app.snapsync.ports.FileResult
import app.snapsync.ports.FileTail
import app.snapsync.ports.Files
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.HttpResult
import app.snapsync.ports.Transfer
import app.snapsync.ports.TransferHandle
import app.snapsync.ports.TransferHandlers
import app.snapsync.ports.TransferNetwork
import app.snapsync.ports.TransferSession
import app.snapsync.ports.TransferSource
import app.snapsync.ports.UploadTarget
import app.snapsync.ports.WriteOutcome
import app.snapsync.testids.testDeviceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.assertIs

/**
 * One device's session tier over fakes it can steer: the shared area's files, a library whose export writes [bytes],
 * a session that records what it was asked to make, the backend's upload-state answer, and the real ledger, sealing and
 * request builder. A row is discovered first with [discovered].
 */
internal class SessionWorld(takesResources: Boolean = false) {
    val databases = DatabasesMock()
    val shared = sharedOver(databases)
    val membership = MembershipRepository(shared)
    val identity = FixedIdentity(DeviceIdRead.Ok(testDeviceId("D1"), DeviceIdRead.Via.READ))
    val crypto = fakeCrypto()
    val ids = EventKeyIds(crypto)
    val keys = EventKeyStore(RecordingSecureStore(), ids)
    val files = MemFiles()
    val library = ExportingLibrary(files)
    val session = RecordingSession(takesResources)
    val transport = ScriptedTransport { ok(404) }
    var network = TransferNetwork.ANY

    val reads = OwnUploadRows(shared)
    val walk = WalkRecord(shared)
    val transitions = RowTransitions(shared)
    val record = JobRecord(transitions)
    val staged = StagedFiles(files)
    val digests = StagedDigests(files, crypto, staged)
    val sealing = UploadSealing(membership, keys, identity, FileCipher(crypto, files))
    val export = UploadExport(library, files, sealing, staged)
    val standings = UploadStandings(BackendGateway(exchangeOver(transport), ScriptedCredential("T1")), identity)
    val create = SessionCreate(session, mobileDataAnswering { network }, record)
    val fromFiles = FileUploads(staged, digests, export, standings, create)
    val requests = UploadRequests(app.snapsync.data.FakeClient().exchange(), Held(token = token(30)).store, identity)
    val tier = SessionTier(session, requests, sealing, create, fromFiles)
    val ends = SessionEnds(session, staged, record)
    val sweep = StagingSweep(files, staged, reads, session, fromFiles)

    val event = active().event.id

    fun joined(encryption: Encryption = Encryption.Plain) {
        val plain = active()
        membership.enter(enrolled(Membership.Active(plain.event.copy(encryption = encryption), plain.share)))
    }

    /** An encrypted event this device holds the key of. */
    fun joinedEncrypted() {
        val key = ids.mint()
        joined(Encryption.Encrypted(key.id))
        keys.keep(KeyToKeep.Key(key), event)
    }

    fun key(asset: String = "A1"): UploadKey = UploadKey.of(AssetId.parse(asset)!!, ResourceRole.PRIMARY, "IMG.MOV")

    fun tag(asset: String = "A1"): String = UploadKeyText.of(key(asset))

    /** [asset]'s row, recorded as the walk records it, and read back as needing a job. */
    fun discovered(asset: String = "A1"): UploadRow.Discovered {
        walk.discover(
            event,
            listOf(FoundResource(key(asset), RowDetail(CaptureDate.Undated, "video/quicktime", "IMG.MOV"))),
        )
        return row(asset) as UploadRow.Discovered
    }

    fun row(asset: String = "A1"): UploadRow? =
        assertIs<RowsRead.Read>(reads.rows(event)).rows.firstOrNull { it.key == key(asset) }

    fun standing(answer: HttpResult) {
        transport.answer = { answer }
    }

    object Handle : GalleryResourceHandle

    companion object {
        /** Where a test's uploads send from: [Handle]. */
        val From = SendFrom(Handle)
    }
}

/** The shared area as a map, with switches for what the area cannot do now. */
internal class MemFiles : Files {
    val held = mutableMapOf<String, ByteArray>()
    var unplaced = false
    var unreadable = false
    var unlistable = false
    var existsFails = false
    val unplacedSuffixes = mutableSetOf<String>()
    val undeletable = mutableSetOf<String>()

    override fun read(area: FileArea, path: String): FileResult<ByteArray> =
        held[path]?.let { FileResult.Ok(it) } ?: FileResult.NotFound

    override fun readTail(area: FileArea, path: String, maxBytes: Int): FileResult<FileTail> = FileResult.NotFound

    override fun readRange(area: FileArea, path: String, offset: Long, maxBytes: Int): FileResult<ByteArray> {
        val all = held[path] ?: return FileResult.NotFound
        if (unreadable) return FileResult.Failed("I/O error")
        val from = offset.toInt().coerceAtMost(all.size)
        return FileResult.Ok(all.copyOfRange(from, (from + maxBytes).coerceAtMost(all.size)))
    }

    override fun write(area: FileArea, path: String, bytes: ByteArray): FileAccess<Unit> {
        held[path] = bytes
        return FileResult.Ok(Unit)
    }

    override fun append(area: FileArea, path: String, bytes: ByteArray): FileAccess<Unit> =
        write(area, path, (held[path] ?: ByteArray(0)) + bytes)

    override fun delete(area: FileArea, path: String): FileResult<Unit> = when {
        path in undeletable -> FileResult.Denied("in use")
        held.remove(path) != null -> FileResult.Ok(Unit)
        else -> FileResult.NotFound
    }

    override fun exists(area: FileArea, path: String): FileAccess<Boolean> =
        if (existsFails) FileResult.Failed("unreadable") else FileResult.Ok(path in held)

    override fun locate(area: FileArea, path: String): FileLocation<String> =
        if (unplaced || unplacedSuffixes.any(path::endsWith)) FileResult.AreaUnavailable else FileResult.Ok("/os/$path")

    override fun move(area: FileArea, from: String, to: String): FileResult<Unit> =
        held.remove(from)?.let {
            held[to] = it
            FileResult.Ok(Unit)
        } ?: FileResult.NotFound

    override fun adopt(osPath: String, area: FileArea, to: String): FileResult<Unit> = FileResult.NotFound

    override fun list(area: FileArea, directory: String): FileAccess<List<String>> =
        if (unlistable) {
            FileResult.Failed(
                "unreadable",
            )
        } else {
            FileResult.Ok(held.keys.filter { it.startsWith("$directory/") })
        }
}

/** A library whose export writes [bytes] where it was asked to, or fails when [fails]. */
internal class ExportingLibrary(private val files: MemFiles) :
    GalleryReader by inMemoryGallery(MutableStateFlow(emptyList())) {
    var bytes = ByteArray(10_000) { (it * 7).toByte() }
    var fails = false
    var writes = true
    var exports = 0

    override suspend fun export(resource: GalleryResourceHandle, to: String): WriteOutcome {
        exports++
        if (fails) return WriteOutcome.Failed("gone")
        if (writes) files.held[to.removePrefix("/os/")] = bytes else files.held.remove(to.removePrefix("/os/"))
        return WriteOutcome.Ok
    }
}

/** A session recording each create, answering [answer], holding [live]. */
internal class RecordingSession(override val takesResources: Boolean) : TransferSession {
    class Made(val source: TransferSource, val target: UploadTarget, val tag: String)

    val made = mutableListOf<Made>()
    val cancelled = mutableListOf<Transfer>()
    var answer = CreateOutcome.CREATED
    var live: List<Transfer> = emptyList()

    @CompositionOnly
    override fun inbound(handlers: TransferHandlers) = Unit

    override suspend fun create(source: TransferSource, target: UploadTarget, tag: String): CreateOutcome {
        made += Made(source, target, tag)
        return answer
    }

    override suspend fun live(): List<Transfer> = live

    override suspend fun cancel(transfer: Transfer) {
        cancelled += transfer
    }

    object Handle : TransferHandle
}

/** The OS's upload queue, recording what it was asked and presenting what a test sets. */
internal class RecordingQueue : app.snapsync.ports.OsUploadQueue {
    val created = mutableListOf<Pair<GalleryResourceHandle, UploadTarget>>()
    val retried = mutableListOf<Pair<app.snapsync.ports.OsUploadJob, UploadTarget>>()
    val acknowledged = mutableListOf<app.snapsync.ports.OsUploadJob>()
    var answer = CreateOutcome.CREATED
    var change: app.snapsync.ports.ChangeOutcome = app.snapsync.ports.ChangeOutcome.Applied
    var offered: List<app.snapsync.ports.OsUploadJob> = emptyList()
    var terminals: List<app.snapsync.ports.OsUploadJob> = emptyList()

    override suspend fun create(resource: GalleryResourceHandle, target: UploadTarget): CreateOutcome {
        created += resource to target
        return answer
    }

    override suspend fun retryOffered(): List<app.snapsync.ports.OsUploadJob> = offered

    override suspend fun terminals(): List<app.snapsync.ports.OsUploadJob> = terminals

    override suspend fun retry(job: app.snapsync.ports.OsUploadJob, target: UploadTarget): app.snapsync.ports.ChangeOutcome {
        retried += job to target
        return change
    }

    override suspend fun acknowledge(job: app.snapsync.ports.OsUploadJob): app.snapsync.ports.ChangeOutcome {
        acknowledged += job
        return change
    }

    object JobHandle : app.snapsync.ports.OsUploadJobHandle
}
