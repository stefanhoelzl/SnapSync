package app.snapsync.mock

import app.snapsync.model.AlbumId
import app.snapsync.model.AlbumKind
import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.model.GalleryAccess
import app.snapsync.model.RawAsset
import app.snapsync.model.SelectionSnapshot
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.PhotoAccessStatusSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile

/**
 * **The photo library's mock** (`docs/testing.md`, "Mocks"): the library the device keeps — its assets, the grant,
 * the albums, what was imported — which survives a relaunch of the app; the [port] and [photoAccess] faces a process
 * reads it through; and the [operator] face that plays the person holding the phone and the library's own answers.
 */
class PhotoLibraryMock(
    access: GalleryAccess = GalleryAccess.GRANTED,
    /** What the person answers the permission dialog with, the one time it is asked. */
    requestAnswer: GalleryAccess = GalleryAccess.GRANTED,
) {
    internal val state = LibraryState(
        library = MutableStateFlow(emptyList()),
        access = MutableStateFlow(access),
        userAlbums = MutableStateFlow(emptyMap()),
        answer = requestAnswer,
        answers = null,
    )

    /** One process's face of the library. */
    fun port(): Gallery = InMemoryGallery(state)

    /** One process's view of the grant — the same grant the [port] reads. */
    fun photoAccess(): PhotoAccessStatusSource = InMemoryPhotoAccess(state.access)

    val operator: PhotoLibraryOperator = PhotoLibraryOperator(state)
}

/**
 * What the person holding the phone — and the photo library itself — can do, and what a test can read of it. None of
 * it is on the [Gallery] port.
 */
class PhotoLibraryOperator internal constructor(private val state: LibraryState) {

    // ---- the library ------------------------------------------------------------------------------

    /** The whole library, unscoped — an operator read (the app has no unbounded walk). */
    fun current(): List<RawAsset> = state.library.value

    /** The library as a cell. */
    val contents: StateFlow<List<RawAsset>> = state.library.asStateFlow()

    /** The library becomes exactly [assets]. */
    fun set(assets: List<RawAsset>) {
        state.library.value = assets
    }

    // The app imports into the same cell from its own thread: each change is one atomic update, never a read and a
    // write another change can land between.
    fun add(asset: RawAsset) {
        state.library.update { it + asset }
    }

    fun remove(assetId: AssetId) {
        state.library.update { library -> library.filterNot { it.assetId == assetId } }
    }

    // ---- the grant --------------------------------------------------------------------------------

    /** The grant, as a cell. */
    val grant: StateFlow<GalleryAccess> = state.access.asStateFlow()

    /** The grant, as the person set it in Settings. */
    var access: GalleryAccess by state.access::value

    /** What the person answers the permission dialog with, the one time it is asked. */
    var requestAnswer: GalleryAccess by state::answer

    // ---- the selection observer -------------------------------------------------------------------

    /**
     * The person's selection under a partial grant, as they last picked it — `null` until they have. The operating
     * system keeps it across a process; which of it a process has been told is that process's own business.
     */
    val selection: StateFlow<List<RawAsset>?> = state.selection.asStateFlow()

    /** Whether the running process's selection observer is open (composition opens it, on every start). */
    val observing: Boolean get() = state.listener?.observing == true

    /**
     * The person's selection under a partial grant is now [assets] — delivered whole, with its resources, to the running
     * process's observer, as the real observer delivers one. Fails loudly when no observer is open, rather than doing
     * nothing: a process whose observer is closed would hear nothing.
     */
    fun changeSelection(assets: List<RawAsset>) {
        val listener = checkNotNull(state.listener) { "no process registered with the photo library" }
        check(listener.observing) { "the selection observer is not open — the composition opens it" }
        state.selection.value = assets
        listener.handlers.onChanged(SelectionSnapshot(assets))
    }

    // ---- the library's answers --------------------------------------------------------------------

    /** The next walk throws, as a platform walk can — a failure, never a successful read with no answer. */
    var failNextEnumeration: Boolean
        get() = state.failNextEnumeration
        set(value) { state.failNextEnumeration = value }

    /** Whether a by-identifier read can see the library; when not, every presence answer is `NotReadable`. */
    var byIdReadable: Boolean by state::byIdReadable

    /** How the library answers each import's change, and what was imported. */
    val imports: ImportScript get() = state.imports

    // ---- albums -----------------------------------------------------------------------------------

    /** Every album the app created, in order: (album id, title). */
    val created: List<Pair<String, String>> get() = state.locked { state.createdLog.toList() }

    /** Every add the app made, in order: (album id, the assets asked for). */
    val added: List<Pair<String, List<AssetId>>> get() = state.locked { state.addedLog.toList() }

    /** Every asset id the app asked to add to [albumId], across all adds, in order. */
    fun assetsIn(albumId: String): List<AssetId> =
        state.locked { state.addedLog.filter { it.first == albumId }.flatMap { it.second } }

