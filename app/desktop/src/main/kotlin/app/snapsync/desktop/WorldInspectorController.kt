package app.snapsync.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.ConfigStore
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.ports.TransferOutcome
import app.snapsync.feature.download.StoreDownloadStatusSource
import app.snapsync.model.UploadError
import app.snapsync.feature.creation.CreationStatusSource
import app.snapsync.feature.creation.EventCreator
import app.snapsync.ports.EventDetails
import app.snapsync.join.HttpEventDirectory
import app.snapsync.feature.membership.JoinEvent
import app.snapsync.feature.membership.JoinOutcome
import app.snapsync.feature.membership.ManifestDeviceEnroller
import app.snapsync.ports.PhotoAccessRequester
import app.snapsync.model.PermissionStatus
import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.model.JoinLoad
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.model.DENYLISTED_ALBUM_TITLES
import app.snapsync.model.excludedAssetIds
import app.snapsync.feature.download.DownloadStatusSource
import app.snapsync.feature.status.SyncStatusSource
import app.snapsync.world.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The single mutation path for the full-stack world harness (test equipment — no tests, mirroring the
 * forge's `PanelController`): every inspector control goes through a named method here, never an inline
 * mutation in a composable. Each method drives `:test:world`'s public control surface / the
 * `process()`-shaped runner, then refreshes the real status + download sources so the LEFT pane's
 * counts **emerge** from the real projection (they are never forged), and recomputes the inspector
 * [snapshot].
 *
 * The world is a **live stateful stack** (backend byte store, ledger, gallery) that cannot be
 * "un-deposited", so presets construct a **fresh** [World] and bump [generation]; the composition root
 * keys the left pane on [generation] so it re-binds its `StatusContainerHost` to the new sources.
 * Incremental controls mutate the current world in place.
 */
class WorldInspectorController(private val scope: CoroutineScope) {

    // ---- current world + per-world derived sources (rebuilt on preset) --------------------------

    var world: World = World(scope)
        private set

    /** Bumped only when [world] is replaced (presets); the left pane is keyed on this. */
    var generation: Int by mutableStateOf(0)
        private set

    /** The inspector's render state, recomputed after every mutation. */
    var snapshot: InspectorSnapshot by mutableStateOf(InspectorSnapshot.EMPTY)
        private set

    // Per-world sources — read inside the composition root's `key(generation)` block, so a preset's
    // rebuild is picked up. Recreated by [rebuildSources].
    lateinit var syncSource: SyncStatusSource
        private set
    lateinit var downloadSource: StoreDownloadStatusSource
        private set
    lateinit var creator: EventCreator
        private set

    // The real join use-case over the current world (details load + enroll/provision), rebuilt per
    // world. Backs the join gate so create AND scan reach the JoiningEvent surface (with the direction
    // + cutoff rows), exactly like the iOS app — instead of the world's default create-provisions-directly.
    private lateinit var joinEvent: JoinEvent

    // The left pane's StatusContainerHost, captured via StatusPane's onHostReady. Create routes its
    // minted event into THIS host's pending-join gate (onEventCreated), so the join screen shows.
    var host: StatusContainerHost? = null

    // Stable across worlds — they read the *current* [world], so no rebuild is needed.
    val permissionSource: PhotoAccessStatusSource = object : PhotoAccessStatusSource {
        override val permission get() = world.permission.permission
    }
    val configSource: ConfigSource = object : ConfigSource {
        override val config get() = world.configSource.config
    }
    val creationStatusSource: CreationStatusSource = object : CreationStatusSource {
        override val creationStatus get() = world.creationStatus.creationStatus
    }
    val configStore: ConfigStore = object : ConfigStore {
        override suspend fun save(config: EventConfig) = world.provision(config.eventId, config.name)
        override suspend fun clear() = world.leave()
    }
    val leave: suspend () -> Unit = { world.leave(); afterMutation() }

    /** What the next gate-driven `request()` resolves to. */
    var armedGrants: Boolean by mutableStateOf(true)
        private set

    val requester: PhotoAccessRequester = object : PhotoAccessRequester {
        override fun request() = launchMutation {
            world.permission.set(if (armedGrants) PermissionStatus.GRANTED else PermissionStatus.DENIED)
        }
        override fun openSettings() = appendConsole("openSettings() — use the Permission segment instead")
    }

    // ---- engine console -------------------------------------------------------------------------

    private val _console = mutableStateOf<List<String>>(emptyList())
    val console: List<String> get() = _console.value

    /** Append a console line, capping the ring so an idle session never grows unbounded. */
    fun appendConsole(line: String) {
        _console.value = (_console.value + line).takeLast(CONSOLE_CAP)
    }

    fun clearConsole() {
        _console.value = emptyList()
    }

    // ---- counters (distinct ids per click; never collide across union/suppression) --------------

    private var ownAssetSeq = 0
    private var foreignDeviceSeq = 0
    private val injectedDeviceIds = mutableListOf<String>()

    init {
        rebuildSources()
        launchMutation { /* seed the initial snapshot + status refresh */ }
    }

    private fun rebuildSources() {
        syncSource = world.syncStatusSource(scope)
        downloadSource = StoreDownloadStatusSource(world.downloadStore)
        // The real join use-case over this world (mirrors the iOS SnapSyncRoot + the integration test):
        // GET /events/:id details, enroll via an empty manifest PUT, then provision the world config.
        joinEvent = JoinEvent(
            configSource = world.configSource,
            deviceId = { world.ownDeviceId },
            details = HttpEventDirectory(world.client, world.host),
            enroller = ManifestDeviceEnroller(world.manifestUploader),
            provision = { cfg ->
                world.provision(
                    eventId = cfg.eventId,
                    name = cfg.name,
                    minPhotoDate = cfg.minPhotoDate,
                    startsAt = cfg.startsAt,
                    direction = cfg.direction,
                    saveToAlbum = cfg.saveToAlbum,
                )
                afterMutation()
            },
        )
        // Route a minted event into the SAME pending-join gate a scan opens (not the world's default
        // provide-directly), so create shows the JoiningEvent surface with the direction + cutoff rows.
        creator = world.createEvent(scope, onMinted = { eventId -> host?.onEventCreated(eventId); Unit })
    }

    /** Details load for the join gate (GET /events/:id over the mini-edge), mapped to the gate's [JoinLoad]. */
    suspend fun loadJoinDetails(eventId: String): JoinLoad = when (val d = joinEvent.loadDetails(eventId)) {
        is EventDetails.Found -> JoinLoad.Found(d.name, d.startsAt)
        EventDetails.NotFound -> JoinLoad.NotFound
        EventDetails.Failed -> JoinLoad.Failed
    }

    /** Confirm the join: enroll (empty-manifest PUT) then provision; `true` unless enrollment failed. */
    suspend fun commitJoin(
        eventId: String,
        name: String,
        startsAt: String,
        cutoff: String,
        direction: Direction,
        saveToAlbum: Boolean,
    ): Boolean =
        joinEvent.join(eventId, name, startsAt, cutoff, direction, saveToAlbum) != JoinOutcome.EnrollFailed

    // ---- the OS invocation + token ---------------------------------------------------------------

    /** One extension invocation: the upload `process()` cycle **and** a download reconcile. */
    fun invokeExtension() = launchMutation {
        val result = world.runUploadCycle()
        appendConsole("invoke: upload cycle → $result")
        world.configSource.config.value?.eventId?.let { world.downloadController.reconcile(it) }
    }

    fun expireToken() = launchMutation {
        world.platform.expireToken()
        appendConsole("change token expired → next invoke does a full enumeration")
    }

    // ---- enrollment ------------------------------------------------------------------------------

    fun setPermission(status: PermissionStatus) = launchMutation { world.permission.set(status) }

    fun armNextRequest(grants: Boolean) {
        armedGrants = grants
    }

    fun reprovision() = launchMutation {
        val eventId = world.configSource.config.value?.eventId ?: return@launchMutation
        world.provision(eventId)
        appendConsole("re-provisioned $eventId (reconcile seeds stored assets COMPLETED)")
    }

    /**
     * Create an event through the REAL `HttpEventCreation` → mini-edge → marker, with a chosen
     * [startsAt]. The operator picks past or future so BOTH sides of the floor are drivable through the
     * real stack — a future start is what proves the theorem the design rests on: nothing uploads, not
     * because a gate refuses, but because the clamped cutoff admits no photo. The forge harness can only
     * show the status line; only this world can show the empty object store behind it.
     */
    fun createEvent(name: String, startsAt: String) = launchMutation { creator.create(name, startsAt) }

    /** The inspector's Leave button — the same faithful edge as the phone-frame Leave affordance. */
    fun leaveEvent() = launchMutation { world.leave() }

    // ---- gallery ---------------------------------------------------------------------------------

    fun addAsset() = launchMutation { world.addOwnAsset("own-${ownAssetSeq++}") }

    fun removeAsset(assetId: String) = launchMutation { world.removeAsset(assetId) }

    // ---- selection policy (capability `photo-selection-policy`) -----------------------------------
    // Each button adds an asset the policy EXCLUDES, so the operator can watch it land in the gallery and
    // then *not* upload and *not* enter the union — and can see that N does not inflate, which is the part
    // a unit test cannot show at a glance.

    /** A screenshot — excluded by media subtype. */
    fun addScreenshot() = launchMutation { world.addScreenshot("shot-${ownAssetSeq++}") }

    /** A screen recording — excluded by media subtype. */
    fun addScreenRecording() = launchMutation { world.addScreenRecording("rec-${ownAssetSeq++}") }

    /** A messenger-compressed image (1600×1200 ≈ 1.9 MP) — below the 3 MP image floor. */
    fun addLowResPhoto() = launchMutation { world.addLowResPhoto("lowres-${ownAssetSeq++}") }

    /** A GIF — excluded by MIME. */
    fun addGif() = launchMutation { world.addGif("gif-${ownAssetSeq++}") }

    /**
     * A 1080p recording. This one must **upload**: 2.07 MP is below the *image* floor but above the *video*
     * floor. It is here precisely so a regression that collapses the two floors is visible as a video that
     * silently stops appearing.
     */
    fun addHdVideo() = launchMutation { world.addHdVideo("video-${ownAssetSeq++}") }

    /** An ordinary photo that WhatsApp also saved into its album — excluded by the album denylist. */
    fun addWhatsAppAlbumPhoto() = launchMutation {
        val id = "wa-${ownAssetSeq++}"
        world.addOwnAsset(id)
        world.placeInAlbum("WhatsApp", id)
    }

    // ---- backend ---------------------------------------------------------------------------------

    /** Inject one foreign device carrying a single complete asset into the joined event. */
    fun injectForeignDevice() = launchMutation {
        val eventId = world.configSource.config.value?.eventId ?: return@launchMutation
        val deviceId = "foreign-${foreignDeviceSeq++}"
        world.addForeignDevice(deviceId, eventId, listOf(World.foreignAsset("$deviceId-a1")))
        injectedDeviceIds += deviceId
        appendConsole("injected $deviceId with one complete asset into $eventId")
    }

    // ---- upload jobs -----------------------------------------------------------------------------

    fun completeJob(key: String) = launchMutation { world.platform.completeJob(key) }

    fun failJob(key: String, error: UploadError) = launchMutation { world.platform.failJob(key, error) }

    fun setJobLimit(limit: Int) = launchMutation { world.jobLimit = limit.coerceAtLeast(0) }

    // ---- downloads -------------------------------------------------------------------------------

    fun stageAllDownloads() = launchMutation { world.stageAllDownloads() }

    /**
     * Failure lever: the operator plays a bad network. Every in-flight transfer finishes with a `502` and
     * an error body — which `URLSession` reports as a *successful* transfer, so this is what the shipped
     * bug looked like (capability `photo-download`). The bytes are rejected, nothing stages, and the
     * downloads stay pending: staging them would have made the error body the store's truth forever.
     */
    fun stageAllDownloadsAs502() = launchMutation {
        world.stageAllDownloads(TransferOutcome(statusCode = 502, expectedBytes = -1L, receivedBytes = 137L))
    }

    /** Failure lever: every in-flight transfer finishes truncated — a body short of its `Content-Length`. */
    fun stageAllDownloadsShortRead() = launchMutation {
        world.stageAllDownloads(TransferOutcome(statusCode = 200, expectedBytes = 5_000L, receivedBytes = 1_200L))
    }

    // ---- failure levers --------------------------------------------------------------------------

    fun setBackendOffline(offline: Boolean) = launchMutation { world.backendOffline = offline }

    /**
     * Force the membership to read as **unreadable** (capability `upload-lifecycle`) — the state a real
     * device is in before its first unlock after a boot.
     *
     * It is a lever here because it is otherwise unreachable by a reviewer: the config cell can express
     * only *joined* and *absent*, and the one dev device available reports `PasswordProtected: false`, so
     * it has no data protection and cannot enter the state at all. Without this switch the outcome three
     * shipped bugs turned on is the one nobody can look at.
     */
    fun setMembershipUnreadable(unreadable: Boolean) = launchMutation { world.membershipUnreadable = unreadable }

    fun armImportFailure() = launchMutation {
        world.failNextImport()
        appendConsole("armed: next foreign import will fail (non-terminal)")
    }

    // ---- presets (rebuild a fresh world) ---------------------------------------------------------

    fun presetClean() = installFreshWorld("clean") { }

    fun presetEnrolled() = installFreshWorld("enrolled") {
        provision(EVENT)
        addOwnAsset("own-a1")
        addOwnAsset("own-a2")
    }

    fun presetFreshJoin() = installFreshWorld("fresh join") {
        provision(EVENT)
        addOwnAsset("own-a1")
    }

    fun presetReprovisionDedup() = installFreshWorld("re-provision (dedup)") {
        // Own asset already stored (as if previously uploaded), then provision: the reconcile seeds it
        // COMPLETED, so a subsequent invoke uploads nothing new. Deposit exactly the enumerator-derived
        // keys (uploadKey) so the completeness check matches — don't reconstruct the key by hand.
        addOwnAsset("own-a1")
        enumerator.enumerate(World.DEFAULT_CUTOFF).forEach { store.deposit(ownDeviceId, it.filename) }
        provision(EVENT)
    }

    fun presetForeignDownload() = installFreshWorld("foreign download") {
        provision(EVENT)
        val deviceId = "foreign-0"
        addForeignDevice(deviceId, EVENT, listOf(World.foreignAsset("$deviceId-a1")))
    }

    private fun installFreshWorld(label: String, setup: suspend World.() -> Unit) {
        scope.launch {
            world = World(scope)
            rebuildSources()
            ownAssetSeq = 0
            foreignDeviceSeq = 0
            injectedDeviceIds.clear()
            world.setup()
            appendConsole("preset: $label")
            refreshStatus()
            snapshot = snapshotNow()
            generation++ // re-bind the left pane to the new world's sources
        }
    }

    // ---- shared plumbing -------------------------------------------------------------------------

    /** Run a mutation, then refresh the real sources and recompute the snapshot (no world rebuild). */
    private fun launchMutation(body: suspend () -> Unit) {
        scope.launch {
            body()
            afterMutation()
        }
    }

    private suspend fun afterMutation() {
        refreshStatus()
        snapshot = snapshotNow()
    }

    /**
     * The operator plays the OS foreground-refresh (and the extension liveness ding): the real
     * gallery/ledger-count/download sources update their `StateFlow`s only on `refresh()`, so the
     * `LedgerBackedSyncStatusSource` projection re-emits only after we pull them.
     */
    private suspend fun refreshStatus() {
        // What the membership contributes scopes the total — its direction AND its cutoff (capability
        // `photo-selection-policy`). Derived by the world exactly as the composition roots derive it, so a
        // download-only join shows the operator N=0, not a total that can never settle.
        world.ownGallery.refresh(world.contribution())
        world.ledgerCounts.refresh()
        downloadSource.refresh()
    }

    private suspend fun snapshotNow(): InspectorSnapshot {
        val suppressed = world.downloadStore.suppressedLocalIds()
        // What the selection policy would exclude (capability `photo-selection-policy`) — computed with the
        // REAL policy over the REAL enumeration, so the row badge cannot drift from what the cycle does.
        // Without this the levers are mute: an operator would add a screenshot, watch it sit in the gallery,
        // and have no way to tell "correctly excluded" from "silently broken".
        val cutoff = world.configSource.config.value?.minPhotoDate ?: World.DEFAULT_CUTOFF
        val policyExcluded = excludedAssetIds(world.enumerator.enumerate(cutoff)) +
            world.albumManager.assetIdsInAlbums(DENYLISTED_ALBUM_TITLES, cutoff)
        val galleryRows = world.gallery.current()
            .map {
                GalleryRow(
                    it.assetId,
                    suppressed = it.assetId in suppressed,
                    policyExcluded = it.assetId in policyExcluded,
                )
            }
        val deviceIds = listOf(world.ownDeviceId) + injectedDeviceIds
        val backend = deviceIds.map { id ->
            DeviceObjects(deviceId = id, own = id == world.ownDeviceId, objects = world.store.objectsOf(id).toList())
        }
        val jobKeys = world.platform.liveJobKeys()
        val jobs = jobKeys.map { key -> JobRow(key, attempts = world.platform.created.count { it.filename == key }) }
        // The real jobs expose no inspection seam and their description codec is internal to
        // `:domain`'s feature/download, so the world records what the controller requested (see downloadRequests).
        val downloads = world.downloadRequests.distinctBy { it.ref to it.resource.resourceKey }
            .map { DownloadRow(it.ref.sourceDeviceId, it.ref.sourceAssetId, it.resource.resourceKey) }
        return InspectorSnapshot(
            joinedEventId = world.configSource.config.value?.eventId,
            galleryRows = galleryRows,
            backend = backend,
            jobs = jobs,
            downloads = downloads,
            jobLimit = world.jobLimit,
            backendOffline = world.backendOffline,
            membershipUnreadable = world.membershipUnreadable,
        )
    }

    private companion object {
        const val EVENT = "00000000-0000-4000-8000-0000000000e1"
        const val CONSOLE_CAP = 200
    }
}

/** The inspector's render snapshot (recomputed after each mutation). */
data class InspectorSnapshot(
    val joinedEventId: String?,
    val galleryRows: List<GalleryRow>,
    val backend: List<DeviceObjects>,
    val jobs: List<JobRow>,
    val downloads: List<DownloadRow>,
    val jobLimit: Int,
    val backendOffline: Boolean,
    val membershipUnreadable: Boolean,
) {
    companion object {
        val EMPTY =
            InspectorSnapshot(null, emptyList(), emptyList(), emptyList(), emptyList(), Int.MAX_VALUE, false, false)
    }
}

data class GalleryRow(val assetId: String, val suppressed: Boolean, val policyExcluded: Boolean = false)
data class DeviceObjects(val deviceId: String, val own: Boolean, val objects: List<String>)
data class JobRow(val key: String, val attempts: Int)
data class DownloadRow(val deviceId: String, val assetId: String, val resourceKey: String)
