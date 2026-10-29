package app.snapsync.services.upload

import app.snapsync.data.crypto.EventKeyIds
import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.crypto.FileCipher
import app.snapsync.data.crypto.UploadSealing
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.store.SharedDatabase
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.Encryption
import app.snapsync.domain.event.Event
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventName
import app.snapsync.domain.event.EventNameVerdict
import app.snapsync.domain.event.EventRange
import app.snapsync.domain.event.EventRangeVerdict
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.membership.Enrolled
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.MintsEnrolled
import app.snapsync.domain.membership.Share
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.CaptureRangeVerdict
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.time.Timestamp
import app.snapsync.domain.upload.LedgerState
import app.snapsync.mock.fakeCrypto
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.mock.inMemoryFiles
import app.snapsync.mock.inMemoryPreferences
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.model.LedgerEntry
import app.snapsync.model.Resource
import app.snapsync.model.TerminalOutcome
import app.snapsync.model.UploadRequest
import app.snapsync.ports.AssetFilter
import app.snapsync.ports.FileAccess
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileLocation
import app.snapsync.ports.FileResult
import app.snapsync.ports.FileTail
import app.snapsync.ports.Files
import app.snapsync.ports.GalleryAlbum
import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.LibraryAlbumKind
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAlbumId
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.TransferNetwork
import app.snapsync.ports.WriteOutcome
import app.snapsync.services.databases.LegacyRecords
import app.snapsync.services.gallery.GalleryResourceRef
import app.snapsync.services.gallery.KeyedRead
import app.snapsync.services.gallery.KeyedResources
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import kotlin.time.Instant

// The doubles both transfers' tests share: a ledger, the library, the shared area — each recording what it was asked.

internal const val DESTINATION = "/api/v2/files/devices/D/A/primary"
internal const val URL = "https://edge.example$DESTINATION"
internal const val KEY = "A-primary.jpg"

/** A ledger of rows by destination, recording the guarded terminal writes — applied only to a REQUESTED row. */
internal class Record(val rows: MutableMap<String, LedgerEntry> = mutableMapOf()) : TransferRecord {
    val terminals = mutableListOf<Pair<String, TerminalOutcome>>()

    override suspend fun entryForDestination(destinationPath: String): LedgerEntry? = rows[destinationPath]

    override suspend fun entryForKey(key: String): LedgerEntry? = rows.values.firstOrNull { it.key == key }

    override fun markTerminal(key: String, outcome: TerminalOutcome): Boolean {
        val (at, row) = rows.entries.firstOrNull { it.value.key == key }?.toPair() ?: return false
        if (row.state != LedgerState.REQUESTED) return false
        terminals += key to outcome
        val settled = if (outcome == TerminalOutcome.COMPLETED) LedgerState.COMPLETED else LedgerState.DISCOVERED
        rows[at] = LedgerEntry(key = row.key, assetId = row.assetId, state = settled, destinationPath = row.destinationPath)
        return true
    }
}

/** The one row these tests upload, at [DESTINATION]. */
internal fun rowA(state: LedgerState = LedgerState.REQUESTED) =
    Record(
        mutableMapOf(
            DESTINATION to LedgerEntry(key = KEY, assetId = testAssetId("A"), state = state, destinationPath = DESTINATION),
        ),
    )

/** The live resources the cycle's by-key resolve finds; while not [readable] the library cannot be read at all. */
internal class Resources(val live: Map<String, Resource> = emptyMap(), var readable: Boolean = true) : KeyedResources {
    var calls = 0

    override suspend fun resourcesFor(keys: Set<String>): KeyedRead {
        calls++
        return if (readable) KeyedRead.Resolved(keys.mapNotNull(live::get)) else KeyedRead.NotReadable
    }
}

/** The test library's handle of a resource: the key it was read under. */
internal data class KeyHandle(val key: String) : GalleryResourceHandle

/** A photo [key] as the library read it, carrying its own handle, of [contentType]. */
internal fun libraryResource(key: String = KEY, contentType: String = "image/jpeg") =
    Resource(key, testAssetId(key.substringBefore('-')), contentType, emptyMap(), GalleryResourceRef(KeyHandle(key)))

/** A photo [key] the library did not read: it carries no handle. */
internal fun unreadResource(key: String = KEY, contentType: String = "image/jpeg") =
    Resource(key, testAssetId(key.substringBefore('-')), contentType, emptyMap(), null)

/** A library that exports only [exportable] keys, recording where it wrote. */
internal class Library(val exportable: Set<String> = emptySet()) : GalleryReader {
    val exports = mutableListOf<Pair<String, String>>()
    override fun access() = LibraryGrant.GRANTED
    override suspend fun assets(filter: AssetFilter): GalleryRead<List<GalleryAsset>> = GalleryRead.Read(emptyList())
    override suspend fun assetsById(ids: Set<PlatformAssetId>): GalleryRead<List<GalleryAsset>> =
        GalleryRead.Read(emptyList())
    override suspend fun resources(ids: Set<PlatformAssetId>): GalleryRead<List<GalleryAssetResources>> =
        GalleryRead.Read(emptyList())
    override suspend fun albums(): GalleryRead<List<GalleryAlbum>> = GalleryRead.Read(emptyList())
    override suspend fun albumsById(ids: Set<PlatformAlbumId>): GalleryRead<List<GalleryAlbum>> =
        GalleryRead.Read(emptyList())
    override suspend fun albumMembers(
        album: PlatformAlbumId,
        createdFrom: Instant?,
    ): GalleryRead<Set<PlatformAssetId>> =
        GalleryRead.Read(emptySet())
    override val albumKind: LibraryAlbumKind = LibraryAlbumKind.COLLECTION
    override suspend fun createAlbum(title: String): PlatformAlbumId? = null
    override suspend fun addToAlbum(album: PlatformAlbumId, assets: Set<PlatformAssetId>) = WriteOutcome.Ok
    override suspend fun export(resource: GalleryResourceHandle, to: String): WriteOutcome {
        val key = (resource as KeyHandle).key
        if (key !in exportable) return WriteOutcome.Failed("gone")
        exports += key to to
        return WriteOutcome.Ok
    }
}