    /**
     * The photos in the app's album [albumId]: under [AlbumKind.COLLECTION] every asset it was asked to add, in order
     * ([assetsIn]); under [AlbumKind.FOLDER] the photos its folder holds now.
     */
    fun contentsOf(albumId: String): List<AssetId> = when (state.albumKind) {
        AlbumKind.COLLECTION -> assetsIn(albumId)
        AlbumKind.FOLDER -> {
            val held = state.library.value.mapTo(mutableSetOf()) { it.assetId }
            state.locked {
                state.folderOf.filter { (asset, folder) -> folder == albumId && asset in held }.keys.sortedBy { it.value }
            }
        }
    }

    /** Put [assetId] into an album some other app made, titled [title] — e.g. `placeIn("WhatsApp", "A1")`. */
    fun placeIn(title: String, assetId: String) {
        val cell = checkNotNull(state.writableAlbums) { "this library's other-app albums are a read-only cell" }
        cell.update { albums -> albums + (title to (albums[title].orEmpty() + AssetId(assetId))) }
    }

    /**
     * The person deletes an album the app made: it no longer resolves. Under [AlbumKind.FOLDER] the folder goes with
     * its photos, as a gallery app's folder delete does.
     */
    fun delete(albumId: String) {
        when (state.albumKind) {
            AlbumKind.COLLECTION -> state.locked { state.deletedAlbums += albumId }
            AlbumKind.FOLDER -> state.locked {
                val inside = state.folderOf.filterValues { it == albumId }.keys.toSet()
                state.folderOf.keys.removeAll(inside)
                state.library.update { library -> library.filterNot { it.assetId in inside } }
            }
        }
    }

    /**
     * The person renames a [AlbumKind.FOLDER] album the app made in a gallery app: its photos move to a folder of the new
     * name, still inside the SnapSync folder, and the album the app knows is left empty.
     */
    fun rename(albumId: String, newTitle: String) {
        check(state.albumKind == AlbumKind.FOLDER) { "a collection album's title is not where its photos live" }
        state.locked {
            val renamed = "renamed-${state.albumCounter++}"
            state.created[renamed] = LibraryState.Album(newTitle)
            state.folderOf.entries.filter { it.value == albumId }.forEach { it.setValue(renamed) }
        }
    }

    /** The album folder [assetId] lives in under [AlbumKind.FOLDER], or `null` — the camera folder. */
    fun folderOf(assetId: AssetId): String? = state.locked { state.folderOf[assetId] }

    /** Every add waits until [releaseAdds]. Holding again while held changes nothing. */
    fun holdAdds() = state.adds.hold()

    fun releaseAdds() = state.adds.release()

    /**
     * Every walk waits until [releaseEnumeration] — a library that has not been enumerated yet. Holding again while
     * held changes nothing.
     */
    fun holdEnumeration() = state.enumeration.hold()

    /** Whether walks are held. */
    val enumerationHeld: Boolean get() = state.enumeration.held

    /** A held walk, and every later one, reads. */
    fun releaseEnumeration() = state.enumeration.release()

    // ---- the platform -----------------------------------------------------------------------------

    /**
     * How this library holds an album: an iPhone's [AlbumKind.COLLECTION] (the default) or an Android phone's
     * [AlbumKind.FOLDER]. Set before composing to play an Android library.
     */
    var albumKind: AlbumKind by state::albumKind
}

/**
 * The photo library's durable state: everything the device keeps, and the one process currently registered with it.
 * The cells guard themselves; the albums, their logs and the attempts are [locked] — the app writes them from its own
 * thread while an inspector reads them from another.
 */
internal class LibraryState(
    val library: MutableStateFlow<List<RawAsset>>,
    val access: MutableStateFlow<GalleryAccess>,
    val userAlbums: StateFlow<Map<String, Set<AssetId>>>,
    answer: GalleryAccess,
    /** How the library answers each change; `null` is the [imports] script's answers. */
    answers: LibraryChangeAnswers?,
) {
    private val lock = mockLock()

    fun <T> locked(block: () -> T): T = lock.locked(block)

    @Volatile var answer: GalleryAccess = answer

    class Album(val title: String, val members: MutableSet<AssetId> = mutableSetOf())

    /** The process whose composition registered last — its handlers, and whether its selection observer is open. */
    class Listener(val face: Any, val handlers: GalleryHandlers) {
        @Volatile var observing: Boolean = false
    }

    val imports = ImportScript()
    val selection = MutableStateFlow<List<RawAsset>?>(null)
    val answers: LibraryChangeAnswers = answers ?: imports.answers

    @Volatile var listener: Listener? = null

    val attempts = mutableMapOf<AssetRef, Int>()
    var albumCounter = 0
    val created = mutableMapOf<AlbumId, Album>()
    val createdLog = mutableListOf<Pair<String, String>>()
    val addedLog = mutableListOf<Pair<String, List<AssetId>>>()
    val deletedAlbums = mutableSetOf<String>()
    val adds = OperatorHold()
    val enumeration = OperatorHold()

    @Volatile var failNextEnumeration = false

    @Volatile var byIdReadable = true

    @Volatile var albumKind = AlbumKind.COLLECTION

    /** Under [AlbumKind.FOLDER], the app's album folder each photo in one lives in; a photo in none is in the camera folder. */
    val folderOf = mutableMapOf<AssetId, AlbumId>()

    /** What this library imported — the photos the app saved itself, which it may move. */
    val ownImports = mutableSetOf<AssetId>()

    /** [userAlbums] as the operator writes it — a caller-supplied read-only cell is never written. */
    val writableAlbums: MutableStateFlow<Map<String, Set<AssetId>>>? get() = userAlbums as? MutableStateFlow
}

