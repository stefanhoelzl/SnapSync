package app.snapsync.mock

import app.snapsync.levers.AlbumKind
import app.snapsync.levers.AppAlbum
import app.snapsync.levers.HeldPhoto
import app.snapsync.levers.ImportsView
import app.snapsync.levers.LibraryLevers
import app.snapsync.levers.LibraryPhotoKind
import app.snapsync.levers.LibraryView
import app.snapsync.levers.NOT_AN_IMAGE
import app.snapsync.levers.ParkedImport
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.model.AlbumId
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.LibraryAlbumKind
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.SelectionSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile

/**
 * **The photo library's mock** (`docs/testing.md`, "Mocks"): the library the device keeps — its assets, the grant,
 * the albums, what was imported — which survives a relaunch of the app; the [port] face a process
 * reads it through; and the [operator] face that plays the person holding the phone and the library's own answers.
 *
 * The cells and writes beside the faces are plumbing between the mocks and the tools that compose them (the rig's
 * seed and gallery read, the desktop inspector, the contract bindings): the library in the platform's own vocabulary,
 * which no typed service carries.
 */
class PhotoLibraryMock(
    access: LibraryGrant = LibraryGrant.GRANTED,
    /** What the person answers the permission dialog with, the one time it is asked. */
    requestAnswer: LibraryGrant = LibraryGrant.GRANTED,
    /** How this library holds an album: an iPhone's [LibraryAlbumKind.COLLECTION] or an Android phone's. */
    albumKind: LibraryAlbumKind = LibraryAlbumKind.COLLECTION,
    /** The device's disk, where the app stages a file for an import; `null`: every file this library is handed decodes. */
    disk: FileSystemMock? = null,
) {
    internal val state = LibraryState(
        library = MutableStateFlow(emptyList()),
        access = MutableStateFlow(access),
        userAlbums = MutableStateFlow(emptyMap()),
        answer = requestAnswer,
        answers = null,
        disk = disk,
    ).also { it.albumKind = albumKind }

    /** One process's face of the library. */
    fun port(): Gallery = InMemoryGallery(state)

    val operator: PhotoLibraryOperator = PhotoLibraryOperator(state)

    /** The whole library, unscoped, as a cell — the app has no unbounded walk. */
    val contents: StateFlow<List<GalleryAssetResources>> = state.library.asStateFlow()

    /** The grant, as a cell. */
    val grant: StateFlow<LibraryGrant> = state.access.asStateFlow()

    /** The person's partial selection as they last picked it, as a cell — `null` until they have. */
    val selection: StateFlow<List<GalleryAssetResources>?> = state.selection.asStateFlow()

    /** Whether the running process's selection observer is open — the operating system's own record of it. */
    val selectionObserved: Boolean get() = state.observer != null

    /** The albums the app made, as the operator reads them — without suspending, for a settle's snapshot. */
    val appAlbums: List<AppAlbum> get() = operator.appAlbums()

    /** Every photo the library holds, by id, in the order it came — the person's view of it. */
    val photoIds: List<String> get() = state.library.value.map { it.id.value }

    /** How many times the app presented the limited-library picker — the operating system's own record of it. */
    val pickersShown: Int get() = state.pickersShown

    // The app imports into the same cell from its own thread: each change is one atomic update, never a read and a
    // write another change can land between.
    /** [asset], described as the platform describes it, is in the library from now on. */
    fun add(asset: GalleryAssetResources) {
        state.library.update { it + asset }
    }

    /** The library becomes exactly [assets]. */
    fun set(assets: List<GalleryAssetResources>) {
        state.library.value = assets
    }

    /**
     * Tells [listener] what this library records as it records it — each walk, add and import an operator's hold parks,
     * each photo an import creates, each album the app makes and each photo that joins one — on the thread that did it.
     * One listener: the control channel's event stream (`docs/testing.md`, "How a test waits").
     */
    fun watch(listener: LibraryWatcher) {
        state.watcher = listener
    }
}

