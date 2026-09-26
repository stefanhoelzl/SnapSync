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
import app.snapsync.feature.upload.AppUploadMechanism
import app.snapsync.feature.upload.WalkOutcome
import app.snapsync.fake.inMemoryWake
import app.snapsync.model.ScheduleResult
import app.snapsync.model.WakeId
import app.snapsync.model.WakeTrigger
import app.snapsync.model.ChangeOutcome
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadJobSet
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadSourceKind
import app.snapsync.model.UploadTarget
import app.snapsync.ports.Completion
import app.snapsync.ports.Upload
import app.snapsync.ports.UploadHandlers
import app.snapsync.ports.Wake
import app.snapsync.ports.WakeHandlers
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

    override suspend fun export(resource: app.snapsync.model.Resource, to: String): WriteOutcome =
        honest.export(resource, to)

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

    /** Inspection: every staging the store took, in order — the store write a download wake's handler waits for. */
    val stagings = mutableListOf<Pair<AssetRef, String>>()

    override suspend fun markStaged(ref: AssetRef, resourceKey: String, stagedPath: String): Boolean =
        inner.markStaged(ref, resourceKey, stagedPath).also { if (it) stagings += ref to resourceKey }

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
 * here is what the runner asked of the uploader: a test reads which units a wake reached, in the real order. Its
 * transfer session's background events are [WorldAppUpload]'s.
 */
class OperatorUploadEngine : AppUploadMechanism {
    /** How many top-ups (②) the tail asked for. */
    var topUps: Int = 0
        private set

    /** How many walks (③) — including a selection change's own work — the tail asked for. */
    var walks: Int = 0
        private set

    /** What each top-up answers — the operator's lever for a truncated or declining pass. */
    var topUpResult: CycleResult = CycleResult.COMPLETED

    /**
     * Operator lever: park the next unit until the gate completes — how a test holds a tail in flight to deliver an
     * expiry, or a join, while it runs. Consumed by the unit it parks.
     */
    @kotlin.concurrent.Volatile
    var nextUnitGate: CompletableDeferred<Unit>? = null

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

    private suspend fun park() {
        val gate = nextUnitGate ?: return
        nextUnitGate = null
        gate.await()
    }
}

/**
 * The app uploader's transfer session as the world's operating system plays it (`docs/testing.md`): an [Upload] that
 * holds no jobs — the world's app uploader is the inert [OperatorUploadEngine] — and whose one lever hands the session's
 * background events back to the composition that listened, as a `handleEventsForBackgroundURLSession` relaunch does.
 * Nothing is in flight in the world, so the session has nothing to deliver: its drain report follows at once.
 */
class WorldAppUpload : Upload {
    private var handlers: UploadHandlers? = null

    /** How many background-event handbacks reached this session. */
    var handbacks: Int = 0
        private set

    override val accepts: UploadSourceKind = UploadSourceKind.FILE

    override fun listen(handlers: UploadHandlers) {
        this.handlers = handlers
    }

    override suspend fun create(source: UploadSource, target: UploadTarget, tag: String): UploadCreateOutcome =
        UploadCreateOutcome.FAILED

    override suspend fun jobs(set: UploadJobSet): List<UploadJob> = emptyList()

    override suspend fun retry(job: UploadJob, target: UploadTarget): ChangeOutcome = ChangeOutcome.Applied

    override suspend fun acknowledge(job: UploadJob): ChangeOutcome = ChangeOutcome.Applied

    override suspend fun cancel(job: UploadJob): ChangeOutcome = ChangeOutcome.Applied

    /** Operator lever: the operating system relaunches the app for this session's events, handing [completion]. */
    fun handBack(completion: Completion) {
        val registered = checkNotNull(handlers) { "no composition listened to the upload session" }
        registered.onBackgroundEvents(completion)
        handbacks++
        registered.onEventsDrained()
    }
}

/**
 * The operating system's scheduled wakes as the world plays them (`docs/testing.md`, "Operator levers"): the honest
 * [inMemoryWake] underneath — the queue a request lands in, iOS-shaped, so only the heartbeat exists — plus the
 * operator's view: how many heartbeats were requested and cancelled, and the handlers the composition registered, so
 * the operator can deliver a wake. Nothing fires one on its own. Durable across a relaunch, as the system's queue is;
 * each relaunch's composition registers its own handlers.
 */
class WorldWake(
    private val pending: MutableStateFlow<Map<WakeId, WakeTrigger>> = MutableStateFlow(emptyMap()),
) : Wake {
    private val honest = inMemoryWake(pending, supported = setOf(WakeId.Heartbeat))
    private var handlers: WakeHandlers? = null

    /** How many heartbeat requests the operating system accepted. */
    var heartbeatsScheduled: Int = 0
        private set

    /** How many heartbeat cancels reached the operating system. */
    var heartbeatsCancelled: Int = 0
        private set

    /** The requests the operating system holds right now. */
    val pendingWakes: Map<WakeId, WakeTrigger> get() = pending.value

    override fun listen(handlers: WakeHandlers) {
        this.handlers = handlers
        honest.listen(handlers)
    }

    override fun schedule(id: WakeId, trigger: WakeTrigger): ScheduleResult =
        honest.schedule(id, trigger).also { answer ->
            if (id == WakeId.Heartbeat && answer == ScheduleResult.Scheduled) heartbeatsScheduled++
        }

    override fun cancel(id: WakeId) {
        if (id == WakeId.Heartbeat) heartbeatsCancelled++
        honest.cancel(id)
    }

    /** Operator lever: the operating system wakes the app for [id], handing it [completion]. */
    fun fire(id: WakeId, completion: Completion) {
        val registered = checkNotNull(handlers) { "no composition registered for wakes — nothing would receive this one" }
        registered.onWake(id, completion)
    }
}
