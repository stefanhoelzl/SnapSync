package app.snapsync.world

import app.snapsync.config.ConfigSource
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
import app.snapsync.gallery.DeviceManifestAsset
import app.snapsync.gallery.DeviceManifestProducer
import app.snapsync.gallery.GalleryResourceEnumerator
import app.snapsync.gallery.InMemoryRawAssetSource
import app.snapsync.gallery.ManifestResource
import app.snapsync.gallery.RawAsset
import app.snapsync.gallery.RawResource
import app.snapsync.gallery.ResourceEnumerator
import app.snapsync.gallery.ResourceRole
import app.snapsync.gallery.deviceManifestAssetsFromResources
import app.snapsync.gallery.uploadKey
import app.snapsync.rejoin.DeviceFilesSource
import app.snapsync.rejoin.ExtensionReconciler
import app.snapsync.rejoin.HttpDeviceFilesSource
import app.snapsync.status.LedgerBackedSyncStatusSource
import app.snapsync.status.LedgerCounts
import app.snapsync.status.OwnDeviceGalleryStatusSource
import app.snapsync.status.ReadingLedgerCountsSource
import app.snapsync.status.SyncStatusSource
import app.snapsync.upload.CycleResult
import app.snapsync.upload.UploadCycle
import app.snapsync.upload.buildUploadConfig
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

    /** The one shared mini-edge client injected into every real common-Ktor seam. */
    val client = miniEdgeClient(store)
    val manifestUploader: HttpDeviceManifestUploader = HttpDeviceManifestUploader(client, host)

    private val configCell = MutableStateFlow<EventConfig?>(null)
    val configSource: ConfigSource = object : ConfigSource {
        override val config: StateFlow<EventConfig?> = configCell.asStateFlow()
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
        )
    // Completed + pending both read from the world's real ledger (one consistent aggregates() read).
    val ledgerCounts: ReadingLedgerCountsSource = ReadingLedgerCountsSource {
        ledgerBackend.aggregates().let { LedgerCounts(completed = it.completed, pending = it.pending) }
    }

    // Single-instance real download controller (its Mutex must be shared across reconcile + staging).
    val downloadController: DownloadController =
        DownloadController(unionSource, downloadStore, downloadJobs, importer, myDeviceId = ownDeviceId)

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

    /** Add one of the OWN device's photos to the gallery (default: a single primary JPEG). */
    suspend fun addOwnAsset(
        assetId: String,
        creationDate: String = DEFAULT_DATE,
        resources: List<RawResource> = listOf(primaryResource()),
    ) {
        gallery.set(gallery.walkAll() + RawAsset(assetId, creationDate, resources))
    }

    /** Remove an own asset from the gallery (surfaces as `removedAssetIds` on the next incremental cycle). */
    suspend fun removeAsset(assetId: String) {
        gallery.set(gallery.walkAll().filterNot { it.assetId == assetId })
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

    /** Join/provision an event: register its marker and make its config present (the config gate lifts). */
    fun provision(eventId: String, name: String? = null) {
        store.registerEvent(eventId)
        configCell.value = EventConfig(eventId, name)
    }

    /**
     * Leave the joined event — the **faithful** in-place clear (NOT a world rebuild): run the real
     * [DownloadController.onLeaveOrSwitch] (cancel transfers, prune non-terminal download rows), then
     * clear the config cell and the joined-event marker. The gallery, backend store, ledger, and
     * **imported foreign photos** are retained (imported download rows are terminal / delete-proof), so
     * re-provisioning the same event afterwards still finds them suppressed (real cross-event dedup).
     * Clearing [configCell] is reactive, so the listing-backed status projection leaves the joined layer
     * with no rebuild.
     */
    suspend fun leave() {
        downloadController.onLeaveOrSwitch()
        configCell.value = null
        marker.clear()
    }

    // ---- composition helpers (mirror UploadExtensionRoot.process()) -----------------------------

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

    /** Assemble the real cycle for [eventId], wiring the manifest hook and echo-suppression. */
    fun uploadCycle(eventId: String): UploadCycle {
        val engine = SyncEngine(EdgeUploadRequestProvider(host, ownDeviceId), ledger)
        val producer = manifestProducer()
        return UploadCycle(
            engine = engine,
            ledger = ledger,
            platform = platform,
            store = discoveryStore,
            onDiscovery = { discovery ->
                producer.produce(
                    eventId = eventId,
                    startDate = null, // whole-library scope (SHIPPED behavior; no date filter)
                    discovered = deviceManifestAssetsFromResources(discovery.resources),
                    removedAssetIds = discovery.removedAssetIds.toSet(),
                    fullEnumeration = discovery.fullEnumeration,
                )
            },
            suppressedAssetIds = { downloadStore.suppressedLocalIds() },
        )
    }

    /**
     * The `process()`-shaped runner: reload config → reconcile → build config → run the real cycle. When
     * [requeuePending] is set, models the extension's "pending > 0 ⇒ PROCESSING" re-invocation request.
     */
    suspend fun runUploadCycle(requeuePending: Boolean = false): CycleResult {
        val payload = configCell.value
        val mayUpload = runCatching { reconciler().reconcile(payload?.eventId) }.getOrElse { false }
        val config = buildUploadConfig(payload?.eventId, host)
        if (config == null || !mayUpload) return CycleResult.COMPLETED
        val result = runCatching { uploadCycle(config.eventId).run() }.getOrElse { CycleResult.FAILED }
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

    /** The real create-event use-case over the mini-edge (`POST /events` → provision). */
    fun createEvent(scope: CoroutineScope): CreateEvent =
        CreateEvent(
            client = HttpEventCreationClient(client, host),
            status = creationStatus,
            provision = { eventId, name -> provision(eventId, name) },
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