/** Told what the library records, on the thread that recorded it, before that thread goes on. */
fun interface LibraryWatcher {
    fun told(event: WorldEvent.Library)
}

/**
 * What the person holding the phone — and the photo library itself — can do, and what a test can read of it
 * ([LibraryLevers]). None of it is on the [Gallery] port.
 */
class PhotoLibraryOperator internal constructor(private val state: LibraryState) : LibraryLevers {

    // ---- the library ------------------------------------------------------------------------------

    override suspend fun add(id: String, kind: LibraryPhotoKind?, date: String?) {
        val at = date ?: LibraryAssets.DEFAULT_DATE
        val asset = when (kind ?: LibraryPhotoKind.PHOTO) {
            LibraryPhotoKind.PHOTO -> LibraryAssets.photo(id, creationDate = at)
            LibraryPhotoKind.LOW_RES -> LibraryAssets.lowResPhoto(id, at)
            LibraryPhotoKind.SCREENSHOT -> LibraryAssets.screenshot(id, at)
            LibraryPhotoKind.SCREEN_RECORDING -> LibraryAssets.screenRecording(id, at)
            LibraryPhotoKind.HD_VIDEO -> LibraryAssets.hdVideo(id, at)
            LibraryPhotoKind.NARROW_VIDEO -> LibraryAssets.narrowVideo(id, at)
            LibraryPhotoKind.LIVE_PHOTO -> LibraryAssets.livePhoto(id, at)
            LibraryPhotoKind.GIF -> LibraryAssets.gif(id, at)
            LibraryPhotoKind.PANORAMA -> LibraryAssets.panorama(id, at)
            LibraryPhotoKind.CROPPED -> LibraryAssets.croppedPhoto(id, at)
            LibraryPhotoKind.RECEIVED_FULL_RES -> LibraryAssets.receivedFullResPhoto(id, at)
            LibraryPhotoKind.MOTION_PHOTO -> LibraryAssets.motionPhoto(id, at)
        }
        state.library.update { it + asset }
    }

    override suspend fun remove(asset: String) {
        state.library.update { library -> library.filterNot { it.id.value == asset } }
    }

    // ---- the grant --------------------------------------------------------------------------------

    override suspend fun permission(grant: PhotoGrant) {
        state.access.value = LibraryGrant.valueOf(grant.name)
    }

    override suspend fun requestAnswer(grant: PhotoGrant) {
        state.answer = LibraryGrant.valueOf(grant.name)
    }

    /** Delivered whole, with its resources, to the running process's observer, as the real observer delivers one. */
    override suspend fun changeSelection(assets: List<String>) {
        val observer = checkNotNull(state.observer) {
            "no selection observer is open — the host was never assembled, so nothing would hear it"
        }
        val wanted = assets.toSet()
        val selected = state.library.value.filter { it.id.value in wanted }
        state.selection.value = selected
        observer.deliver(SelectionSnapshot(selected))
    }

    // ---- the library's answers --------------------------------------------------------------------

    override suspend fun failNextEnumeration() {
        state.failNextEnumeration = true
    }

    /** Holding again while held changes nothing. */
    override suspend fun holdEnumeration(on: Boolean) {
        if (on) state.enumeration.hold() else state.enumeration.release()
    }

    override suspend fun byIdReadable(on: Boolean) {
        state.byIdReadable = on
    }

    override suspend fun failNextImport(afterCreating: Boolean?) {
        if (afterCreating == true) state.imports.failNextImportAfterCreating = true else state.imports.failNextImport = true
    }

    override suspend fun suspendNextImport(afterCommit: Boolean?) {
        if (afterCommit == true) state.imports.suspendNextImportAfterCommit = true else state.imports.suspendNextImport = true
    }

    override suspend fun resumeImport(succeeded: Boolean?) = state.imports.resumeSuspendedImport(succeeded != false)

