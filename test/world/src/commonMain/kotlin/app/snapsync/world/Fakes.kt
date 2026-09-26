package app.snapsync.world

import app.snapsync.fake.inMemoryGallery
import app.snapsync.fake.inMemoryPhotoAccess
import app.snapsync.model.AlbumId
import app.snapsync.model.AlbumRecord
import app.snapsync.model.AssetFacts
import app.snapsync.model.AssetId
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.GalleryRead
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryHandlers
import app.snapsync.model.ImportRequest
import app.snapsync.model.ImportResult
import app.snapsync.model.SelectionSnapshot
import app.snapsync.ports.LibraryChangeToken
import app.snapsync.model.GalleryAccess
import app.snapsync.model.RawAsset
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.AssetRef
import app.snapsync.ports.DownloadStore
import app.snapsync.model.PendingDownload
import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.feature.upload.AppUploadEvents
import app.snapsync.feature.upload.AppUploadMechanism
import app.snapsync.feature.upload.WalkOutcome
import app.snapsync.ports.BackgroundScheduler
import app.snapsync.model.CycleResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The world's rigging around the honest `:adapter:generic:fake` [inMemoryPhotoAccess] status source: the
 * status is the honest fake's, over [cell], which this wrapper owns and the world's gallery shares, and [set] is
 * the operator's lever — the user changing the grant in Settings, which no port member can do. Drives the status
 * projection's `active` flag.
 */
class MutablePhotoAccessStatusSource(
    initial: GalleryAccess = GalleryAccess.GRANTED,
) : PhotoAccessStatusSource {
    /** The grant cell — shared with the world's [WorldGallery], as one `PHPhotoLibrary` answers both on device. */
    internal val cell = MutableStateFlow(initial)
    private val honest = inMemoryPhotoAccess(cell)

    override val permission: StateFlow<GalleryAccess> = honest.permission

    fun set(value: GalleryAccess) {
        cell.value = value
    }
}

/**
 * The world's gallery: the operator rigging around the honest `:adapter:generic:fake` [inMemoryGallery]
 * (`docs/architecture.md`, "The fake-honesty gate": the fake exposes only its port; the settable cells, the
 * levers and the inspection live HERE). Every answer is the honest fake's — the one the gallery contracts hold
 * to the PhotoKit adapters — except where a lever below says otherwise.
 *
 * [access] is the grant cell the world's permission source owns, so the gallery and the status source agree.
 */
