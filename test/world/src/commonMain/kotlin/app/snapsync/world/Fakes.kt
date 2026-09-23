package app.snapsync.world

import app.snapsync.fake.inMemoryCandidateSource
import app.snapsync.model.CandidateRead
import app.snapsync.ports.CandidateSource
import app.snapsync.model.PermissionStatus
import app.snapsync.model.RawAsset
import app.snapsync.model.SelectionPolicy
import app.snapsync.ports.AssetRef
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.feature.upload.AppUploadEngine
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
 * (spec `architecture-guards`, "The fake-honesty gate": the fake exposes only its port; the settable
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
     * successful read with no answer to give (capability `gallery-status`). Modelling the failure as an
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
 * `DownloadController` sent to the OS (it marks each enqueued resource through this port), replacing
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

    override suspend fun pruneNonTerminal(protecting: Set<AssetRef>): List<String> {
        enqueueRequests.clear()
        return inner.pruneNonTerminal(protecting)
    }
}

/**
 * The world's app-driven [AppUploadEngine]: inert, because **the operator is the engine** — nothing auto-runs in
 * the world (spec `full-stack-harness`), and a cycle happens only when the operator invokes it. The composed
 * `UploadTransitions` still drive the real arm/disarm decisions against this on join/leave/grant.
 */
class OperatorUploadEngine : AppUploadEngine {
    override suspend fun arm() {}
    override suspend fun disarm() {}
    override suspend fun cancelTransfers() {}

    /** How many background-task wakes reached this engine — the inbound-port contract reads it. */
    var backgroundTasks: Int = 0
        private set

    /** How many background-transfer handbacks reached this engine — the inbound-port contract reads it. */
    var transferHandbacks: Int = 0
        private set

    // The operator IS the trigger in the world harness: cycles happen when invoked by hand from the
    // inspector, never off an OS callback, so every trigger answer here is "nothing" — counted, so a test can
    // tell that an OS entry reached this engine rather than another.
    override suspend fun onForeground() {}
    override suspend fun onSilentPush(eventId: String) {}
    override suspend fun onBackgroundTask() { backgroundTasks++ }
    override suspend fun onSelectionChanged() {}

    // Nothing is in flight in the world, so there is nothing to absorb: the handler is released at once.
    override fun onBackgroundTransfers(completion: () -> Unit) {
        transferHandbacks++
        completion()
    }
}
