package app.snapsync.application.receiving

import app.snapsync.application.membership.Device
import app.snapsync.data.RecordingSecureStore
import app.snapsync.data.backend.exchangeOver
import app.snapsync.data.backend.json
import app.snapsync.data.backend.ok
import app.snapsync.data.crypto.DownloadOpening
import app.snapsync.data.crypto.EventKeyIds
import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.crypto.FileCipher
import app.snapsync.data.crypto.FileNameCipher
import app.snapsync.data.crypto.ServedNames
import app.snapsync.data.membership.EventCheckRecord
import app.snapsync.data.membership.active
import app.snapsync.data.membership.enrolled
import app.snapsync.data.receiving.DOWNLOADS_DB_NAME
import app.snapsync.data.receiving.DownloadDatabaseFile
import app.snapsync.data.receiving.DownloadStore
import app.snapsync.data.receiving.DownloadTransfers
import app.snapsync.data.receiving.EventUnion
import app.snapsync.data.receiving.ImportQueue
import app.snapsync.data.receiving.ImportedReleases
import app.snapsync.data.receiving.LibraryImport
import app.snapsync.data.receiving.LibraryPresence
import app.snapsync.data.receiving.MarkedPhotos
import app.snapsync.data.receiving.ReceivedPhotos
import app.snapsync.data.receiving.ReceivingCleanup
import app.snapsync.data.receiving.ReceivingCounts
import app.snapsync.data.receiving.ReceivingPlans
import app.snapsync.data.receiving.RecognitionPasses
import app.snapsync.data.receiving.RecognitionRecords
import app.snapsync.data.receiving.ReportedReceived
import app.snapsync.data.receiving.StagingArea
import app.snapsync.data.sharing.ManifestRecord
import app.snapsync.data.sharing.SelectionSnapshots
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.receiving.AssetRef
import app.snapsync.domain.receiving.MintsReceivingMembership
import app.snapsync.domain.receiving.MintsRecognised
import app.snapsync.domain.receiving.ReceivedPhotoName
import app.snapsync.domain.receiving.ReceivingMembership
import app.snapsync.domain.receiving.Recognised
import app.snapsync.domain.receiving.SenderFilename
import app.snapsync.format.AssetIdText
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Log
import app.snapsync.inbound.LogLine
import app.snapsync.inbound.LogWriter
import app.snapsync.levers.WriteKind
import app.snapsync.mock.LibraryAssets
import app.snapsync.mock.fakeCrypto
import app.snapsync.mock.inMemoryFiles
import app.snapsync.mock.inMemoryGallery
import app.snapsync.mock.mobileDataAnswering
import app.snapsync.ports.Download
import app.snapsync.ports.DownloadHandlers
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryImport
import app.snapsync.ports.GalleryImportRequest
import app.snapsync.ports.HttpRequest
import app.snapsync.ports.HttpResult
import app.snapsync.ports.ImportOutcome
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.StartResult
import app.snapsync.ports.TransferNetwork
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import kotlinx.coroutines.flow.MutableStateFlow

/** The device that shares the photos this one receives. */
internal const val SENDER = "A0000000-0000-4000-8000-00000000000A"

/** One photo of the union as the backend lists it: [asset] of [device], captured at [created], its primary resource. */
internal class Listed(
    val asset: String,
    val created: String = "2026-07-15T10:00:00Z",
    val device: String = SENDER,
    val filename: String = "IMG_$asset.JPG",
    val contentType: String = "image/jpeg",
    val role: String = "primary",
) {
    val ref: AssetRef get() = AssetRef(testDeviceId(device), testAssetId(asset))

    /** The key the backend derives for older builds — a phone derives the same from the name it holds. */
    val key: String get() = "$asset-$role.${filename.substringAfterLast('.').lowercase()}"
}

/** The platform's background downloads, recorded: each start's tag, answered [answer]. */
internal class RecordingDownload(var answer: StartResult = StartResult.Started) : Download {
    val started = mutableListOf<String>()
    var cancelled = 0

    @CompositionOnly
    override fun inbound(handlers: DownloadHandlers) = Unit

    override fun start(url: String, headers: Map<String, String>, tag: String, network: TransferNetwork): StartResult {
        started += tag
        return answer
    }

    override suspend fun cancelAll() {
        cancelled++
    }
}

/** The library's import, scripted: each request recorded and answered [answer] — the default creates an asset. */
internal class ScriptedImport(
    var answer: (GalleryImportRequest) -> ImportOutcome = {
        ImportOutcome.Imported(PlatformAssetId("imported-${it.correlation.replace('/', '-')}"))
    },
) : GalleryImport {
    val requests = mutableListOf<GalleryImportRequest>()

    override suspend fun import(request: GalleryImportRequest): ImportOutcome {
        requests += request
        return answer(request)
    }
}

/**
 * A device as receiving's use cases see it: the real data layer over the ports' in-memory mocks — `downloads.db`, the
 * shared file area, the library, a recorded download session — and the backend's union and received-position routes
 * behind a scripted transport, serving [listed] at [position] with [declared] photos declared.
 */
