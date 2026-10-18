package app.snapsync.mock

import app.snapsync.inbound.CompositionOnly
import app.snapsync.levers.WorldEvent
import app.snapsync.ports.AssetFilter
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryAlbum
import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.GalleryImportRequest
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryResource
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.ImportOutcome
import app.snapsync.ports.ImportPlaceholder
import app.snapsync.ports.ImportSettled
import app.snapsync.ports.LibraryAlbumKind
import app.snapsync.ports.LibraryChangeToken
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAlbumId
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.SelectionSnapshot
import app.snapsync.ports.WriteOutcome
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
 * - **No grant, no read**: every read answers `NotReadable` unless the grant is full or partial, and with no grant the
 *   library creates no album and answers no change token either — as iOS answers a process it has not granted. There
 *   is no selection here, so a partial grant reads the whole library — the core reads the held snapshot instead.
 * - **[assets] translates only the two REQUIRED narrowings** — the capture floor and deny-everything. The
 *   platform's predicate is an *optimization* the authoritative admission must be proven to work without,
 *   so a mock that mirrored it would hide exactly the bug that matters: an
 *   admission relying on the fetch to have excluded something. The floor is mirrored because it is required
 *   rather than advisory: without it the real walk is unbounded.
 * - Albums: an asset added to an album is a member of it; an asset the library does not hold is skipped;
 *   adding to an album that does not exist fails. Albums it creates get deterministic ids (`album-<n>`), the other
 *   apps' albums `user-album:<title>`.
 * - **A [LibraryAlbumKind.FOLDER] library plays Android's**: an album the app created is the folder its photos live in, so it
 *   resolves and lists only while it holds one; an import with an album lands in that folder; an add MOVES a photo this
 *   library imported and skips any other (the platform will not let the app move a camera photo). Every read reaches
 *   into the album folders as into any folder of the default gallery.
 * - [requestAccess] applies the state's answer **only while the grant is undetermined**, because a platform asks
 *   once — and only then is the dialog raised ([WorldEvent.LibraryAccessAsked]). [widenSelection] presents the picker
 *   ([WorldEvent.LibraryPickerShown]) only under a partial grant, the one a platform has a picker for; it changes
 *   nothing — the selection the person picks arrives through the operator.
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

    @CompositionOnly
    override fun inbound(handlers: GalleryHandlers) {
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

    override suspend fun import(request: GalleryImportRequest): ImportOutcome {
        val handlers = checkNotNull(state.listener?.takeIf { it.face === this }?.handlers) {
            "an import before listen has nowhere to record its marker"
        }
        val correlation = request.correlation
        state.imports.attempt(correlation)
        val attempt = state.locked {
            (state.attempts.getOrElse(correlation) { 0 } + 1).also { state.attempts[correlation] = it }
        }
        fun settle(outcome: ImportOutcome) = outcome.also {
            handlers.onImportSettled.deliver(ImportSettled(correlation, it))
        }
        state.answers.beforeChange(correlation)?.let { return settle(ImportOutcome.Failed(it)) }
        val suffix = if (attempt == 1) "" else "-$attempt"
        val createdLocalId = PlatformAssetId("imported-${correlation.replace('/', '-')}$suffix")
        handlers.onImportPlaceholder.deliver(ImportPlaceholder(correlation, createdLocalId))
        // The library takes a staged file as it ingests it, before decoding it: an undecodable one is gone either way.
        if (request.files.any { state.undecodable(it.path) }) {
            return settle(
                ImportOutcome.Failed(
                    "the staged file cannot be decoded",
                    consumedResources = true,
                    placeholder = createdLocalId,
                ),
            )
        }
        state.answers.beforeCommit(correlation)?.let {
            return settle(ImportOutcome.Failed(it, placeholder = createdLocalId))
        }
        val created = createdAsset(createdLocalId, request)
        val filedInto = state.locked {
            state.library.update { it + created }
            state.ownImports += createdLocalId
            // Filed in the commit that creates it; an album the library no longer holds files nothing and fails nothing.
            val album = request.album?.value?.takeIf { it in state.created && it !in state.deletedAlbums }
            when (state.albumKind) {
                LibraryAlbumKind.FOLDER -> album?.also { state.folderOf[createdLocalId] = it }
                LibraryAlbumKind.COLLECTION -> album?.also { state.created.getValue(it).members += createdLocalId }
            }
        }
        state.watcher.told(WorldEvent.LibraryAssetCreated(createdLocalId.value, request.files.map { it.name }))
        filedInto?.let { state.watcher.told(WorldEvent.LibraryJoinedAlbum(it, createdLocalId.value)) }
        state.answers.afterCommit(correlation)?.let {
            return settle(ImportOutcome.Failed(it, placeholder = createdLocalId))
        }
        return settle(ImportOutcome.Imported(createdLocalId))
    }

    override fun access(): LibraryGrant = state.access.value

    /**
     * An export of a resource the library still holds, by its own handle, writes that resource's bytes to [to] — the
     * stand-in bytes an in-memory library keeps, its file name — kept in [LibraryState.exported]. Any other
     * handle, or a resource no longer held, cannot be produced, and nothing is written.
     */
    override suspend fun export(resource: GalleryResourceHandle, to: String): WriteOutcome {
        val asset = (resource as? InMemoryResource)?.asset ?: return WriteOutcome.Failed("$resource is not this library's")
        return if (state.library.value.any { it.id == asset }) {
            state.exported += to to asset.value.encodeToByteArray()
            WriteOutcome.Ok
        } else {
            WriteOutcome.Failed("$asset is no longer in the library")
        }
    }

    override suspend fun assets(filter: AssetFilter): GalleryRead<List<GalleryAsset>> {
        state.enumeration.await { state.watcher.told(WorldEvent.LibraryEnumerationHeld) }
        if (state.locked { state.failNextEnumeration.also { state.failNextEnumeration = false } }) {
            // A platform walk that fails is a failure, not a successful read with no answer (`NotReadable`).
            error("the operator forced this enumeration to fail")
        }
        // Narrowed by the lower bound only, as a platform that cannot express the upper one would: a superset is
        // what the port allows.
        return readable {
            state.library.value.filter { capturedFrom(it.asset.created, filter.createdFrom) }.map { it.asset }
        }
    }

    override suspend fun assetsById(ids: Set<PlatformAssetId>): GalleryRead<List<GalleryAsset>> =
        if (!state.byIdReadable) {
            GalleryRead.NotReadable
        } else {
            readable {
                state.library.value.map { it.asset }.filter { it.id in ids }
            }
        }

    override suspend fun resources(ids: Set<PlatformAssetId>): GalleryRead<List<GalleryAssetResources>> = readable {
        state.library.value.filter { it.id in ids }
    }

    override suspend fun albums(): GalleryRead<List<GalleryAlbum>> = readable { allAlbums().map { it.first } }

    override suspend fun albumsById(ids: Set<PlatformAlbumId>): GalleryRead<List<GalleryAlbum>> = readable {
        val deleted = state.locked { state.deletedAlbums.toSet() }
        allAlbums().map { it.first }.filter { it.id in ids && it.id.value !in deleted }
    }

    override val albumKind: LibraryAlbumKind get() = state.albumKind

    override suspend fun albumMembers(album: PlatformAlbumId, createdFrom: Instant?): GalleryRead<Set<PlatformAssetId>> = readable {
        val members = allAlbums().firstOrNull { it.first.id == album }?.second.orEmpty()
        val captured = state.library.value
            .filter { capturedFrom(it.asset.created, createdFrom) }
            .mapTo(mutableSetOf()) { it.id }
        members.filterTo(mutableSetOf()) { it in captured }
    }

    override suspend fun createAlbum(title: String): PlatformAlbumId? {
        // A process with no grant is refused the library's writes too, as iOS refuses it (GalleryReader contract).
        if (state.access.value !in READABLE) return null
        val id = state.locked {
            "album-${state.albumCounter++}".also { id ->
                state.created[id] = LibraryState.Album(title)
                state.createdLog += id to title
            }
        }
        state.watcher.told(WorldEvent.LibraryAlbumCreated(id, title))
        return PlatformAlbumId(id)
    }

    override suspend fun addToAlbum(album: PlatformAlbumId, assets: Set<PlatformAssetId>): WriteOutcome {
        val albumId = album.value
        state.adds.await { state.watcher.told(WorldEvent.LibraryAddHeld(albumId)) }
        val joined = mutableListOf<PlatformAssetId>()
        val outcome = state.locked {
            state.addedLog += albumId to assets.toList()
            val target = state.created[albumId]?.takeIf { albumId !in state.deletedAlbums }
                ?: return@locked WriteOutcome.Failed("album $albumId does not resolve")
            val held = state.library.value.mapTo(mutableSetOf()) { it.id }
            when (state.albumKind) {
                LibraryAlbumKind.COLLECTION -> assets.filter { it in held && target.members.add(it) }.forEach { joined += it }
                // A move of what this library imported; anything else is skipped, as the platform refuses it.
                LibraryAlbumKind.FOLDER -> assets.filter {
                    it in held && it in state.ownImports && state.folderOf[it] != albumId
                }.forEach {
                    state.folderOf[it] = albumId
                    joined += it
                }
            }
            WriteOutcome.Ok
        }
        joined.forEach { state.watcher.told(WorldEvent.LibraryJoinedAlbum(albumId, it.value)) }
        return outcome
    }

    override suspend fun requestAccess(): LibraryGrant {
        if (state.access.compareAndSet(LibraryGrant.NOT_DETERMINED, state.answer)) {
            state.watcher.told(WorldEvent.LibraryAccessAsked)
        }
        return state.access.value
    }

    override suspend fun widenSelection(): LibraryGrant {
        // Only a partial grant has a selection to widen; under any other the platform asks nothing (the Gallery contract).
        if (state.access.value == LibraryGrant.LIMITED) {
            state.pickersShown++
            state.watcher.told(WorldEvent.LibraryPickerShown)
        }
        return state.access.value
    }

    /** A process with no grant is told nothing of the library, not even its change token (the Gallery contract). */
    override suspend fun changeToken(): LibraryChangeToken? =
        if (state.access.value in READABLE) Token(state.library.value) else null

    private fun allAlbums(): List<Pair<GalleryAlbum, Set<PlatformAssetId>>> = createdAlbums() +
        state.userAlbums.value.map { (title, members) -> GalleryAlbum(PlatformAlbumId("user-album:$title"), title) to members }

    /**
     * The app's albums: every one under [LibraryAlbumKind.COLLECTION]; under [LibraryAlbumKind.FOLDER] only a folder
     * holding a photo.
     */
    private fun createdAlbums(): List<Pair<GalleryAlbum, Set<PlatformAssetId>>> = state.locked { createdAlbumsLocked() }

    private fun createdAlbumsLocked(): List<Pair<GalleryAlbum, Set<PlatformAssetId>>> = when (state.albumKind) {
        // A collection the member deleted is gone, whatever the app still records of it.
        LibraryAlbumKind.COLLECTION -> state.created.filterKeys { it !in state.deletedAlbums }.map { (id, album) ->
            GalleryAlbum(PlatformAlbumId(id), album.title) to album.members.toSet()
        }
        LibraryAlbumKind.FOLDER -> {
            val held = state.library.value.mapTo(mutableSetOf()) { it.id }
            state.created.mapNotNull { (id, album) ->
                val members = state.folderOf.filter { (asset, folder) -> folder == id && asset in held }.keys
                if (members.isEmpty()) null else GalleryAlbum(PlatformAlbumId(id), album.title) to members
            }
        }
    }

    /** Whether a photo captured at [created] lies at or after [from]; an undated one lies in no range. */
    private fun capturedFrom(
        created: Instant?,
        from: Instant?,
    ): Boolean = from == null || (created != null && created >= from)

    private inline fun <T> readable(read: () -> T): GalleryRead<T> =
        if (state.access.value in READABLE) GalleryRead.Read(read()) else GalleryRead.NotReadable

    private fun createdAsset(id: PlatformAssetId, request: GalleryImportRequest) = GalleryAssetResources(
        GalleryAsset(
            id = id,
            created = request.created,
            isScreenshot = false,
            isScreenRecording = false,
            isVideo = false,
            isEdited = false,
            pixelArea = null,
        ),
        // Each file under the name it was handed: the library stores what the app named it.
        request.files.map { GalleryResource(it.kind, it.contentType, it.name, InMemoryResource(id)) },
    )

    private class Token(private val readAt: List<GalleryAssetResources>) : LibraryChangeToken {
        override fun sameLibraryAs(other: LibraryChangeToken): Boolean = other is Token && other.readAt === readAt
    }
}

/** The grants under which a library read answers anything — a full or a partial one. */
private val READABLE = setOf(LibraryGrant.GRANTED, LibraryGrant.LIMITED)
