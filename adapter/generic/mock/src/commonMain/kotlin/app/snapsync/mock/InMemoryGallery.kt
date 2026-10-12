package app.snapsync.mock

import app.snapsync.model.AlbumId
import app.snapsync.model.AlbumKind
import app.snapsync.model.AlbumRecord
import app.snapsync.model.AssetFacts
import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.model.GalleryRead
import app.snapsync.model.ImportRequest
import app.snapsync.model.ImportResult
import app.snapsync.model.RawAsset
import app.snapsync.model.RawResource
import app.snapsync.model.ReceivedPhotoName
import app.snapsync.model.Resource
import app.snapsync.model.ResourceRole
import app.snapsync.model.SelectionSnapshot
import app.snapsync.model.StagedResource
import app.snapsync.model.WriteOutcome
import app.snapsync.model.toFacts
import app.snapsync.ports.AssetFilter
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.LibraryChangeToken
import app.snapsync.ports.LibraryGrant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Instant

/**
 * The honest in-memory [Gallery] (`docs/architecture.md`) — one process's face of a [LibraryState], held to the
 * gallery contracts exactly as the PhotoKit adapters are. The library, its grant, its albums and the import script
 * are the state's; what this face holds is only what a process holds: the handlers it registered and whether its
 * selection observer is open.
 *
 * It answers as the platform does:
 * - **No grant, no read**: every read answers `NotReadable` unless the grant is full or partial. There is no
 *   selection here, so a partial grant reads the whole library — the core reads the held snapshot instead.
 * - **[assets] translates only the two REQUIRED narrowings** — the capture floor and deny-everything. The
 *   platform's predicate is an *optimization* the authoritative admission must be proven to work without,
 *   so a mock that mirrored it would hide exactly the bug that matters: an
 *   admission relying on the fetch to have excluded something. The floor is mirrored because it is required
 *   rather than advisory: without it the real walk is unbounded.
 * - Albums: an asset added to an album is a member of it; an asset the library does not hold is skipped;
 *   adding to an album that does not exist fails. Albums it creates get deterministic ids (`album-<n>`), the other
 *   apps' albums `user-album:<title>`.
 * - **A [AlbumKind.FOLDER] library plays Android's**: an album the app created is the folder its photos live in, so it
 *   resolves and lists only while it holds one; an import with an album lands in that folder; an add MOVES a photo this
 *   library imported and skips any other (the platform will not let the app move a camera photo). Every read reaches
 *   into the album folders as into any folder of the default gallery.
 * - [requestAccess] applies the state's answer **only while the grant is undetermined**, because a platform asks
 *   once. The selection picker has no surface off device and changes nothing.
 * - **An import is two-phase, exactly like the real adapter**: the placeholder reaches `onImportPlaceholder` inside
 *   the "change block", before the asset is observable, and the outcome reaches `onImportSettled` before [import]
 *   returns. The state's [LibraryChangeAnswers] is how the library answers each change. **Every import mints a
 *   fresh identifier**, as `PHAssetCreationRequest` does, counted before anything can fail, so a repeat import never
 *   lands on an earlier import's handle; the first keeps the bare form `imported-<device>-<asset>`.
 * - The observer emits on its own exactly once, as both platform adapters do: opening it under a partial grant
 *   delivers the selection the person last picked (the start's baseline read). Every later selection is played
 *   through the operator, to the handlers of the process whose observer is open.
 * - A change token is the library **value** it was read at: every change to the library replaces that value, so a
 *   token read after it never compares equal to one read before.
 */
internal class InMemoryGallery(private val state: LibraryState) : Gallery {

    override val grant: StateFlow<LibraryGrant> = state.access.asStateFlow()

    private val read = MutableStateFlow<SelectionSnapshot?>(null)
    override val selection: StateFlow<SelectionSnapshot?> = read.asStateFlow()

    /** This process's observer reads [snapshot] — the operator's changed selection, or the baseline. */
    internal fun deliver(snapshot: SelectionSnapshot) {
        read.value = snapshot
    }

    override fun listen(handlers: GalleryHandlers) {
        state.listener = LibraryState.Listener(this, handlers)
    }

