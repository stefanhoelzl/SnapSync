package app.snapsync.world

import app.snapsync.album.AlbumCoordinator
import app.snapsync.config.ConfigSource
import app.snapsync.config.ConfigStore
import app.snapsync.config.Direction
import app.snapsync.config.EventConfig
import app.snapsync.download.DownloadController
import app.snapsync.download.EventUnionSource
import app.snapsync.download.HttpEventUnionSource
import app.snapsync.downloadstore.InMemoryDownloadStore
import app.snapsync.engine.LedgerWriter
import app.snapsync.engine.SyncEngine
import app.snapsync.eventcreation.CreateEvent
import app.snapsync.eventcreation.HttpEventCreationClient
import app.snapsync.eventcreation.MutableCreationStatusSource
import app.snapsync.gallery.Contribution
import app.snapsync.gallery.DeviceManifestAsset
import app.snapsync.gallery.denormalizeAssetId
import app.snapsync.gallery.DeviceManifestProducer
import app.snapsync.gallery.GalleryResourceEnumerator
import app.snapsync.album.DENYLISTED_ALBUM_TITLES
import app.snapsync.gallery.InMemoryRawAssetSource
import app.snapsync.gallery.MEDIA_TYPE_IMAGE
import app.snapsync.gallery.MEDIA_TYPE_VIDEO
import app.snapsync.gallery.MIME_GIF
import app.snapsync.gallery.SUBTYPE_NONE
import app.snapsync.gallery.SUBTYPE_SCREENSHOT
import app.snapsync.gallery.SUBTYPE_SCREEN_RECORDING
import app.snapsync.gallery.ManifestResource
import app.snapsync.gallery.RawAsset
import app.snapsync.gallery.RawResource
import app.snapsync.gallery.ResourceEnumerator
import app.snapsync.gallery.ResourceRole
import app.snapsync.gallery.deviceManifestAssetsFromResources
import app.snapsync.gallery.uploadKey
import app.snapsync.membership.DeviceFilesSource
import app.snapsync.membership.ExtensionReconciler
import app.snapsync.membership.HttpDeviceFilesSource
import app.snapsync.membership.HttpLeaveNotifier
import app.snapsync.status.LedgerBackedSyncStatusSource
import app.snapsync.status.LedgerCounts
import app.snapsync.status.OwnDeviceGalleryStatusSource
import app.snapsync.status.ReadingLedgerCountsSource
import app.snapsync.status.SyncStatusSource
import app.snapsync.upload.CycleResult
import app.snapsync.upload.UploadCycle
import app.snapsync.upload.CycleGate
import app.snapsync.upload.JoinedMembership
import app.snapsync.upload.cycleGate
import app.snapsync.uploadurl.EdgeUploadRequestProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The controllable in-memory **world** (capability `harness-world-model`): the backend object store,
 * the gallery, the ledger, and all the operator-driven fakes, plus composition helpers that assemble
 * the REAL stack against them — mirroring the extension composition root `UploadExtensionRoot.process()`
 * one seam-substitution at a time. Consumed by BOTH the desktop full-stack harness and
 * `:test:integration`.
 *
 * It models exactly **one** fixed own device ([ownDeviceId]) — the id the upload cycle, provider,
 * reconciler, and own-device status all use — and any number of **injectable** foreign devices
 * ([addForeignDevice]) whose complete assets appear in the event-wide union for download/echo.
 */
