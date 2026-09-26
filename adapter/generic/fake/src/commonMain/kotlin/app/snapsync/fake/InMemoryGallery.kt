package app.snapsync.fake

import app.snapsync.model.AlbumId
import app.snapsync.model.AlbumRecord
import app.snapsync.model.AssetFacts
import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
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
import app.snapsync.model.WriteOutcome
import app.snapsync.model.grantsPhotoAccess
import app.snapsync.model.toFacts
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.LibraryChangeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The honest in-memory [Gallery] (`docs/architecture.md`), held to the gallery contracts exactly as the PhotoKit
 * adapters are. Every piece of state is the caller's own cell, per the fake-honesty rule: [library] (the assets),
 * [access] (the grant), and [userAlbums] (the albums other apps made — title → asset ids, which is how the world
 * forges "this photo arrived via WhatsApp"). Levers and inspection live in `:test:world` wrappers.
 *
 * It answers as the platform does:
 * - **No grant, no read**: every read answers `NotReadable` unless the grant is full or partial. There is no
 *   selection here, so a partial grant reads the whole library — the core reads the held snapshot instead.
 * - **[assets] translates only the two REQUIRED narrowings** — the capture floor and deny-everything. The
 *   platform's predicate is an *optimization* the authoritative admission must be proven to work without
 *   (capability `photo-sharing`), so a fake that mirrored it would hide exactly the bug that matters: an
 *   admission relying on the fetch to have excluded something. The floor is mirrored because it is required
 *   rather than advisory: without it the real walk is unbounded.
 * - Albums: an asset added to an album is a member of it; an asset the library does not hold is skipped;
 *   adding to an album that does not exist fails. Albums this fake creates get deterministic ids (`album-<n>`),
 *   the other apps' albums `user-album:<title>`.
 * - [requestAccess] applies [answer] **only while the grant is undetermined**, because a platform asks once. The
 *   selection picker has no surface off device and changes nothing.
 * - **An import is two-phase, exactly like the real adapter**: the placeholder reaches `onImportPlaceholder` inside
 *   the "change block", before the asset is observable, and the outcome reaches `onImportSettled` before [import]
 *   returns. [answers] is how the library answers each change (see [LibraryChangeAnswers]). **Every import mints a
 *   fresh identifier**, as `PHAssetCreationRequest` does, counted before anything can fail, so a repeat import
 *   never lands on an earlier import's handle; the first keeps the bare form `imported-<device>-<asset>`. An import
 *   that lands adds its asset to [library].
 * - There is no selection here, so the observer never emits; a test plays the selection through the handlers it
 *   registered.
 * - A change token is the library **value** it was read at: every change to [library] replaces that value, so a
 *   token read after it never compares equal to one read before. Identity of the held value is the comparison on
 *   purpose — a content comparison would call a remove-then-restore "unchanged", which the platform does not.
 */