    override fun observeChanges(enabled: Boolean) {
        val opening = enabled && state.observer !== this
        if (enabled) state.observer = this else if (state.observer === this) state.observer = null
        // The baseline: a partial grant's observer reads the selection when it opens. A person who never picked one
        // has nothing to read — the process stays unread until they do.
        val picked = state.selection.value
        if (opening && state.access.value == LibraryGrant.LIMITED && picked != null) deliver(SelectionSnapshot(picked))
    }

    override suspend fun import(request: ImportRequest): ImportResult {
        val handlers = checkNotNull(state.listener?.takeIf { it.face === this }?.handlers) {
            "an import before listen has nowhere to record its marker"
        }
        val ref = request.ref
        state.imports.attempt(ref)
        val attempt = state.locked { (state.attempts.getOrElse(ref) { 0 } + 1).also { state.attempts[ref] = it } }
        fun settle(outcome: ImportResult) = outcome.also { handlers.onImportSettled(ref, it) }
        state.answers.beforeChange(ref)?.let { return settle(ImportResult.Failed(it)) }
        val suffix = if (attempt == 1) "" else "-$attempt"
        val createdLocalId = AssetId("imported-${ref.sourceDeviceId}-${ref.sourceAssetId}$suffix")
        handlers.onImportPlaceholder(ref, createdLocalId)
        state.answers.beforeCommit(ref)?.let { return settle(ImportResult.Failed(it, placeholder = createdLocalId)) }
        val created = createdAsset(createdLocalId, request.ref, request.resources, request.creationDate)
        state.locked {
            state.library.update { it + created }
            state.ownImports += createdLocalId
            if (state.albumKind == AlbumKind.FOLDER) {
                request.album?.takeIf {
                    it in state.created
                }?.let { state.folderOf[createdLocalId] = it }
            }
        }
        state.answers.afterCommit(ref)?.let { return settle(ImportResult.Failed(it, placeholder = createdLocalId)) }
        return settle(ImportResult.Imported(createdLocalId))
    }

    override fun access(): LibraryGrant = state.access.value

    /**
     * An in-memory library holds no bytes and there is no file to write, so an export of a resource it still holds is
     * answered as done and one it no longer holds as failed — the platform's answers, with nothing on a disk.
     */
    override suspend fun export(resource: Resource, to: String): WriteOutcome =
        if (state.library.value.any { asset -> asset.assetId == resource.assetId }) {
            WriteOutcome.Ok
        } else {
            WriteOutcome.Failed("${resource.filename} is no longer in the library")
        }

    override suspend fun assets(filter: AssetFilter): GalleryRead<List<AssetFacts>> {
        state.enumeration.await()
        if (state.locked { state.failNextEnumeration.also { state.failNextEnumeration = false } }) {
            // A platform walk that fails is a failure, not a successful read with no answer (`NotReadable`).
            error("the operator forced this enumeration to fail")
        }
        // Narrowed by the lower bound only, as a platform that cannot express the upper one would: a superset is
        // what the port allows.
        return readable {
            state.library.value.filter {
                capturedFrom(
                    it.creationDate,
                    filter.createdFrom,
                )
            }.map { it.toFacts() }
        }
    }

    override suspend fun assetsById(ids: Set<AssetId>): GalleryRead<List<AssetFacts>> =
        if (!state.byIdReadable) {
            GalleryRead.NotReadable
        } else {
            readable {
                state.library.value.map { it.toFacts() }.filter { it.assetId in ids }
            }
        }

    override suspend fun resources(ids: Set<AssetId>): GalleryRead<List<RawAsset>> = readable {
        state.library.value.filter { it.toFacts().assetId in ids }
    }

    override suspend fun albums(): GalleryRead<List<AlbumRecord>> = readable { allAlbums().map { it.first } }

    override suspend fun albumsById(ids: Set<AlbumId>): GalleryRead<List<AlbumRecord>> = readable {
        val deleted = state.locked { state.deletedAlbums.toSet() }
        allAlbums().map { it.first }.filter { it.id in ids && it.id !in deleted }
    }

    override val albumKind: AlbumKind get() = state.albumKind

    override suspend fun albumMembers(album: AlbumId, createdFrom: Instant?): GalleryRead<Set<AssetId>> = readable {
        val members = allAlbums().firstOrNull { it.first.id == album }?.second.orEmpty()
        val captured = state.library.value
            .filter { capturedFrom(it.creationDate, createdFrom) }
            .mapTo(mutableSetOf()) { it.toFacts().assetId }
        members.filterTo(mutableSetOf()) { it in captured }
    }