class World(
    val ownDeviceId: String = "00000000-0000-4000-9000-0000000000a1",
    val host: String = "https://world.edge",
) {

    // ---- world state + fakes (all public / inspectable) -----------------------------------------

    val store: BackendStore = BackendStore()
    val gallery: InMemoryRawAssetSource = InMemoryRawAssetSource()
    val enumerator: GalleryResourceEnumerator = ResourceEnumerator(gallery)
    val ledgerBackend: WorldLedgerBackend = WorldLedgerBackend()
    val ledger: LedgerWriter = LedgerWriter(ledgerBackend)
    val discoveryStore: InMemoryDiscoveryStore = InMemoryDiscoveryStore()
    val downloadStore: InMemoryDownloadStore = InMemoryDownloadStore()
    val platform: FakeUploadJobPlatform = FakeUploadJobPlatform(store, ownDeviceId, enumerator)
    val downloadJobs: FakePhotoDownloadJobs = FakePhotoDownloadJobs()
    val importer: FakePhotoLibraryImporter = FakePhotoLibraryImporter(gallery)
    val marker: InMemoryJoinedEventMarker = InMemoryJoinedEventMarker()
    val manifestStore: InMemoryDeviceManifestStore = InMemoryDeviceManifestStore()
    val permission: MutablePermissionStatusSource = MutablePermissionStatusSource()
    val creationStatus: MutableCreationStatusSource = MutableCreationStatusSource()

    // Event album (capability `event-album`): the recording manager + leave-surviving map + coordinator,
    // so integration tests can assert which asset ids the real upload cycle placed into the album.
    val albumManager: FakeAlbumManager = FakeAlbumManager()
    val albumMapStore: InMemoryAlbumMapStore = InMemoryAlbumMapStore()
    val albumCoordinator: AlbumCoordinator = AlbumCoordinator(albumManager, albumMapStore)

    /** The one shared mini-edge client injected into every real common-Ktor seam. */
    val client = miniEdgeClient(store)
    val manifestUploader: HttpDeviceManifestUploader = HttpDeviceManifestUploader(client, host)

    private val configCell = MutableStateFlow<EventConfig?>(null)
    val configSource: ConfigSource = object : ConfigSource {
        override val config: StateFlow<EventConfig?> = configCell.asStateFlow()
    }

    // The write side of the same cell [configSource] reads — lets a real `LeaveEvent` (or provision)
    // clear/set the config the container reduces from, so an integration test can drive the actual
    // use-case instead of the world's hand-rolled [leave].
    val configStore: ConfigStore = object : ConfigStore {
        override suspend fun save(config: EventConfig) { configCell.value = config }
        override suspend fun clear() { configCell.value = null }
    }

    // The real common-Ktor seams over the mini-edge (single client, exactly as production shares one).
    val deviceFiles: DeviceFilesSource = HttpDeviceFilesSource(client, host)
    val unionSource: EventUnionSource = HttpEventUnionSource(client, host)

    // Own-device upload total N (enumeration + download suppression; no storage LIST for status).
    // Named `ownGallery` to avoid clashing with the raw-asset `gallery` above.
    val ownGallery: OwnDeviceGalleryStatusSource =
        OwnDeviceGalleryStatusSource(
            enumerator = enumerator,
            suppressedLocalIds = { downloadStore.suppressedLocalIds() },
            // The SAME policy the cycle gets — the whole point of the requirement (capability
            // `photo-selection-policy`): if the total counted what the cycle refuses to upload, the
            // harness's status pane would sit below 100% forever, which is what this must prove it doesn't.
            albumExcludedAssetIds = { cutoff -> albumManager.assetIdsInAlbums(DENYLISTED_ALBUM_TITLES, cutoff) },
        )
    // Completed + pending both read from the world's real ledger (one consistent aggregates() read).
    val ledgerCounts: ReadingLedgerCountsSource = ReadingLedgerCountsSource {
        ledgerBackend.aggregates().let { LedgerCounts(completed = it.completed, pending = it.pending) }
    }

    // Single-instance real download controller (its Mutex must be shared across reconcile + staging).
    val downloadController: DownloadController =
        DownloadController(
            unionSource, downloadStore, downloadJobs, importer, myDeviceId = ownDeviceId,
            // Mirror SnapSyncRoot: the download arm runs only when the joined direction includes download
            // (capability `join-event`), so a reconcile on an upload-only membership is a no-op.
            downloadEnabled = { configCell.value?.direction?.includesDownload },
        )

    // ---- failure levers -------------------------------------------------------------------------

    /** Backend-offline: the per-device listing and event-union routes fail (mini-edge `502`). */
    var backendOffline: Boolean
        get() = store.offline
        set(value) {
            store.offline = value
        }

    /** The OS in-flight job cap (`createJob` returns `LIMIT_EXCEEDED` at/above it). */
    var jobLimit: Int
        get() = platform.jobLimit
        set(value) {
            platform.jobLimit = value
        }

    /** Arm the next foreign import to fail (`ImportResult.Failed`, non-terminal). */
    fun failNextImport() {
        importer.failNextImport = true
    }

    // ---- device model + operator gallery actions ------------------------------------------------

    /**
     * Add one of the OWN device's photos to the gallery (default: a single primary JPEG).
     *
     * The origin facts default to an ordinary 12 MP camera photo, so an asset added without them is
     * **admitted** by the selection policy (capability `photo-selection-policy`) — see [addScreenshot] and
     * friends to forge one that is not.
     */
    suspend fun addOwnAsset(
        assetId: String,
        creationDate: String = DEFAULT_DATE,
        resources: List<RawResource> = listOf(primaryResource()),
        mediaSubtypes: Long = SUBTYPE_NONE,
        mediaType: Long = MEDIA_TYPE_IMAGE,
        pixelWidth: Long = 4032,
        pixelHeight: Long = 3024,
        hasAdjustments: Boolean = false,
    ) {
        gallery.set(
            gallery.current() + RawAsset(
                assetId = assetId,
                creationDate = creationDate,
                rawResources = resources,
                mediaSubtypes = mediaSubtypes,
                mediaType = mediaType,
                pixelWidth = pixelWidth,
                pixelHeight = pixelHeight,
                hasAdjustments = hasAdjustments,
            ),
        )
    }

    // ---- selection-policy levers (capability `photo-selection-policy`) ---------------------------
    // Each forges one category the policy excludes, so every rule is exercisable in the harness and the
    // integration tests without PhotoKit — and so an operator can *see* that a screenshot never uploads.

    /** A screenshot. Excluded by media subtype — the sharpest and highest-frequency case. */
    suspend fun addScreenshot(assetId: String, creationDate: String = DEFAULT_DATE) =
        addOwnAsset(assetId, creationDate, mediaSubtypes = SUBTYPE_SCREENSHOT, pixelWidth = 750, pixelHeight = 1334)

    /** A screen recording. Excluded by media subtype. */
    suspend fun addScreenRecording(assetId: String, creationDate: String = DEFAULT_DATE) =
        addOwnAsset(
            assetId, creationDate,
            mediaSubtypes = SUBTYPE_SCREEN_RECORDING, mediaType = MEDIA_TYPE_VIDEO,
            pixelWidth = 886, pixelHeight = 1920,
        )

    /** A compressed image as a messenger would have saved it (1600×1200 ≈ 1.9 MP). Below the image floor. */
    suspend fun addLowResPhoto(assetId: String, creationDate: String = DEFAULT_DATE) =
        addOwnAsset(assetId, creationDate, pixelWidth = 1600, pixelHeight = 1200)

    /** A 1080p recording — BELOW the image floor but ABOVE the video floor, so it must be **admitted**. */
    suspend fun addHdVideo(assetId: String, creationDate: String = DEFAULT_DATE) =
        addOwnAsset(
            assetId, creationDate,
            mediaType = MEDIA_TYPE_VIDEO, pixelWidth = 1920, pixelHeight = 1080,
        )

    /** A GIF. Excluded by MIME — never a camera capture, not even one exported from a Live Photo. */
    suspend fun addGif(assetId: String, creationDate: String = DEFAULT_DATE) =
        addOwnAsset(
            assetId, creationDate,
            resources = listOf(primaryResource(filename = "giphy.gif", contentType = MIME_GIF)),
        )

    /** Put an existing own asset into an album some app made — e.g. `placeInAlbum("WhatsApp", "A1")`. */
    fun placeInAlbum(albumTitle: String, assetId: String) {
        albumManager.placeIn(albumTitle, assetId)
    }

    /** Remove an own asset from the gallery (surfaces as `removedAssetIds` on the next incremental cycle). */
    suspend fun removeAsset(assetId: String) {
        gallery.set(gallery.current().filterNot { it.assetId == assetId })
    }

    /**
     * Inject a foreign device with already-stored complete assets: deposit each resource's bytes into
     * that device's partition and register its manifest under [eventId], so the event-union returns
     * these assets (tagged with [deviceId]) for download/echo. Registers the event marker too.
     */
    fun addForeignDevice(deviceId: String, eventId: String, assets: List<DeviceManifestAsset>) {
        store.registerEvent(eventId)
        assets.forEach { asset -> asset.resources.forEach { store.deposit(deviceId, it.key) } }
        store.putManifest(eventId, deviceId, foreignManifest(deviceId, assets))
    }

    /**
     * Join/provision an event: register its marker and make its config present (the config gate lifts).
     * [minPhotoDate] is this device's per-membership capture-date cutoff (capability `photo-selection-policy`),
     * always present. It defaults to [DEFAULT_CUTOFF], which precedes [DEFAULT_DATE] so an asset added with
     * default arguments is in scope.
     *
     * [startsAt] is the EVENT's start date — the floor under [minPhotoDate]. It defaults to
     * [DEFAULT_STARTS_AT], which is at or before [DEFAULT_CUTOFF], so a default provision is a started
     * event whose floor binds nothing. Pass a FUTURE value to model an event that has not begun: the real
     * stack then admits no photo at all, and the status line reads not-started.
     */
    fun provision(
        eventId: String,
        name: String? = null,
        minPhotoDate: String = DEFAULT_CUTOFF,
        startsAt: String = DEFAULT_STARTS_AT,
        direction: Direction = Direction.Both,
        saveToAlbum: Boolean = false,
    ) {
        store.registerEvent(eventId, name, startsAt)
        configCell.value = EventConfig(
            eventId = eventId,
            name = name ?: "",
            minPhotoDate = minPhotoDate,
            startsAt = startsAt,
            direction = direction,
            saveToAlbum = saveToAlbum,
        )
    }

    /**
     * Leave the joined event — the **faithful** in-place clear (NOT a world rebuild): run the real
     * [DownloadController.onLeaveOrSwitch] (cancel transfers, prune non-terminal download rows), then
     * the real backend leave ([HttpLeaveNotifier] over the mini-edge — the same `DELETE` the app fires,
     * driving the store's rename→reap→GC cascade), then clear the config cell and the joined-event
     * marker. The gallery, ledger, and **imported foreign photos** are retained (imported download rows
     * are terminal / delete-proof), so re-provisioning the same event afterwards still finds them
     * suppressed (real cross-event dedup). Clearing [configCell] is reactive, so the listing-backed
     * status projection leaves the joined layer with no rebuild. Backend outcomes (the device departed;
     * the event reaped + its bytes GC'd when it was the last active member) are assertable on [store].
     */
    suspend fun leave() {
        downloadController.onLeaveOrSwitch()
        configCell.value?.eventId?.let { HttpLeaveNotifier(client, host).leave(it, ownDeviceId) }
        configCell.value = null
        marker.clear()
    }

    // ---- composition (the REAL cycle, not a mirror of a root) -----------------------------------
    //
    // These build the same object graph a composition root builds and hand it to the same
    // `UploadCycle` — they do not re-implement what a root does. The distinction is load-bearing: this
    // file used to mirror `UploadExtensionRoot.process()` by hand, and before the app-driven tier's
    // reconciler was fixed the MIRROR reconciled while the real tier did not. A mirror that is more
    // correct than production is worse than one that is wrong — it stays green while the defect ships.

    /**
     * Force the membership to read as **unreadable** (capability `upload-lifecycle`) — the state a real
     * device is in before its first unlock after a boot, where the Keychain cannot be read at all.
     *
     * It is a lever rather than a property of [configCell] because a nullable cell can express only
     * *joined* or *absent*, which is exactly the modelling gap that mattered: the outcome three shipped
     * bugs turned on was the one no test could reach. Set it and a cycle takes [CycleGate.Skip].
     */
    var membershipUnreadable: Boolean = false

    /**
     * The world's membership read, translated into the shared vocabulary — the world's equivalent of a
     * root's `readGate()`, and like a root's, a translation rather than a decision: `cycleGate` decides.
     */
    fun readGate(): CycleGate = cycleGate(
        configReadable = !membershipUnreadable,
        membership = configCell.value?.let {
            JoinedMembership(
                eventId = it.eventId,
                contribution = Contribution.of(it.direction.includesUpload, it.minPhotoDate),
                saveToAlbum = it.saveToAlbum,
            )
        },
        host = host,
        skipDetail = "world: membership forced unreadable",
    )

    fun reconciler(): ExtensionReconciler =
        ExtensionReconciler(
            files = deviceFiles,
            ledger = ledgerBackend,
            marker = marker,
            deviceId = ownDeviceId,
            clearDiscoveryCursor = { discoveryStore.clearToken() },
        )

    fun manifestProducer(): DeviceManifestProducer =
        DeviceManifestProducer(manifestStore, manifestUploader, ownDeviceId)

    /**
     * What the joined membership contributes (capability `photo-selection-policy`) — its participation
     * direction AND its cutoff, derived from the config cell through the **same** `Contribution.of` the
     * composition roots use. Both consumers of the policy take this: the upload cycle (which declines with
     * `SKIPPED` for `None`) and the own-device total `N` (which reports 0 without walking).
     *
     * The world therefore runs the real direction gate rather than modelling one — the point of driving the
     * real stack here. A download-only membership uploads nothing and counts nothing *because the production
     * code says so*, not because the harness arranged it.
     *
     * An **unjoined** world contributes [Contribution.None], not a default cutoff. There is no membership,
     * so there is nothing to contribute and `N` is 0 — the same answer the cycle reaches. This used to
     * invent `DEFAULT_CUTOFF` (and `includesUpload = true`) so the harness "stayed drivable", which made the
     * world the one place in the system where a cutoff appears without a membership behind it — against the
     * invariant the whole selection policy rests on.
     */
    fun contribution(): Contribution = configCell.value?.let {
        Contribution.of(includesUpload = it.direction.includesUpload, cutoff = it.minPhotoDate)
    } ?: Contribution.None

    /**
     * Every event this world's cycles notified (capability `upload-completion-notify`), in order.
     *
     * The mini-edge has no notify route, so this records the call rather than serving it. It exists
     * because the world used to omit `onBatchUploaded` entirely — silently, via the port's old default —
     * which is why the notify had no integration coverage at all. Recording it is the minimum that makes
     * "a drained cycle with completions notifies exactly once, after the manifest PUT" observable here.
     */
    val notified: MutableList<String> = mutableListOf()

    /**
     * The real cycle — long-lived, as on both tiers: it re-reads the membership itself through [readGate]
     * on every `run()`, so a provision, leave, or switch takes effect on the next cycle.
     */
    val cycle: UploadCycle by lazy {
        UploadCycle(
            readGate = ::readGate,
            engineFor = { config -> SyncEngine(EdgeUploadRequestProvider(config.host, ownDeviceId), ledger) },
            ledger = ledger,
            platform = platform,
            store = discoveryStore,
            reconcile = { eventId -> reconciler().reconcile(eventId) },
            onDiscovery = { eventId, cutoff, discovery ->
                manifestProducer().produce(
                    eventId = eventId,
                    startDate = cutoff,
                    discovered = deviceManifestAssetsFromResources(discovery.resources),
                    removedAssetIds = discovery.removedAssetIds.toSet(),
                    fullEnumeration = discovery.fullEnumeration,
                )
            },
            suppressedAssetIds = { downloadStore.suppressedLocalIds() },
            // Denylisted-album membership (capability `photo-selection-policy`) — the REAL policy constant
            // over the world's forgeable album membership, exactly as both composition roots wire it. The
            // world runs the real rules; only the PhotoKit lookup is faked.
            albumExcludedAssetIds = { cutoff -> albumManager.assetIdsInAlbums(DENYLISTED_ALBUM_TITLES, cutoff) },
            onBatchUploaded = { eventId -> notified += eventId },
            // Event album (capability `event-album`): the cycle applies the membership's opt-in, which it
            // read at its gate; raw localId recovered by reversing `_`→`/` (as the real roots do).
            placeInAlbum = { eventId, assetIds ->
                albumCoordinator.place(eventId, assetIds.map(::denormalizeAssetId))
            },
        )
    }

    /**
     * Run one cycle. The membership read, the gate, the leave-side reconcile, and the assembly are all
     * inside the real [cycle] now — what is left here is the extension tier's own "pending > 0 ⇒
     * PROCESSING" re-invocation request, which [requeuePending] models. That rule is genuinely
     * tier-specific (the app-driven tier has completion callbacks and needs no such poll), so it is the
     * one thing this runner may hold.
     */
    suspend fun runUploadCycle(requeuePending: Boolean = false): CycleResult {
        val result = runCatching { cycle.run() }.getOrElse { CycleResult.FAILED }
        if (requeuePending && result == CycleResult.COMPLETED && ledgerBackend.aggregates().pending > 0) {
            return CycleResult.PROCESSING
        }
        return result
    }

    /** The real ledger-backed status source over the world (own-device ledger truth + gallery total). */
    fun syncStatusSource(scope: CoroutineScope): SyncStatusSource =
        LedgerBackedSyncStatusSource(
            ledgerCounts = ledgerCounts,
            permission = permission,
            gallery = ownGallery,
            scope = scope,
        )

    /**
     * The real create-event use-case over the mini-edge (`POST /events` → [onMinted]). By default the
     * harness provisions the minted event directly (whole-library); a caller wanting the production
     * flow (route into the join gate to pick a cutoff) passes an [onMinted] that opens the pending join.
     */
    fun createEvent(
        scope: CoroutineScope,
        onMinted: suspend (eventId: String) -> Unit = { provision(it) },
    ): CreateEvent =
        CreateEvent(
            client = HttpEventCreationClient(client, host),
            status = creationStatus,
            onMinted = onMinted,
            scope = scope,
        )

    // ---- download operator action ---------------------------------------------------------------

    /** Stage every currently-pending download (resolving its synthetic url store-direct). */
    suspend fun stageAllDownloads() {
        downloadJobs.pending().forEach { pd ->
            downloadController.onResourceStaged(pd.ref, pd.resource.resourceKey, "staged://${pd.resource.resourceKey}")
        }
    }

    companion object {
        const val DEFAULT_DATE: String = "2026-06-01T10:00:00Z"

        /**
         * The world's default capture-date cutoff (capability `photo-selection-policy`). Strictly precedes
         * [DEFAULT_DATE], so an asset added with default arguments is in scope and the harness behaves as
         * it did when a `null` cutoff meant whole-library. A cutoff is never absent.
         */
        const val DEFAULT_CUTOFF: String = "2026-01-01T00:00:00Z"

        /**
         * The default event start (capability `event-creation`) — the FLOOR under every membership's
         * cutoff. At/before [DEFAULT_CUTOFF] and well before [DEFAULT_DATE], so a default provision models
         * a **started** event whose floor binds nothing and the harness behaves exactly as it did before
         * start dates existed. Pass a future value to `provision` to model an event that has not begun.
         */
        const val DEFAULT_STARTS_AT: String = "2026-01-01T00:00:00Z"

        /** A single primary raw resource (`PHAssetResourceType.photo` == 1). */
        fun primaryResource(
            filename: String = "IMG.JPG",
            contentType: String = "image/jpeg",
        ): RawResource = RawResource(
            type = 1L,
            contentTypeUti = contentType,
            mimeContentType = contentType,
            originalFilename = filename,
            handle = Unit,
        )

        /** Build a foreign device's complete primary asset for [World.addForeignDevice]. */
        fun foreignAsset(
            assetId: String,
            filename: String = "IMG.HEIC",
            contentType: String = "image/heic",
            creationDate: String = DEFAULT_DATE,
        ): DeviceManifestAsset {
            val key = uploadKey(assetId, ResourceRole.PRIMARY, filename)
            return DeviceManifestAsset(
                assetId = assetId,
                creationDate = creationDate,
                resources = listOf(ManifestResource(ResourceRole.PRIMARY, contentType, key, filename)),
            )
        }
    }
}