internal class InMemoryGallery(
    private val library: MutableStateFlow<List<RawAsset>>,
    private val access: MutableStateFlow<GalleryAccess>,
    private val userAlbums: StateFlow<Map<String, Set<AssetId>>>,
    private val answer: GalleryAccess,
    private val answers: LibraryChangeAnswers,
) : Gallery {

    private var handlers: GalleryHandlers? = null
    private val attempts = mutableMapOf<AssetRef, Int>()

    override fun listen(handlers: GalleryHandlers) {
        this.handlers = handlers
    }

    override fun observeChanges(enabled: Boolean) = Unit

    override suspend fun import(request: ImportRequest): ImportResult {
        val handlers = checkNotNull(handlers) { "an import before listen has nowhere to record its marker" }
        val ref = request.ref
        val attempt = attempts.getOrElse(ref) { 0 } + 1
        attempts[ref] = attempt
        fun settle(outcome: ImportResult) = outcome.also { handlers.onImportSettled(ref, it) }
        answers.beforeChange(ref)?.let { return settle(ImportResult.Failed(it)) }
        val suffix = if (attempt == 1) "" else "-$attempt"
        val createdLocalId = AssetId("imported-${ref.sourceDeviceId}-${ref.sourceAssetId}$suffix")
        handlers.onImportPlaceholder(ref, createdLocalId)
        answers.beforeCommit(ref)?.let { return settle(ImportResult.Failed(it, placeholder = createdLocalId)) }
        library.value = library.value + createdAsset(createdLocalId, request.resources, request.creationDate)
        answers.afterCommit(ref)?.let { return settle(ImportResult.Failed(it, placeholder = createdLocalId)) }
        return settle(ImportResult.Imported(createdLocalId))
    }

    private class Album(val title: String, val members: MutableSet<AssetId> = mutableSetOf())

    private var counter = 0
    private val created = mutableMapOf<AlbumId, Album>()

    override fun access(): GalleryAccess = access.value

    /**
     * An in-memory library holds no bytes and there is no file to write, so an export of a resource it still holds is
     * answered as done and one it no longer holds as failed — the platform's answers, with nothing on a disk. A file
     * uploader over this double sends nothing real; the world's uploader takes resources, not files.
     */
    override suspend fun export(resource: Resource, to: String): WriteOutcome =
        if (library.value.any { asset -> asset.assetId == resource.assetId }) {
            WriteOutcome.Ok
        } else {
            WriteOutcome.Failed("${resource.filename} is no longer in the library")
        }

    override suspend fun assets(policy: SelectionPolicy): GalleryRead<List<AssetFacts>> = readable {
        if (policy.rules.any { it.deniesEverything }) {
            emptyList()
        } else {
            val floor = policy.rules.filterIsInstance<SelectionRule.CaptureAfter>().maxOfOrNull { it.cutoff.at.iso }
            library.value.filter { floor == null || it.creationDate >= floor }.map { it.toFacts() }
        }
    }

    override suspend fun assetsById(ids: Set<AssetId>): GalleryRead<List<AssetFacts>> = readable {
        library.value.map { it.toFacts() }.filter { it.assetId in ids }
    }

    override suspend fun resources(ids: Set<AssetId>): GalleryRead<List<RawAsset>> = readable {
        library.value.filter { it.toFacts().assetId in ids }
    }

    override suspend fun albums(): GalleryRead<List<AlbumRecord>> = readable { allAlbums().map { it.first } }

    override suspend fun albumsById(ids: Set<AlbumId>): GalleryRead<List<AlbumRecord>> = readable {
        allAlbums().map { it.first }.filter { it.id in ids }
    }

    override suspend fun albumMembers(album: AlbumId, since: CaptureCutoff?): GalleryRead<Set<AssetId>> = readable {
        val members = allAlbums().firstOrNull { it.first.id == album }?.second.orEmpty()
        val captured = library.value
            .filter { since == null || it.creationDate >= since.at.iso }
            .mapTo(mutableSetOf()) { it.toFacts().assetId }
        members.filterTo(mutableSetOf()) { it in captured }
    }

    override suspend fun createAlbum(title: String): AlbumId {
        val id = "album-${counter++}"
        created[id] = Album(title)
        return id
    }

    override suspend fun addToAlbum(album: AlbumId, assets: Set<AssetId>): WriteOutcome {
        val target = created[album] ?: return WriteOutcome.Failed("album $album does not resolve")
        val held = library.value.mapTo(mutableSetOf()) { it.toFacts().assetId }
        target.members += assets.filter { it in held }
        return WriteOutcome.Ok
    }

    override suspend fun requestAccess(): GalleryAccess {
        if (access.value == GalleryAccess.NOT_DETERMINED) access.value = answer
        return access.value
    }

    override suspend fun widenSelection(): GalleryAccess = access.value

    override suspend fun changeToken(): LibraryChangeToken = Token(library.value)

    private fun allAlbums(): List<Pair<AlbumRecord, Set<AssetId>>> =
        created.map { (id, album) -> AlbumRecord(id, album.title) to album.members.toSet() } +
            userAlbums.value.map { (title, members) -> AlbumRecord("user-album:$title", title) to members }

    private inline fun <T> readable(read: () -> T): GalleryRead<T> =
        if (access.value.grantsPhotoAccess) GalleryRead.Read(read()) else GalleryRead.NotReadable

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