    override suspend fun createAlbum(title: String): AlbumId? {
        return state.locked {
            val id = "album-${state.albumCounter++}"
            state.created[id] = LibraryState.Album(title)
            state.createdLog += id to title
            id
        }
    }

    override suspend fun addToAlbum(album: AlbumId, assets: Set<AssetId>): WriteOutcome {
        state.adds.await()
        return state.locked {
            state.addedLog += album to assets.toList()
            val target = state.created[album] ?: return@locked WriteOutcome.Failed("album $album does not resolve")
            val held = state.library.value.mapTo(mutableSetOf()) { it.toFacts().assetId }
            when (state.albumKind) {
                AlbumKind.COLLECTION -> target.members += assets.filter { it in held }
                // A move of what this library imported; anything else is skipped, as the platform refuses it.
                AlbumKind.FOLDER -> assets.filter { it in held && it in state.ownImports }.forEach { state.folderOf[it] = album }
            }
            WriteOutcome.Ok
        }
    }

    override suspend fun requestAccess(): LibraryGrant {
        state.access.compareAndSet(LibraryGrant.NOT_DETERMINED, state.answer)
        return state.access.value
    }

    override suspend fun widenSelection(): LibraryGrant {
        state.pickersShown++
        return state.access.value
    }

    override suspend fun changeToken(): LibraryChangeToken = Token(state.library.value)

    private fun allAlbums(): List<Pair<AlbumRecord, Set<AssetId>>> = createdAlbums() +
        state.userAlbums.value.map { (title, members) -> AlbumRecord("user-album:$title", title) to members }

    /** The app's albums: every one under [AlbumKind.COLLECTION]; under [AlbumKind.FOLDER] only a folder holding a photo. */
    private fun createdAlbums(): List<Pair<AlbumRecord, Set<AssetId>>> = state.locked { createdAlbumsLocked() }

    private fun createdAlbumsLocked(): List<Pair<AlbumRecord, Set<AssetId>>> = when (state.albumKind) {
        AlbumKind.COLLECTION -> state.created.map { (id, album) -> AlbumRecord(id, album.title) to album.members.toSet() }
        AlbumKind.FOLDER -> {
            val held = state.library.value.mapTo(mutableSetOf()) { it.assetId }
            state.created.mapNotNull { (id, album) ->
                val members = state.folderOf.filter { (asset, folder) -> folder == id && asset in held }.keys
                if (members.isEmpty()) null else AlbumRecord(id, album.title) to members
            }
        }
    }

    /** Whether a photo captured at [creationDate] lies at or after [from]; an undated one lies in no range. */
    private fun capturedFrom(creationDate: String, from: Instant?): Boolean {
        if (from == null) return true
        val at = Instant.parseOrNull(creationDate) ?: return false
        return at >= from
    }

    private inline fun <T> readable(read: () -> T): GalleryRead<T> =
        if (state.access.value in READABLE) GalleryRead.Read(read()) else GalleryRead.NotReadable

    private fun createdAsset(id: AssetId, ref: AssetRef, resources: List<StagedResource>, creationDate: String) = RawAsset(
        assetId = id,
        creationDate = creationDate,
        rawResources = resources.map { staged ->
            RawResource(
                role = if (staged.role == ResourceRole.LIVE.wire) ResourceRole.LIVE else ResourceRole.PRIMARY,
                mimeContentType = staged.contentType,
                // The SAME naming rule both platform importers apply (`ReceivedPhotoName`), so an in-memory library
                // carries the mark a reinstalled app reads back, exactly where a device would.
                originalFilename = ReceivedPhotoName.mark(staged.originalFilename, staged.resourceKey, ref),
                handle = Unit,
            )
        },
    )

    private class Token(private val readAt: List<RawAsset>) : LibraryChangeToken {
        override fun sameLibraryAs(other: LibraryChangeToken): Boolean = other is Token && other.readAt === readAt
    }
}

/** The grants under which a library read answers anything — a full or a partial one. */
private val READABLE = setOf(LibraryGrant.GRANTED, LibraryGrant.LIMITED)
