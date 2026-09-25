package app.snapsync.world

import app.snapsync.fake.inMemoryCandidateSource
import app.snapsync.model.CandidateRead
import app.snapsync.ports.CandidateSource
import app.snapsync.model.PermissionStatus
import app.snapsync.model.RawAsset
import app.snapsync.model.SelectionPolicy
import app.snapsync.ports.AssetRef
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.PendingDownload
import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.feature.upload.AppUploadEvents
import app.snapsync.feature.upload.AppUploadMechanism
import app.snapsync.feature.upload.WalkOutcome
import app.snapsync.ports.BackgroundScheduler
import app.snapsync.ports.CycleResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import app.snapsync.fake.inMemoryPhotoAccess
import app.snapsync.ports.PhotoAccessRequester
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The world's rigging around the honest `:adapter:generic:fake` [inMemoryPhotoAccess] status source: the
 * status is the honest fake's, over a cell this wrapper owns, and [set] is the operator's lever — the user
 * changing the grant in Settings, which no port member can do. Drives the status projection's `active` flag.
 */
class MutablePhotoAccessStatusSource(
    initial: PermissionStatus = PermissionStatus.GRANTED,
) : PhotoAccessStatusSource {
    private val cell = MutableStateFlow(initial)
    private val honest = inMemoryPhotoAccess(cell)

    override val permission: StateFlow<PermissionStatus> = honest.first.permission

    /** The requester over the same cell: what the user chooses when the app asks. */
    val requester: PhotoAccessRequester = honest.second

    fun set(value: PermissionStatus) {
        cell.value = value
    }
}

/**
 * The world's gallery: the operator rigging around the honest `:adapter:generic:fake` [inMemoryCandidateSource]
 * (`docs/architecture.md`, "The fake-honesty gate": the fake exposes only its port; the settable
 * state cell and the unscoped [current] read live HERE, in the world wrapper). [source] is what the
 * compositions consume; [set]/[current] are what the operator (and [FakePhotoLibraryImporter]) drive.
 */
class WorldGallery {
    private val state = MutableStateFlow<List<RawAsset>>(emptyList())

    /** The honest port impl over the world-owned cell — handed straight to the compositions. */
    private val honest: CandidateSource = inMemoryCandidateSource(state)

    /**
     * Operator lever: make the next enumeration THROW, as a platform walk can (capability
     * `sync-status`). It exists so a test can assert what a failed count does — the total stays
     * *not counted* rather than collapsing to a `0` that would read as "everything shared" — which is
     * otherwise unreachable without a device.
     */
    var failNextEnumeration: Boolean = false

    /**
     * The seam the compositions consume: the honest fake, plus the operator's failure lever.
     *
     * The lever THROWS rather than answering `NotReadable`, and the distinction is the point: a platform
     * walk that fails is a failure, caught by whoever owns the count, while `NotReadable` is a
     * successful read with no answer to give (capability `sync-status`). Modelling the failure as an
     * absence here would collapse the two states the world exists to keep apart.
     */
    val source: CandidateSource = object : CandidateSource {
        override suspend fun candidates(policy: SelectionPolicy): CandidateRead =
            if (failNextEnumeration) {
                failNextEnumeration = false
                error("the operator forced this enumeration to fail")
            } else {
                honest.candidates(policy)
            }
    }

    /** The current contents, unscoped — a rigging-only read (production has no unbounded walk). */
    fun current(): List<RawAsset> = state.value

    /** The writable cell itself, for the honest importer, which lands its assets here. */
    internal val cell: MutableStateFlow<List<RawAsset>> get() = state

    /** The same contents as a cell, for the honest doubles that read the library by identifier. */
    val contents: StateFlow<List<RawAsset>> = state.asStateFlow()

    fun set(rawAssets: List<RawAsset>) {
        state.value = rawAssets
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
