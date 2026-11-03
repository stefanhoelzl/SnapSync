package app.snapsync.data.receiving

import app.cash.sqldelight.db.QueryResult
import app.snapsync.data.backend.ScriptedTransport
import app.snapsync.data.backend.exchangeOver
import app.snapsync.data.receiving.db.DownloadDatabase
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.receiving.AssetRef
import app.snapsync.domain.receiving.UnionPage
import app.snapsync.domain.receiving.UnionPhoto
import app.snapsync.domain.receiving.UnionResource
import app.snapsync.format.TimestampText
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.inbound.CompositionOnly
import app.snapsync.mock.DatabasesMock
import app.snapsync.mock.FileSystemMock
import app.snapsync.mock.mobileDataAnswering
import app.snapsync.ports.DbOpen
import app.snapsync.ports.DbResult
import app.snapsync.ports.Download
import app.snapsync.ports.DownloadHandlers
import app.snapsync.ports.FileAccess
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileLocation
import app.snapsync.ports.FileResult
import app.snapsync.ports.Files
import app.snapsync.ports.StartResult
import app.snapsync.ports.TransferNetwork
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import kotlin.test.assertIs

/** This device, and the others whose photos it receives. */
internal val ME = testDeviceId("me")
internal val ANNA = testDeviceId("anna")
internal val BEN = testDeviceId("ben")

internal val EVENT = testEventId("event")
internal val OTHER_EVENT = testEventId("other-event")

/** The ref of [asset] on [device]. */
internal fun ref(
    asset: String,
    device: app.snapsync.domain.device.DeviceId = ANNA,
) = AssetRef(device, testAssetId(asset))

internal const val DURING = "2026-07-15T10:00:00Z"

internal fun captured(text: String = DURING): CaptureDate = TimestampText.captureDate(text)

/** A resource of a union photo: its still, unless told otherwise. */
internal fun resource(
    key: String,
    role: String = "primary",
    contentType: String = "image/heic",
    filename: String = "IMG_1.HEIC",
    url: String = "https://backend.test/bytes/$key",
) = UnionResource(key, url, role, contentType, filename)

/** A union photo of [ref], its one still keyed after the asset. */
internal fun photo(
    ref: AssetRef,
    captured: CaptureDate = captured(),
    vararg resources: UnionResource = arrayOf(resource("${ref.sourceAssetId}-primary.heic")),
) = UnionPhoto(ref, captured, resources.toList())

internal fun page(vararg photos: UnionPhoto, position: Long = 1, othersDeclared: Long? = null) =
    UnionPage(photos.toList(), position, othersDeclared)

/** The platform's download session, scripted: every start answered by [answer], every call recorded. */
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

/** The device's files over the mock's disk, with any one answer a test overrides. */
internal class ScriptedFiles(val disk: FileSystemMock = FileSystemMock()) : Files by disk.port() {
    var adopt: FileResult<Unit>? = null
    var locate: FileLocation<String>? = null
    var list: FileAccess<List<String>>? = null
    var exists: FileAccess<Boolean>? = null
    var delete: FileResult<Unit>? = null
    private val files = disk.port()

    override fun adopt(osPath: String, area: FileArea, to: String) = adopt ?: files.adopt(osPath, area, to)

    override fun locate(area: FileArea, path: String) = locate ?: files.locate(area, path)

    override fun list(area: FileArea, directory: String) = list ?: files.list(area, directory)

    override fun exists(area: FileArea, path: String) = exists ?: files.exists(area, path)

    override fun delete(area: FileArea, path: String) = delete ?: files.delete(area, path)

    /** A staged file at [path], holding a byte. */
    fun staged(path: String) = disk.write(FileArea.SHARED, path, byteArrayOf(1))

    fun has(path: String): Boolean = disk.read(FileArea.SHARED, path) != null