    override suspend fun attemptCap(cap: Int) {
        state.imports.attemptCap = cap
    }

    // ---- albums -----------------------------------------------------------------------------------

    override suspend fun placeIn(title: String, asset: String) {
        val cell = checkNotNull(state.writableAlbums) { "this library's other-app albums are a read-only cell" }
        cell.update { albums -> albums + (title to (albums[title].orEmpty() + PlatformAssetId(asset))) }
    }

    /** Holding again while held changes nothing. */
    override suspend fun holdAdds(on: Boolean) {
        if (on) state.adds.hold() else state.adds.release()
    }

    override suspend fun albumKind(kind: AlbumKind) {
        state.albumKind = LibraryAlbumKind.valueOf(kind.name)
    }

    override suspend fun deleteAlbum(album: String) {
        when (state.albumKind) {
            LibraryAlbumKind.COLLECTION -> state.locked { state.deletedAlbums += album }
            LibraryAlbumKind.FOLDER -> state.locked {
                val inside = state.folderOf.filterValues { it == album }.keys.toSet()
                state.folderOf.keys.removeAll(inside)
                state.library.update { library -> library.filterNot { it.id in inside } }
            }
        }
    }

    override suspend fun renameAlbum(album: String, title: String) {
        // A collection album keeps its identity: only its title changes (`A_RENAMED_COLLECTION_KEEPS_ITS_ID`).
        if (state.albumKind == LibraryAlbumKind.COLLECTION) {
            state.locked { checkNotNull(state.created[album]) { "no album $album to rename" }.title = title }
            return
        }
        state.locked {
            val renamed = "renamed-${state.albumCounter++}"
            state.created[renamed] = LibraryState.Album(title)
            state.folderOf.entries.filter { it.value == album }.forEach { it.setValue(renamed) }
        }
    }

    // ---- reads ------------------------------------------------------------------------------------

    override suspend fun library(): LibraryView = state.locked {
        LibraryView(
            photos = state.library.value.map {
                HeldPhoto(it.id.value, it.asset.created?.toString(), imported = it.id in state.ownImports)
            },
            grant = PhotoGrant.valueOf(state.access.value.name),
            requestAnswer = PhotoGrant.valueOf(state.answer.name),
            selection = state.selection.value?.map { it.id.value },
            observing = state.observer != null,
            pickersShown = state.pickersShown,
            enumerationHeld = state.enumeration.held,
            addsHeld = state.adds.held,
            byIdReadable = state.byIdReadable,
            albumKind = AlbumKind.valueOf(state.albumKind.name),
        )
    }

    override suspend fun albums(): List<AppAlbum> = appAlbums()

    /** The albums the app made, as [albums] reads them — without suspending, for a settle's snapshot. */
    internal fun appAlbums(): List<AppAlbum> = state.locked {
        state.createdLog.map { (id, title) ->
            AppAlbum(
                id = id,
                // As the album is titled now — a member's rename shows — or as it was made, once it is gone.
                title = state.created[id]?.title ?: title,
                photos = contentsOf(id).map { it.value },
                asked = state.addedLog.filter { it.first == id }.flatMap { it.second }.map { it.value },
            )
        }
    }

    override suspend fun imports(): ImportsView = ImportsView(
        imported = state.imports.imported,
        parked = state.imports.parkedCorrelation?.let {
            ParkedImport(
                it.substringBeforeLast('/'),
                it.substringAfterLast('/'),
            )
        },
    )

    /**
     * The photos the app's album [albumId] holds now, as a person sees it — each once, and only while the library still
     * holds it: under [LibraryAlbumKind.COLLECTION] its members in the order they first joined, under
     * [LibraryAlbumKind.FOLDER] the photos its folder holds. Called under the state's lock.
     */
    private fun contentsOf(albumId: String): List<PlatformAssetId> {
        val held = state.library.value.mapTo(mutableSetOf()) { it.id }
        return when (state.albumKind) {
            LibraryAlbumKind.COLLECTION ->
                state.created[albumId]?.takeIf { albumId !in state.deletedAlbums }?.members.orEmpty().filter { it in held }
            LibraryAlbumKind.FOLDER ->
                state.folderOf.filter { (asset, folder) -> folder == albumId && asset in held }.keys.sortedBy { it.value }
        }
    }
}