class WorldGallery(
    access: MutableStateFlow<GalleryAccess> = MutableStateFlow(GalleryAccess.GRANTED),
    /** The operator's import script and inspection (see [WorldImports]). */
    val imports: WorldImports = WorldImports(),
) : Gallery {
    private val state = MutableStateFlow<List<RawAsset>>(emptyList())

    /**
     * Pre-existing albums the *user's other apps* made — title → the normalized assetIds inside them. The honest
     * fake reads this cell; [placeIn] is how the harness and the integration tests forge "this photo arrived via
     * WhatsApp" without PhotoKit (capability `photo-sharing`).
     */
    private val userAlbums = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    private val honest: Gallery = inMemoryGallery(state, access, userAlbums, answers = imports.answers)

    /** The handlers the composition registered — how [changeSelection] reaches the core, as the observer would. */
    private var handlers: GalleryHandlers? = null

    /** Inspection: whether the selection observer is open (only host assembly opens it). */
    var observing: Boolean = false
        private set

    /**
     * Operator lever (capability `photo-access`): the member's selection under a partial grant is now [assets] —
     * delivered whole, with its resources, through the registered `onChanged`, as the real observer delivers one.
     * Only while the observer is open: a composition that never assembled its host would hear nothing, so the lever
     * fails loudly rather than silently doing nothing.
     */
    fun changeSelection(assets: List<RawAsset>) {
        check(observing) { "the selection observer is not open — only host assembly opens it" }
        checkNotNull(handlers) { "no handlers registered — the host zone's listen never ran" }.onChanged(SelectionSnapshot(assets))
    }

    // ---- the library ----------------------------------------------------------------------------

    /** The current contents, unscoped — a rigging-only read (production has no unbounded walk). */
    fun current(): List<RawAsset> = state.value

    /** The writable cell itself, for the honest importer, which lands its assets here. */
    internal val cell: MutableStateFlow<List<RawAsset>> get() = state

    /** The same contents as a cell. */
    val contents: StateFlow<List<RawAsset>> = state.asStateFlow()

    fun set(rawAssets: List<RawAsset>) {
        state.value = rawAssets
    }

    /**
     * Operator lever: make the next walk THROW, as a platform walk can (capability `sync-status`). It exists so
     * a test can assert what a failed count does — the total stays *not counted* rather than collapsing to a `0`
     * that would read as "everything shared" — which is otherwise unreachable without a device.
     *
     * It THROWS rather than answering `NotReadable`, and the distinction is the point: a platform walk that
     * fails is a failure, caught by whoever owns the count, while `NotReadable` is a successful read with no
     * answer to give. Modelling the failure as an absence would collapse the two states the world keeps apart.
     */
    var failNextEnumeration: Boolean = false

    /**
     * Operator lever: the by-identifier read cannot see the library, as a partial or revoked grant's cannot —
     * every presence answer is then `UNKNOWN`, which must never be confused with `ABSENT`.
     */
    var byIdReadable: Boolean = true

    // ---- the albums -------------------------------------------------------------------------------

    val created = mutableListOf<Pair<String, String>>()      // (albumId, name)
    val added = mutableListOf<Pair<String, List<String>>>()   // (albumId, assetIds)
    private val deleted = mutableSetOf<String>()
    private var addsHeld: CompletableDeferred<Unit>? = null

    /** Put [assetId] into an album titled [title] — e.g. `placeIn("WhatsApp", "A1")`. */
    fun placeIn(title: String, assetId: String) {
        userAlbums.value = userAlbums.value + (title to (userAlbums.value[title].orEmpty() + assetId))
    }

    /** Simulate the user deleting an album (so it no longer resolves and a re-join recreates). */
    fun delete(albumId: String) { deleted.add(albumId) }

    /** Every asset id added to [albumId] across all adds, in order. */
    fun assetsIn(albumId: String): List<String> = added.filter { it.first == albumId }.flatMap { it.second }

    /**
     * Operator lever: every add waits until [releaseAdds]. During a join or a reconfigure Save only the event
     * album's gather adds, so this is how a test shows the act that started a gather never waits on it
     * (capability `event-album`).
     */
    fun holdAdds() { addsHeld = CompletableDeferred() }

    fun releaseAdds() {
        addsHeld?.complete(Unit)
        addsHeld = null
    }

    // ---- the port ---------------------------------------------------------------------------------

    override fun access(): GalleryAccess = honest.access()

    override suspend fun assets(policy: SelectionPolicy): GalleryRead<List<AssetFacts>> {
        if (failNextEnumeration) {
            failNextEnumeration = false
            error("the operator forced this enumeration to fail")
        }
        return honest.assets(policy)
    }

    override suspend fun assetsById(ids: Set<AssetId>): GalleryRead<List<AssetFacts>> =
        if (byIdReadable) honest.assetsById(ids) else GalleryRead.NotReadable

    override suspend fun resources(ids: Set<AssetId>): GalleryRead<List<RawAsset>> = honest.resources(ids)

    override suspend fun albums(): GalleryRead<List<AlbumRecord>> = honest.albums()

    override suspend fun albumsById(ids: Set<AlbumId>): GalleryRead<List<AlbumRecord>> =
        honest.albumsById(ids - deleted)

    override suspend fun albumMembers(album: AlbumId, since: CaptureCutoff?): GalleryRead<Set<AssetId>> =
        honest.albumMembers(album, since)

    override suspend fun createAlbum(title: String): AlbumId? =
        honest.createAlbum(title)?.also { created.add(it to title) }

    override suspend fun addToAlbum(album: AlbumId, assets: Set<AssetId>): WriteOutcome {
        addsHeld?.await()
        added.add(album to assets.toList())
        return honest.addToAlbum(album, assets)
    }

    override suspend fun requestAccess(): GalleryAccess = honest.requestAccess()

    override suspend fun widenSelection(): GalleryAccess = honest.widenSelection()

    override suspend fun changeToken(): LibraryChangeToken? = honest.changeToken()

    override fun listen(handlers: GalleryHandlers) {
        this.handlers = handlers
        honest.listen(handlers)
    }

    override fun observeChanges(enabled: Boolean) {
        observing = enabled
        honest.observeChanges(enabled)
    }

    override suspend fun import(request: ImportRequest): ImportResult {
        imports.attempt(request.ref)
        return honest.import(request)
    }
}

