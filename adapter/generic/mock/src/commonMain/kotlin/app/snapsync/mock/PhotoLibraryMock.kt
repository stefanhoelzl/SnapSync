package app.snapsync.mock

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
 */
class PhotoLibraryMock(
    access: LibraryGrant = LibraryGrant.GRANTED,
    /** What the person answers the permission dialog with, the one time it is asked. */
    requestAnswer: LibraryGrant = LibraryGrant.GRANTED,
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

    val operator: PhotoLibraryOperator = PhotoLibraryOperator(state)
}

/**
 * What the person holding the phone — and the photo library itself — can do, and what a test can read of it. None of
 * it is on the [Gallery] port.
 */
class PhotoLibraryOperator internal constructor(private val state: LibraryState) {

    // ---- the library ------------------------------------------------------------------------------

    /** The whole library, unscoped — an operator read (the app has no unbounded walk). */
    fun current(): List<GalleryAssetResources> = state.library.value

    /** The library as a cell. */
    val contents: StateFlow<List<GalleryAssetResources>> = state.library.asStateFlow()

    /** The library becomes exactly [assets]. */
    fun set(assets: List<GalleryAssetResources>) {
        state.library.value = assets
    }

    // The app imports into the same cell from its own thread: each change is one atomic update, never a read and a
    // write another change can land between.
    fun add(asset: GalleryAssetResources) {
        state.library.update { it + asset }
    }

    fun remove(assetId: PlatformAssetId) {
        state.library.update { library -> library.filterNot { it.id == assetId } }
    }

    // ---- the grant --------------------------------------------------------------------------------

    /** The grant, as a cell. */
    val grant: StateFlow<LibraryGrant> = state.access.asStateFlow()

    /** The grant, as the person set it in Settings. */
    var access: LibraryGrant by state.access::value

    /** What the person answers the permission dialog with, the one time it is asked. */
    var requestAnswer: LibraryGrant by state::answer

    // ---- the selection observer -------------------------------------------------------------------

    /**
     * The person's selection under a partial grant, as they last picked it — `null` until they have. The operating
     * system keeps it across a process; which of it a process has been told is that process's own business.
     */
    val selection: StateFlow<List<GalleryAssetResources>?> = state.selection.asStateFlow()

    /** Whether the running process's selection observer is open (composition opens it, on every start). */
    val observing: Boolean get() = state.observer != null

    /** How many times the app presented the limited-library picker — the person's answer arrives as [changeSelection]. */
    val pickersShown: Int get() = state.pickersShown