/**
 * The photo library's durable state: everything the device keeps, and the one process currently registered with it.
 * The cells guard themselves; the albums, their logs and the attempts are [locked] — the app writes them from its own
 * thread while an inspector reads them from another.
 */
internal class LibraryState(
    val library: MutableStateFlow<List<GalleryAssetResources>>,
    val access: MutableStateFlow<LibraryGrant>,
    val userAlbums: StateFlow<Map<String, Set<PlatformAssetId>>>,
    answer: LibraryGrant,
    /** How the library answers each change; `null` is the [imports] script's answers. */
    answers: LibraryChangeAnswers?,
    /**
     * The device's disk, from which an import reads the file it is handed, by the location the app's file system gave
     * it; `null`, or a location that is not the mock's, and the file decodes. A library decides by the file, so this is
     * what makes an import undecodable.
     */
    private val disk: FileSystemMock? = null,
) {
    private val lock = mockLock()

    fun <T> locked(block: () -> T): T = lock.locked(block)

    @Volatile var answer: LibraryGrant = answer

    class Album(var title: String, val members: MutableSet<PlatformAssetId> = mutableSetOf())

    /** The process whose composition registered last, and its handlers. */
    class Listener(val face: Any, val handlers: GalleryHandlers)

    val imports = ImportScript { watcher.told(it) }
    val selection = MutableStateFlow<List<GalleryAssetResources>?>(null)

    /** How many times the app presented the limited-library picker. */
    @Volatile var pickersShown: Int = 0
    val answers: LibraryChangeAnswers = answers ?: imports.answers

    @Volatile var listener: Listener? = null

    /** The process face whose selection observer is open — the one a changed selection is delivered to. */
    @Volatile var observer: InMemoryGallery? = null

    val attempts = mutableMapOf<String, Int>()
    var albumCounter = 0
    val created = mutableMapOf<AlbumId, Album>()
    val createdLog = mutableListOf<Pair<String, String>>()
    val addedLog = mutableListOf<Pair<String, List<PlatformAssetId>>>()
    val deletedAlbums = mutableSetOf<String>()
    val adds = OperatorHold()
    val enumeration = OperatorHold()

    @Volatile var failNextEnumeration = false

    @Volatile var byIdReadable = true

    /** Whether the file at [location] is one no library can decode: the bytes another member shared as no image. */
    fun undecodable(location: String): Boolean = disk?.located(location)?.contentEquals(NOT_AN_IMAGE) == true

    /** What each export wrote, by the path it was written to: the files an exporting library leaves. */
    @Volatile var exported: Map<String, ByteArray> = emptyMap()

    @Volatile var albumKind = LibraryAlbumKind.COLLECTION

    /** Under [LibraryAlbumKind.FOLDER], the app's album folder each photo in one lives in; a photo in none is in the camera folder. */
    val folderOf = mutableMapOf<PlatformAssetId, AlbumId>()

    /** What this library imported — the photos the app saved itself, which it may move. */
    val ownImports = mutableSetOf<PlatformAssetId>()

    /** [userAlbums] as the operator writes it — a caller-supplied read-only cell is never written. */
    val writableAlbums: MutableStateFlow<Map<String, Set<PlatformAssetId>>>? get() = userAlbums as? MutableStateFlow

    /** Who is told what this library records, as it records it — the control channel's event stream, or no one. */
    @Volatile var watcher: LibraryWatcher = LibraryWatcher {}
}

