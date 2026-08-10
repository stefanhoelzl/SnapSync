package app.snapsync.world

import app.snapsync.fake.InMemoryCandidateSource
import app.snapsync.model.PermissionStatus
import app.snapsync.model.RawAsset
import app.snapsync.ports.AssetRef
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.feature.upload.UploadProducer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A settable in-memory [PhotoAccessStatusSource] — the world's stand-in for the iOS PhotoKit permission
 * adapter. A **rigged world wrapper**, not an `:adapter:generic:fake` resident: [set] is an operator lever, and
 * levers live here by law (spec `architecture-guards`, "The fake-honesty gate"). Drives the status
 * projection's `active` flag.
 */
class MutablePhotoAccessStatusSource(
    initial: PermissionStatus = PermissionStatus.GRANTED,
) : PhotoAccessStatusSource {
    private val _permission = MutableStateFlow(initial)
    override val permission: StateFlow<PermissionStatus> = _permission.asStateFlow()

    fun set(value: PermissionStatus) {
        _permission.value = value
    }
}

/**
 * The world's gallery: the operator rigging around the honest `:adapter:generic:fake` [InMemoryCandidateSource]
 * (spec `architecture-guards`, "The fake-honesty gate": the fake exposes only its port; the settable
 * state cell and the unscoped [current] read live HERE, in the world wrapper). [source] is what the
 * compositions consume; [set]/[current] are what the operator (and [FakePhotoLibraryImporter]) drive.
 */
class WorldGallery {
    private val state = MutableStateFlow<List<RawAsset>>(emptyList())

    /** The honest port impl over the world-owned cell — handed straight to the compositions. */
    val source: InMemoryCandidateSource = InMemoryCandidateSource(state)

    /** The current contents, unscoped — a rigging-only read (production has no unbounded walk). */
    fun current(): List<RawAsset> = state.value

    fun set(rawAssets: List<RawAsset>) {
        state.value = rawAssets
    }
}

/**
 * The world's download store: the recording wrapper around the honest `:adapter:generic:fake`
 * [app.snapsync.fake.InMemoryDownloadStore]. [enqueueRequests] records what the real
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
 * The world's [UploadProducer]: inert, because **the operator is the producer** — nothing auto-runs in
 * the world (spec `full-stack-harness`), and a cycle happens only when the operator invokes it. The
 * composed `UploadArm` still drives the real lifecycle verbs against this on join/leave/grant.
 */
class OperatorUploadProducer : UploadProducer {
    override suspend fun start() {}
    override suspend fun stop() {}
}
