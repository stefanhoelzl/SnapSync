package app.snapsync.services.upload

import app.snapsync.domain.upload.LedgerState
import app.snapsync.model.CandidateScope
import app.snapsync.model.FileAccess
import app.snapsync.model.FileArea
import app.snapsync.model.FileLocation
import app.snapsync.model.FileResult
import app.snapsync.model.FileTail
import app.snapsync.model.LedgerEntry
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.TerminalOutcome
import app.snapsync.ports.AssetFilter
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
import app.snapsync.services.gallery.Discovery
import app.snapsync.services.gallery.GalleryResourceRef
import app.snapsync.services.gallery.UploadDiscovery
import app.snapsync.testids.testAssetId
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

/** The live resources the cycle's by-key resolve finds. */
internal class Resources(val live: Map<String, Resource> = emptyMap()) : UploadDiscovery {
    override suspend fun discover(policy: SelectionPolicy) =
        Discovery(emptyList(), fullEnumeration = false, scope = CandidateScope.Library)
    override suspend fun resourcesFor(keys: Set<String>) = keys.mapNotNull(live::get)
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
    resources: UploadDiscovery = Resources(),
    library: GalleryReader = Library(),
    files: Files = SharedArea(),
    network: () -> TransferNetwork = { TransferNetwork.ANY },
) = TransferSetup(record, resources, library, files, network)