@OptIn(AppProcessOnly::class)
internal class Receiver {
    val device = Device()
    val shared = mutableMapOf<String, ByteArray>()
    private val private = mutableMapOf<String, ByteArray>()
    val files = inMemoryFiles(shared = shared, private = private)
    val download = RecordingDownload()
    val file = DownloadDatabaseFile(device.databases.port())
    val staging = StagingArea(files)
    val transfers = DownloadTransfers(
        download,
        mobileDataAnswering { TransferNetwork.ANY },
        exchangeOver(device.transport),
    )
    val store = DownloadStore(file, staging, transfers)
    val plans = ReceivingPlans(store)
    val queue = ImportQueue(store)
    val releases = ImportedReleases(store)
    val records = RecognitionRecords(store)
    val cleanup = ReceivingCleanup(store)
    val counts = ReceivingCounts(store)
    val received = ReceivedPhotos(file)
    val checks = EventCheckRecord(device.shared)

    private val crypto = fakeCrypto()
    val keys = EventKeyStore(RecordingSecureStore(), EventKeyIds(crypto))

    /**
     * The union, netted of what this device declared — nothing yet: its manifest record is empty — its names read as
     * the membership's event serves them.
     */
    val union = EventUnion(device.gateway, ManifestRecord(device.shared), ServedNames(keys, FileNameCipher(crypto)))

    val library = MutableStateFlow<List<GalleryAssetResources>>(emptyList())
    val grant = MutableStateFlow(LibraryGrant.GRANTED)
    val gallery = inMemoryGallery(library, grant)
    val selection = SelectionSnapshots()
    val marked = MarkedPhotos(gallery, selection)
    val presence = LibraryPresence(gallery, selection)
    val importer = ScriptedImport()
    val libraryImport = LibraryImport(importer, staging)
    val passes = RecognitionPasses()
    val reported = ReportedReceived()

    val opening = DownloadOpening(device.membership, keys, FileCipher(crypto, files), files)

    /** The union the backend serves, at [position], with [declared] photos declared (`null`: no total). */
    val listed = mutableListOf<Listed>()
    var position = 0L
    var declared: Long? = null

    /** What the union read answers instead, when set; [received] likewise for the received position. */
    var unionAnswer: HttpResult? = null
    var receivedAnswer: HttpResult = ok()

    init {
        device.transport.answer = ::answered
    }

    private fun answered(request: HttpRequest): HttpResult = when {
        request.url.contains("/received") -> receivedAnswer
        request.url.contains("/files") -> unionAnswer ?: unionReply()
        else -> error("receiving reaches the union and the received position only, not ${request.url}")
    }

    private fun unionReply(): HttpResult = json(
        200,
        listed.joinToString(",", "[", "]") {
            """{"deviceId":"${testDeviceId(it.device).value}","assetId":"${testAssetId(it.asset).value}",""" +
                """"creationDate":"${it.created}","resources":[{"role":"${it.role}",""" +
                """"contentType":"${it.contentType}","key":"${it.key}","filename":"${it.filename}"}]}"""
        },
        listOfNotNull("SnapSync-Cursor" to "$position", declared?.let { "SnapSync-Declared" to "$it" }).toMap(),
    )

    /** The membership joined — entered in the store, as the joined screen and the opening read it. */
    fun joined(membership: Membership.Active = active()): Membership.Active {
        device.membership.enter(enrolled(membership))
        return membership
    }

    /** A temporary file the platform left for a finished transfer, holding [bytes]. */
    fun temporary(name: String, bytes: ByteArray = byteArrayOf(1, 2, 3)): String {
        private[name] = bytes
        return "mem:/private/$name"
    }

    /** The store opened, so a write fault can be armed on it. */
    fun open() {
        counts.stagedImports()
    }

    /** The next [times] statements of [kind] on [table] of the download store fail. */
    fun fail(table: String, kind: WriteKind, times: Int = 1) {
        open()
        device.databases.fail(DOWNLOADS_DB_NAME, table, kind, times)
    }

    /** The download store unreadable — or readable again. */
    fun unreadable(on: Boolean = true) = device.databases.unreadable(DOWNLOADS_DB_NAME, on)

    /** A library photo [id] that carries [of]'s SnapSync mark, captured at [created]. */
    fun markedPhoto(id: String, of: Listed, created: String = of.created): GalleryAssetResources =
        LibraryAssets.photo(
            id,
            created,
            resources = listOf(
                LibraryAssets.primaryResource(
                    filename = ReceivedPhotoName.mark(checkNotNull(SenderFilename.parse(of.filename)), of.ref),
                ),
            ),
        )
}

@OptIn(MintsReceivingMembership::class)
internal fun receiving(membership: Membership.Active = active()): ReceivingMembership = ReceivingMembership(membership)

@OptIn(MintsRecognised::class)
internal fun recognised(membership: Membership.Active = active()): Recognised = Recognised(membership.event.id)

/** The platform id an imported asset of [asset] answers with, as [ScriptedImport] creates it. */
internal fun importedId(asset: String, device: String = SENDER): AssetId =
    AssetIdText.fromPlatform("imported-${testDeviceId(device).value}-${testAssetId(asset).value}")

/** The log lines of [tag], recorded while [block] runs. */
@OptIn(CompositionOnly::class)
internal suspend fun linesOf(tag: String, block: suspend () -> Unit): List<LogLine> {
    val lines = mutableListOf<LogLine>()
    Log.install(
        listOf(object : LogWriter {
            override fun write(line: LogLine) {
                lines += line
            }
        }),
    )
    try {
        block()
    } finally {
        Log.install(emptyList())
    }
    return lines.filter { it.tag == tag }
}

/** The message of each line. */
internal val List<LogLine>.messages: List<String> get() = map { it.event }