    /** A file the platform left at a temporary path, as a finished download leaves one. */
    fun temp(name: String): String {
        disk.write(FileArea.PRIVATE, "tmp/$name", byteArrayOf(2))
        return assertIs<FileResult.Ok<String>>(files.locate(FileArea.PRIVATE, "tmp/$name")).value
    }
}

/** The app's download store over in-memory ports, and every class over it. */
@OptIn(AppProcessOnly::class)
internal class Store(val databases: DatabasesMock = DatabasesMock()) {
    val files = ScriptedFiles()
    val download = RecordingDownload()
    var network = TransferNetwork.ANY
    val transfers = DownloadTransfers(download, mobileDataAnswering { network }, exchangeOver(ScriptedTransport()))
    val staging = StagingArea(files)
    val file = DownloadDatabaseFile(databases.port())
    val downloads = DownloadStore(file, staging, transfers)
    val plans = ReceivingPlans(downloads)
    val queue = ImportQueue(downloads)
    val releases = ImportedReleases(downloads)
    val records = RecognitionRecords(downloads)
    val cleanup = ReceivingCleanup(downloads)
    val counts = ReceivingCounts(downloads)
    val received = ReceivedPhotos(file)
    val markers = ImportMarkerWrites(file)

    /** Run [sql] on the store's database, as an older build or another writer would leave it. */
    fun sql(vararg sql: String) {
        counts.census() // the app's own open creates and migrates it first
        val open = assertIs<DbOpen.Opened>(databases.port().open(DOWNLOADS_DB_NAME, DownloadDatabase.Schema, false))
        sql.forEach { statement ->
            assertIs<DbResult.Ok<*>>(open.database.execute { it.execute(null, statement, 0) })
        }
    }

    /** The first column of every row [sql] answers, as text (`null` for SQL NULL). */
    fun strings(sql: String): List<String?> {
        val open = assertIs<DbOpen.Opened>(databases.port().open(DOWNLOADS_DB_NAME, DownloadDatabase.Schema, false))
        val read = open.database.execute { driver ->
            driver.executeQuery(null, sql, { cursor ->
                QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getString(0)) })
            }, 0).value
        }
        return assertIs<DbResult.Ok<List<String?>>>(read).value
    }

    /** [page] planned for [EVENT] as a whole read by this device. */
    suspend fun planned(page: UnionPage, plan: app.snapsync.domain.receiving.UnionReadPlan = WHOLE): Planned =
        assertIs<DownloadRead.Ok<Planned>>(
            plans.plan(EVENT, page, plan, ME, assertIs<DownloadRead.Ok<StoredPosition>>(plans.position(EVENT)).value),
        ).value

    /** Every resource of [photo] staged, at the path its transfer names. */
    suspend fun stagedAll(photo: UnionPhoto, event: String = EVENT.value) = photo.resources.forEach { r ->
        val tag = TransferTag(photo.ref, r.key, app.snapsync.domain.receiving.StagingFolder.of(event))
        files.staged(tag.stagedAt.value)
        assertIs<DownloadRead.Ok<StageRecord>>(queue.staged(photo.ref, r.key, tag.stagedAt))
    }

    fun state(ref: AssetRef): String? = strings(
        "SELECT state FROM downloadAsset WHERE sourceDeviceId = '${ref.sourceDeviceId.value}' " +
            "AND sourceAssetId = '${ref.sourceAssetId.value}'",
    ).singleOrNull()

    fun marker(ref: AssetRef): String? = strings(
        "SELECT createdLocalId FROM downloadAsset WHERE sourceDeviceId = '${ref.sourceDeviceId.value}' " +
            "AND sourceAssetId = '${ref.sourceAssetId.value}'",
    ).singleOrNull()
}

internal val WHOLE = app.snapsync.domain.receiving.UnionReadPlan.Whole

/** The value of an Ok read. */
internal fun <T> DownloadRead<T>.ok(): T = assertIs<DownloadRead.Ok<T>>(this).value