/**
 * The world's download store: the recording wrapper around the honest `:adapter:generic:fake`
 * [app.snapsync.fake.inMemoryDownloadStore]. [enqueueRequests] records what the real
 * `DownloadController` sent to the OS (it marks each enqueued batch through this port), replacing
 * the pre-step-10 `recordingJobs` interception — the real jobs still do all the work, and the
 * transfer-description codec stays `internal` to `:domain`. Cleared on [pruneNonTerminal] (the
 * leave/switch path), mirroring the old recorder's clear-on-cancelAll timing.
 */
class RecordingDownloadStore(private val inner: DownloadStore) : DownloadStore by inner {

    /** Inspection: every (asset, resourceKey) the controller enqueued, in order. */
    val enqueueRequests = mutableListOf<Pair<AssetRef, String>>()

    override suspend fun markEnqueued(ref: AssetRef, resourceKey: String) {
        enqueueRequests += ref to resourceKey
        inner.markEnqueued(ref, resourceKey)
    }

    // The controller marks a reconcile's whole batch in one call; recorded per resource, in order, as before.
    override suspend fun markAllEnqueued(downloads: Collection<PendingDownload>) {
        downloads.forEach { enqueueRequests += it.ref to it.resource.resourceKey }
        inner.markAllEnqueued(downloads)
    }

    override suspend fun pruneNonTerminal(protecting: Set<AssetRef>): List<String> {
        enqueueRequests.clear()
        return inner.pruneNonTerminal(protecting)
    }
}


/**
 * The world's app-driven [AppUploadMechanism]: its units are inert, because **the operator is the engine** — nothing
 * uploads on its own in the world (`docs/testing.md`), and a cycle happens only when the operator invokes it.
 * The composed tail runner still drives these units from every wake the world's OS entries deliver, so what is counted
 * here is what the runner asked of the uploader: a test reads which units a wake reached, in the real order.
 *
 * [events] is the core the world composed, resolved per call: the world's transfer session has nothing in flight, so a
 * [reattach] reports its events drained at once, which is what releases the handler the wake handed over.
 */
class OperatorUploadEngine(private val events: () -> AppUploadEvents) : AppUploadMechanism {
    /** How many top-ups (②) the tail asked for. */
    var topUps: Int = 0
        private set

    /** How many walks (③) — including a selection change's own work — the tail asked for. */
    var walks: Int = 0
        private set

    /** How many background-transfer handbacks reached this uploader's session. */
    var transferHandbacks: Int = 0
        private set

    /** What each top-up answers — the operator's lever for a truncated or declining pass. */
    var topUpResult: CycleResult = CycleResult.COMPLETED

    /**
     * Operator lever: park the next unit until the gate completes — how a test holds a tail in flight to deliver an
     * expiry, or a join, while it runs. Consumed by the unit it parks.
     */
    @kotlin.concurrent.Volatile
    var nextUnitGate: CompletableDeferred<Unit>? = null

    /** The heartbeat the tail re-arms — counted, never run (the operator plays the OS). */
    override val heartbeat: CountingScheduler = CountingScheduler()

    override suspend fun topUp(stopRequested: () -> Boolean): CycleResult {
        topUps++
        park()
        return topUpResult
    }

    override suspend fun walkAndPublish(stopRequested: () -> Boolean): WalkOutcome {
        walks++
        park()
        return if (stopRequested()) WalkOutcome.Abandoned else WalkOutcome.Walked(CycleResult.COMPLETED, addedRows = false)
    }

    override suspend fun cancelTransfers() = Unit

    override fun reattach() {
        transferHandbacks++
        // Nothing is in flight in the world, so the session has nothing to deliver: its drain report comes at once.
        events().eventsDrained()
    }

    private suspend fun park() {
        val gate = nextUnitGate ?: return
        nextUnitGate = null
        gate.await()
    }
}

/** A background task scheduler that only counts: the operator plays the OS, so a scheduled wake never runs itself. */
class CountingScheduler : BackgroundScheduler {
    var scheduled: Int = 0
        private set
    var cancelled: Int = 0
        private set

    override fun scheduleNext() {
        scheduled++
    }

    override fun cancel() {
        cancelled++
    }
}