/**
 * The photo library's answers to an import, scripted — the operator's side of [LibraryChangeAnswers] — plus what was
 * imported. Each lever fires once: [failNextImport] refuses the change before anything is created,
 * [failNextImportAfterCreating] creates the asset and then reports failure, [suspendNextImport] holds the transaction
 * open before its commit lands (the library answers *absent* about it meanwhile), and [suspendNextImportAfterCommit]
 * holds it after the commit (the asset exists, the report never comes — the shape a process death leaves). A park is
 * [told] as it happens, so a caller waits on the event rather than guessing at a delay.
 */
internal class ImportScript(private val told: (WorldEvent.Library) -> Unit) {
    // The operator scripts and reads from its thread while the app imports on another.
    private val lock = mockLock()

    private val importedCorrelations = mutableListOf<String>()

    /** The correlations of the imports that created an asset, one entry per created asset (a repeat shows twice). */
    val imported: List<String> get() = lock.locked { importedCorrelations.toList() }

    fun restoreImported(correlations: List<String>) = lock.locked { importedCorrelations.addAll(correlations) }

    @Volatile var failNextImport: Boolean = false

    @Volatile var failNextImportAfterCreating: Boolean = false

    @Volatile var suspendNextImport: Boolean = false

    @Volatile var suspendNextImportAfterCommit: Boolean = false

    private var parked: Pair<String, CompletableDeferred<Boolean>>? = null

    /** The correlation of the import parked now, or `null`. */
    val parkedCorrelation: String? get() = lock.locked { parked?.first }

    /** Deliver the parked import's outcome: on [succeeded] the asset lands; otherwise its marker is cleared. */
    fun resumeSuspendedImport(succeeded: Boolean) {
        val gate = lock.locked { parked.also { parked = null } } ?: error("no import is suspended")
        gate.second.complete(succeeded)
    }

    /**
     * How many times ONE import may be made before the library raises: an unbounded re-selection of one asset is a
     * live-lock, and a live-lock in a test is a hang that names nothing. The cap turns it into a failure naming the count.
     */
    @Volatile var attemptCap: Int = DEFAULT_ATTEMPT_CAP

    private val attempts = mutableMapOf<String, Int>()

    private suspend fun park(correlation: String): String? {
        val gate = CompletableDeferred<Boolean>()
        lock.locked {
            // A second park would overwrite the first's gate and strand that import: refuse it, loudly.
            check(parked == null) { "an import is already suspended; resume it before suspending $correlation" }
            parked = correlation to gate
        }
        told(WorldEvent.LibraryImportParked(correlation.substringBeforeLast('/'), correlation.substringAfterLast('/')))
        return if (gate.await()) null else "suspended import resumed as failed"
    }

    val answers = object : LibraryChangeAnswers {
        override suspend fun beforeChange(correlation: String): String? {
            if (!failNextImport) return null
            failNextImport = false
            return "forced"
        }

        override suspend fun beforeCommit(correlation: String): String? {
            if (!suspendNextImport) return null
            suspendNextImport = false
            return park(correlation)
        }

        override suspend fun afterCommit(correlation: String): String? {
            lock.locked { importedCorrelations += correlation }
            if (suspendNextImportAfterCommit) {
                suspendNextImportAfterCommit = false
                park(correlation)?.let { return it }
            }
            if (!failNextImportAfterCreating) return null
            failNextImportAfterCreating = false
            return "forced after creating"
        }
    }

    /** Counts an import of [correlation] against [attemptCap], raising at the cap. */
    fun attempt(correlation: String) {
        val attempt = lock.locked { (attempts.getOrElse(correlation) { 0 } + 1).also { attempts[correlation] = it } }
        check(attempt <= attemptCap) {
            "imported $correlation $attempt times (cap $attemptCap) — the drain is live-locking " +
                "on one asset instead of offering it once"
        }
    }

    private companion object {
        const val DEFAULT_ATTEMPT_CAP = 50
    }
}