    /**
     * The person's selection under a partial grant is now [assets] — delivered whole, with its resources, to the running
     * process's observer, as the real observer delivers one. Fails loudly when no observer is open, rather than doing
     * nothing: a process whose observer is closed would hear nothing.
     */
    fun changeSelection(assets: List<GalleryAssetResources>) {
        val observer = checkNotNull(state.observer) { "the selection observer is not open — the composition opens it" }
        state.selection.value = assets
        observer.deliver(SelectionSnapshot(assets))
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
    val added: List<Pair<String, List<PlatformAssetId>>> get() = state.locked { state.addedLog.toList() }

    /** Every asset id the app asked to add to [albumId], across all adds, in order. */
    fun assetsIn(albumId: String): List<PlatformAssetId> =
        state.locked { state.addedLog.filter { it.first == albumId }.flatMap { it.second } }

    /**
     * The photos the app's album [albumId] holds now, as a person sees it — each once, and only while the library still
     * holds it: under [LibraryAlbumKind.COLLECTION] its members in the order they first joined, under
     * [LibraryAlbumKind.FOLDER] the photos its folder holds. What the app ASKED for, repeats included, is [assetsIn].
     */
    fun contentsOf(albumId: String): List<PlatformAssetId> {
        val held = state.library.value.mapTo(mutableSetOf()) { it.id }
        return state.locked {
            when (state.albumKind) {
                LibraryAlbumKind.COLLECTION -> state.created[albumId]?.members.orEmpty().filter { it in held }
                LibraryAlbumKind.FOLDER ->
                    state.folderOf.filter { (asset, folder) -> folder == albumId && asset in held }.keys.sortedBy { it.value }
            }
        }
    }

    /** Put [assetId] into an album some other app made, titled [title] — e.g. `placeIn("WhatsApp", "A1")`. */
    fun placeIn(title: String, assetId: String) {
        val cell = checkNotNull(state.writableAlbums) { "this library's other-app albums are a read-only cell" }
        cell.update { albums -> albums + (title to (albums[title].orEmpty() + PlatformAssetId(assetId))) }
    }

    /**
     * The person deletes an album the app made: it no longer resolves. Under [LibraryAlbumKind.FOLDER] the folder goes with
     * its photos, as a gallery app's folder delete does.
     */
    fun delete(albumId: String) {
        when (state.albumKind) {
            LibraryAlbumKind.COLLECTION -> state.locked { state.deletedAlbums += albumId }
            LibraryAlbumKind.FOLDER -> state.locked {
                val inside = state.folderOf.filterValues { it == albumId }.keys.toSet()
                state.folderOf.keys.removeAll(inside)
                state.library.update { library -> library.filterNot { it.id in inside } }
            }
        }
    }

    /**
     * The person renames a [LibraryAlbumKind.FOLDER] album the app made in a gallery app: its photos move to a folder of the new
     * name, still inside the SnapSync folder, and the album the app knows is left empty.
     */
    fun rename(albumId: String, newTitle: String) {
        check(state.albumKind == LibraryAlbumKind.FOLDER) { "a collection album's title is not where its photos live" }
        state.locked {
            val renamed = "renamed-${state.albumCounter++}"
            state.created[renamed] = LibraryState.Album(newTitle)
            state.folderOf.entries.filter { it.value == albumId }.forEach { it.setValue(renamed) }
        }
    }

    /** The album folder [assetId] lives in under [LibraryAlbumKind.FOLDER], or `null` — the camera folder. */
    fun folderOf(assetId: PlatformAssetId): String? = state.locked { state.folderOf[assetId] }

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
     * How this library holds an album: an iPhone's [LibraryAlbumKind.COLLECTION] (the default) or an Android phone's
     * [LibraryAlbumKind.FOLDER]. Set before composing to play an Android library.
     */
    var albumKind: LibraryAlbumKind by state::albumKind
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
) {
    private val lock = mockLock()

    fun <T> locked(block: () -> T): T = lock.locked(block)

    @Volatile var answer: LibraryGrant = answer

    class Album(val title: String, val members: MutableSet<PlatformAssetId> = mutableSetOf())

    /** The process whose composition registered last, and its handlers. */
    class Listener(val face: Any, val handlers: GalleryHandlers)

    val imports = ImportScript()
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

    @Volatile var albumKind = LibraryAlbumKind.COLLECTION

    /** Under [LibraryAlbumKind.FOLDER], the app's album folder each photo in one lives in; a photo in none is in the camera folder. */
    val folderOf = mutableMapOf<PlatformAssetId, AlbumId>()

    /** What this library imported — the photos the app saved itself, which it may move. */
    val ownImports = mutableSetOf<PlatformAssetId>()

    /** [userAlbums] as the operator writes it — a caller-supplied read-only cell is never written. */
    val writableAlbums: MutableStateFlow<Map<String, Set<PlatformAssetId>>>? get() = userAlbums as? MutableStateFlow
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

    private val importedCorrelations = mutableListOf<String>()

    /** The correlations of the imports that created an asset, one entry per created asset (a repeat shows twice). */
    val imported: List<String> get() = lock.locked { importedCorrelations.toList() }

    internal fun restoreImported(correlations: List<String>) = lock.locked { importedCorrelations.addAll(correlations) }

    @Volatile var failNextImport: Boolean = false

    @Volatile var failNextImportAfterCreating: Boolean = false

    @Volatile var suspendNextImport: Boolean = false

    @Volatile var suspendNextImportAfterCommit: Boolean = false

    private var parked: CompletableDeferred<Boolean>? = null

    /**
     * Completes once an import has actually parked, so a caller awaits the live transaction rather than guessing at a
     * delay — completed with the parked import's correlation. **Replaced on every park**: a completed deferred would
     * hand a second waiter the STALE one at once.
     */
    @Volatile var suspendedImport: CompletableDeferred<String> = CompletableDeferred()
        private set

    /** Deliver the parked import's outcome: on [succeeded] the asset lands; otherwise its marker is cleared. */
    fun resumeSuspendedImport(succeeded: Boolean) {
        val gate = lock.locked { parked.also { parked = null } } ?: error("no import is suspended")
        gate.complete(succeeded)
    }

    /**
     * How many times ONE import may be made before the library raises: an unbounded re-selection of one asset is a
     * live-lock, and a live-lock in a test is a hang that names nothing. The cap turns it into a failure naming the count.
     */
    var attemptCap: Int = DEFAULT_ATTEMPT_CAP

    private val attempts = mutableMapOf<String, Int>()

    private suspend fun park(correlation: String): String? {
        val gate = CompletableDeferred<Boolean>()
        val announced = lock.locked {
            // A second park would overwrite the first's gate and strand that import: refuse it, loudly.
            check(parked == null) { "an import is already suspended; resume it before suspending $correlation" }
            parked = gate
            if (suspendedImport.isCompleted) suspendedImport = CompletableDeferred()
            suspendedImport
        }
        announced.complete(correlation)
        return if (gate.await()) null else "suspended import resumed as failed"
    }

    internal val answers = object : LibraryChangeAnswers {
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
    internal fun attempt(correlation: String) {
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