/** The shared area as a set of paths, located under `/shared/` — or unreachable when [reachable] is off. */
internal class SharedArea(
    private val reachable: Boolean = true,
    private val deletable: Boolean = true,
    /** Paths the listing names that are gone by the time they are deleted — another path released them. */
    private val vanishing: Set<String> = emptySet(),
) : Files {
    val files = mutableSetOf<String>()
    override fun read(area: FileArea, path: String): FileResult<ByteArray> = FileResult.NotFound
    override fun readTail(area: FileArea, path: String, maxBytes: Int): FileResult<FileTail> = FileResult.NotFound
    override fun readRange(area: FileArea, path: String, offset: Long, maxBytes: Int): FileResult<ByteArray> =
        FileResult.NotFound
    override fun append(area: FileArea, path: String, bytes: ByteArray) = FileResult.Ok(Unit).also { files += path }
    override fun write(area: FileArea, path: String, bytes: ByteArray) = FileResult.Ok(Unit).also { files += path }
    override fun delete(area: FileArea, path: String): FileResult<Unit> = when {
        !reachable -> FileResult.AreaUnavailable
        !deletable -> FileResult.Denied("locked")
        files.remove(path) -> FileResult.Ok(Unit)
        else -> FileResult.NotFound
    }
    override fun exists(area: FileArea, path: String) = FileResult.Ok(path in files)
    override fun locate(area: FileArea, path: String): FileLocation<String> =
        if (reachable) FileResult.Ok("/shared/$path") else FileResult.AreaUnavailable
    override fun move(area: FileArea, from: String, to: String): FileResult<Unit> = FileResult.NotFound
    override fun adopt(osPath: String, area: FileArea, to: String): FileResult<Unit> = FileResult.NotFound
    override fun list(area: FileArea, directory: String): FileAccess<List<String>> =
        if (reachable) {
            FileResult.Ok((files + vanishing).filter { it.startsWith("$directory/") }.sorted())
        } else {
            FileResult.AreaUnavailable
        }
}

/** A transfer's collaborators, every one a double unless given. */
internal fun setup(
    record: TransferRecord = Record(),
    resources: KeyedResources = Resources(),
    library: GalleryReader = Library(),
    files: Files = SharedArea(),
    network: () -> TransferNetwork = { TransferNetwork.ANY },
    sealing: UploadSealing = plainSealing(),
) = TransferSetup(record, resources, library, files, network, sealing)

/** The event the transfers' requests address. */
internal val TEST_EVENT: EventId = EventId.parse("0f0e8a5c-3f1b-4c2d-9e7a-1b2c3d4e5f60")!!

/** [resource]'s request to [url], for [TEST_EVENT]. */
internal fun request(resource: Resource, url: String = URL, headers: Map<String, String> = emptyMap()) =
    UploadRequest(url, headers, resource, TEST_EVENT)

/** A sealing for a member of the plain [TEST_EVENT]: everything goes up as it is. */
@OptIn(MintsEnrolled::class)
internal fun plainSealing(): UploadSealing {
    val membership = MembershipRepository(
        SharedDatabase(inMemoryDatabases(), LegacyRecords(inMemoryFiles(), inMemoryPreferences())),
    )
    val range = (
        EventRange.of(
            EventStart(Timestamp.of(START)),
            EventEnd(Timestamp.of(END)),
        ) as EventRangeVerdict.Ok
        ).range
    val event = Event(
        id = TEST_EVENT,
        name = (EventName.of("Plain") as EventNameVerdict.Ok).name,
        range = range,
        openUntil = null,
        deletesAt = DeletesAt(Timestamp.of(END)),
        encryption = Encryption.Plain,
        members = null,
    )
    val share = (
        CaptureRange.of(
            CaptureCutoff(Timestamp.of(START)),
            CaptureCeiling(Timestamp.of(END)),
            range.start,
            range.end,
        ) as CaptureRangeVerdict.Ok
        ).range
    membership.enter(Enrolled(Membership.Active(event, Share(share, Direction.Both, saveToAlbum = false))))
    return UploadSealing(
        membership,
        EventKeyStore(inMemorySecureStore(), EventKeyIds(fakeCrypto())),
        object : DeviceIdentity {
            override fun id(): DeviceIdRead = DeviceIdRead.Ok(testDeviceId(DEVICE), DeviceIdRead.Via.READ)
        },
        FileCipher(fakeCrypto(), inMemoryFiles()),
    )
}

private val START = Instant.parse("2026-07-14T18:00:00Z")
private val END = Instant.parse("2026-07-21T18:00:00Z")
private const val DEVICE = "11111111-0000-4000-8000-000000000002"
