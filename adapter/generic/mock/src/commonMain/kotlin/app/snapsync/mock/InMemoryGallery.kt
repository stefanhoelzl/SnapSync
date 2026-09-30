package app.snapsync.mock

import app.snapsync.model.AlbumId
import app.snapsync.model.AlbumRecord
import app.snapsync.model.AssetFacts
import app.snapsync.model.AssetId
import app.snapsync.model.ImportRequest
import app.snapsync.model.ImportResult
import app.snapsync.model.RawResource
import app.snapsync.model.Resource
import app.snapsync.model.ResourceRole
import app.snapsync.model.StagedResource
import app.snapsync.model.importFilename
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.GalleryAccess
import app.snapsync.model.GalleryRead
import app.snapsync.model.RawAsset
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.SelectionRule
import app.snapsync.model.SelectionSnapshot
import app.snapsync.model.WriteOutcome
import app.snapsync.model.grantsPhotoAccess
import app.snapsync.model.toFacts
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.LibraryChangeToken

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
 *   platform's predicate is an *optimization* the authoritative admission must be proven to work without
 *   (capability `photo-sharing`), so a mock that mirrored it would hide exactly the bug that matters: an
 *   admission relying on the fetch to have excluded something. The floor is mirrored because it is required
 *   rather than advisory: without it the real walk is unbounded.
 * - Albums: an asset added to an album is a member of it; an asset the library does not hold is skipped;
 *   adding to an album that does not exist fails. Albums it creates get deterministic ids (`album-<n>`), the other
 *   apps' albums `user-album:<title>`.
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

    override fun listen(handlers: GalleryHandlers) {
        state.listener = LibraryState.Listener(this, handlers)
    }

    override fun observeChanges(enabled: Boolean) {
        val listener = state.listener?.takeIf { it.face === this } ?: return
        val opening = enabled && !listener.observing
        listener.observing = enabled
        // The baseline: a partial grant's observer reads the selection when it opens. A person who never picked one
        // has nothing to read — the process stays unread until they do.
        val picked = state.selection.value
        if (opening && state.access.value == GalleryAccess.LIMITED && picked != null) {
            listener.handlers.onChanged(SelectionSnapshot(picked))
        }
    }

    override suspend fun import(request: ImportRequest): ImportResult {
        val handlers = checkNotNull(state.listener?.takeIf { it.face === this }?.handlers) {
            "an import before listen has nowhere to record its marker"
        }
        val ref = request.ref
        state.imports.attempt(ref)
        val attempt = state.attempts.getOrElse(ref) { 0 } + 1
        state.attempts[ref] = attempt
        fun settle(outcome: ImportResult) = outcome.also { handlers.onImportSettled(ref, it) }
        state.answers.beforeChange(ref)?.let { return settle(ImportResult.Failed(it)) }
        val suffix = if (attempt == 1) "" else "-$attempt"
        val createdLocalId = AssetId("imported-${ref.sourceDeviceId}-${ref.sourceAssetId}$suffix")
        handlers.onImportPlaceholder(ref, createdLocalId)
        state.answers.beforeCommit(ref)?.let { return settle(ImportResult.Failed(it, placeholder = createdLocalId)) }
        state.library.value = state.library.value + createdAsset(createdLocalId, request.resources, request.creationDate)
        state.answers.afterCommit(ref)?.let { return settle(ImportResult.Failed(it, placeholder = createdLocalId)) }
        return settle(ImportResult.Imported(createdLocalId))
    }

    override fun access(): GalleryAccess = state.access.value

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

    override suspend fun assets(policy: SelectionPolicy): GalleryRead<List<AssetFacts>> {
        state.enumerationHeld?.await()
        if (state.failNextEnumeration) {
            state.failNextEnumeration = false
            // A platform walk that fails is a failure, not a successful read with no answer (`NotReadable`).
            error("the operator forced this enumeration to fail")
        }
        return readable {
            if (policy.rules.any { it.deniesEverything }) {
                emptyList()
            } else {
                val floor = policy.rules.filterIsInstance<SelectionRule.CaptureAfter>().maxOfOrNull { it.cutoff.at.iso }
                state.library.value.filter { floor == null || it.creationDate >= floor }.map { it.toFacts() }
            }
        }
    }

    override suspend fun assetsById(ids: Set<AssetId>): GalleryRead<List<AssetFacts>> =
        if (!state.byIdReadable) GalleryRead.NotReadable else readable {
            state.library.value.map { it.toFacts() }.filter { it.assetId in ids }
        }

    override suspend fun resources(ids: Set<AssetId>): GalleryRead<List<RawAsset>> = readable {
        state.library.value.filter { it.toFacts().assetId in ids }
    }

    override suspend fun albums(): GalleryRead<List<AlbumRecord>> = readable { allAlbums().map { it.first } }

    override suspend fun albumsById(ids: Set<AlbumId>): GalleryRead<List<AlbumRecord>> = readable {
        allAlbums().map { it.first }.filter { it.id in ids && it.id !in state.deletedAlbums }
    }

    override suspend fun albumMembers(album: AlbumId, since: CaptureCutoff?): GalleryRead<Set<AssetId>> = readable {
        val members = allAlbums().firstOrNull { it.first.id == album }?.second.orEmpty()
        val captured = state.library.value
            .filter { since == null || it.creationDate >= since.at.iso }
            .mapTo(mutableSetOf()) { it.toFacts().assetId }
        members.filterTo(mutableSetOf()) { it in captured }
    }

    override val supportsAlbumWrites: Boolean get() = state.albumWrites

    override suspend fun createAlbum(title: String): AlbumId? {
        if (!state.albumWrites) return null
        val id = "album-${state.albumCounter++}"
        state.created[id] = LibraryState.Album(title)
        state.createdLog += id to title
        return id
    }

    override suspend fun addToAlbum(album: AlbumId, assets: Set<AssetId>): WriteOutcome {
        if (!state.albumWrites) return WriteOutcome.Failed("this library files no photo into an album")
        state.addsHeld?.await()
        state.addedLog += album to assets.toList()
        val target = state.created[album] ?: return WriteOutcome.Failed("album $album does not resolve")
        val held = state.library.value.mapTo(mutableSetOf()) { it.toFacts().assetId }
        target.members += assets.filter { it in held }
        return WriteOutcome.Ok
    }

    override suspend fun requestAccess(): GalleryAccess {
        if (state.access.value == GalleryAccess.NOT_DETERMINED) state.access.value = state.answer
        return state.access.value
    }

    override suspend fun widenSelection(): GalleryAccess = state.access.value

    override suspend fun changeToken(): LibraryChangeToken = Token(state.library.value)

    private fun allAlbums(): List<Pair<AlbumRecord, Set<AssetId>>> =
        state.created.map { (id, album) -> AlbumRecord(id, album.title) to album.members.toSet() } +
            state.userAlbums.value.map { (title, members) -> AlbumRecord("user-album:$title", title) to members }

    private inline fun <T> readable(read: () -> T): GalleryRead<T> =
        if (state.access.value.grantsPhotoAccess) GalleryRead.Read(read()) else GalleryRead.NotReadable

    private fun createdAsset(id: AssetId, resources: List<StagedResource>, creationDate: String) = RawAsset(
        assetId = id,
        creationDate = creationDate,
        rawResources = resources.map { staged ->
            RawResource(
                role = if (staged.role == ResourceRole.LIVE.wire) ResourceRole.LIVE else ResourceRole.PRIMARY,
                mimeContentType = staged.contentType,
                // The SAME naming rule the iOS importer applies (`importFilename`), so an in-memory library
                // cannot show a human name where a device would show a storage key.
                originalFilename = importFilename(staged.originalFilename, staged.resourceKey),
                handle = Unit,
            )
        },
    )

    private class Token(private val readAt: List<RawAsset>) : LibraryChangeToken {
        override fun sameLibraryAs(other: LibraryChangeToken): Boolean = other is Token && other.readAt === readAt
    }
}