/**
 * The photo library's answers to an import, scripted — the operator's side of [LibraryChangeAnswers] — plus what was
 * imported. Each lever fires once: [failNextImport] refuses the change before anything is created,
 * [failNextImportAfterCreating] creates the asset and then reports failure, [suspendNextImport] holds the transaction
 * open before its commit lands (the library answers *absent* about it meanwhile), and [suspendNextImportAfterCommit]
 * holds it after the commit (the asset exists, the report never comes — the shape a process death leaves).
 */
class ImportScript internal constructor() {
    // The operator scripts and reads from its thread while the app imports on another.
    private val lock = mockLock()

    private val importedRefs = mutableListOf<AssetRef>()

    /** The source refs imported, one entry per created asset (so a repeat shows up twice). */
    val imported: List<AssetRef> get() = lock.locked { importedRefs.toList() }

    internal fun restoreImported(refs: List<AssetRef>) = lock.locked { importedRefs.addAll(refs) }

    @Volatile var failNextImport: Boolean = false

    @Volatile var failNextImportAfterCreating: Boolean = false

    @Volatile var suspendNextImport: Boolean = false

    @Volatile var suspendNextImportAfterCommit: Boolean = false

    private var parked: CompletableDeferred<Boolean>? = null

    /**
     * Completes once an import has actually parked, so a caller awaits the live transaction rather than guessing at a
     * delay. **Replaced on every park**: a completed deferred would hand a second waiter the STALE ref at once.
     */
    @Volatile var suspendedImport: CompletableDeferred<AssetRef> = CompletableDeferred()
        private set

    /** Deliver the parked import's outcome: on [succeeded] the asset lands; otherwise its marker is cleared. */
    fun resumeSuspendedImport(succeeded: Boolean) {
        val gate = lock.locked { parked.also { parked = null } } ?: error("no import is suspended")
        gate.complete(succeeded)
    }

    /**
     * How many times ONE ref may be imported before the library raises: an unbounded re-selection of one ref is a
     * live-lock, and a live-lock in a test is a hang that names nothing. The cap turns it into a failure naming the count.
     */
    var attemptCap: Int = DEFAULT_ATTEMPT_CAP

    private val attempts = mutableMapOf<AssetRef, Int>()

    private suspend fun park(ref: AssetRef): String? {
        val gate = CompletableDeferred<Boolean>()
        val announced = lock.locked {
            // A second park would overwrite the first's gate and strand that import: refuse it, loudly.
            check(parked == null) { "an import is already suspended; resume it before suspending ${ref.sourceAssetId}" }
            parked = gate
            if (suspendedImport.isCompleted) suspendedImport = CompletableDeferred()
            suspendedImport
        }
        announced.complete(ref)
        return if (gate.await()) null else "suspended import resumed as failed"
    }

    internal val answers = object : LibraryChangeAnswers {
        override suspend fun beforeChange(ref: AssetRef): String? {
            if (!failNextImport) return null
            failNextImport = false
            return "forced"
        }

        override suspend fun beforeCommit(ref: AssetRef): String? {
            if (!suspendNextImport) return null
            suspendNextImport = false
            return park(ref)
        }

        override suspend fun afterCommit(ref: AssetRef): String? {
            lock.locked { importedRefs += ref }
            if (suspendNextImportAfterCommit) {
                suspendNextImportAfterCommit = false
                park(ref)?.let { return it }
            }
            if (!failNextImportAfterCreating) return null
            failNextImportAfterCreating = false
            return "forced after creating"
        }
    }

    /** Counts an import of [ref] against [attemptCap], raising at the cap. */
    internal fun attempt(ref: AssetRef) {
        val attempt = lock.locked { (attempts.getOrElse(ref) { 0 } + 1).also { attempts[ref] = it } }
        check(attempt <= attemptCap) {
            "imported ${ref.sourceAssetId} $attempt times (cap $attemptCap) — the drain is live-locking " +
                "on one ref instead of offering it once"
        }
    }

    private companion object {
        const val DEFAULT_ATTEMPT_CAP = 50
    }
}
