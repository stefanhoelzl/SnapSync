package app.snapsync.world

import app.snapsync.compose.AppCore
import kotlinx.coroutines.CompletableDeferred
import app.snapsync.compose.AppPorts
import app.snapsync.compose.UploadPorts
import app.snapsync.compose.snapSyncApp
import app.snapsync.compose.uploadCore
import app.snapsync.download.HttpEventUnionSource
import app.snapsync.eventcreation.HttpEventCreation
import app.snapsync.eventcreation.HttpEventRename
import app.snapsync.fake.InMemoryAttestStore
import app.snapsync.fake.InMemoryDeviceLogSource
import app.snapsync.fake.InMemoryDiagnosticsReporter
import app.snapsync.model.DiagnosticDump
import app.snapsync.ports.DeviceLogSource
import app.snapsync.fake.InMemoryPhotoSelectionChangeSource
import app.snapsync.fake.InMemoryDeviceManifestStore
import app.snapsync.fake.InMemoryDiscoveryStore
import app.snapsync.fake.InMemoryDownloadStore
import app.snapsync.fake.InMemoryJoinedEventMarker
import app.snapsync.fake.InMemoryStagedBytes
import app.snapsync.fake.InMemoryLedgerStore
import app.snapsync.feature.creation.MutableCreationStatusSource
import app.snapsync.feature.membership.MutableRenameStatusSource
import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.download.StoreDownloadStatusSource
import app.snapsync.feature.membership.JoinEvent
import app.snapsync.feature.status.OwnDeviceGalleryStatusSource
import app.snapsync.feature.status.ReadingLedgerCountsSource
import app.snapsync.feature.status.SyncStatusSource
import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.upload.UploadCycle
import app.snapsync.join.HttpEnrollment
import app.snapsync.join.HttpEventDirectory
import app.snapsync.membership.HttpDeviceFilesSource
import app.snapsync.membership.HttpLeaveNotifier
import app.snapsync.model.normalizeAssetId
import app.snapsync.model.resourcesFrom
import app.snapsync.model.Resource
import app.snapsync.model.AssetFacts
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.EventEnd
import app.snapsync.model.CaptureDate
import app.snapsync.model.EventStart
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.noContribution
import app.snapsync.model.selectionPolicyFor
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.eventStart
import app.snapsync.model.toFacts
import app.snapsync.model.DENYLISTED_ALBUM_TITLES
import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.ManifestResource
import app.snapsync.model.RawAsset
import app.snapsync.model.RawResource
import app.snapsync.model.ResourceRole
import app.snapsync.model.UserCommands
import app.snapsync.model.uploadKey
import app.snapsync.ports.AssetRef
import app.snapsync.ports.AttestClient
import app.snapsync.ports.AttestKey
import app.snapsync.ports.ConfigRead
import app.snapsync.ports.ConfigReader
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.ConfigStore
import app.snapsync.ports.CycleResult
import app.snapsync.ports.PhotoAccessRequester
import app.snapsync.ports.CandidateSource
import app.snapsync.ports.TransferOutcome
import app.snapsync.model.PermissionStatus
import co.touchlab.kermit.Logger
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The controllable in-memory **world** (capability `harness-world-model`): the backend object store,
 * the mini-edge, and the operator levers — wrapped around `:adapter:generic:fake`'s honest doubles — that the
 * REAL app graph runs against. Since migration step 10 the world composes that graph through the
 * **same** [snapSyncApp] the iOS shell calls (spec `module-architecture`, "One shared composition"),
 * so [core] IS the production `AppCore` — features, flows, and the user-tap command bundle — over
 * fake ports; and the upload cycle is the same [uploadCore] both device tiers call. A wiring
 * difference between the harness and production is impossible rather than undetected.
 *
 * It models exactly **one** fixed own device ([ownDeviceId]) — the id the upload cycle, provider,
 * reconciler, and own-device status all use — and any number of **injectable** foreign devices
 * ([addForeignDevice]) whose complete assets appear in the event-wide union for download/echo.
 */
class World(
    /**
     * The caller's scope — **not** one the world owns. It becomes [AppCore]'s composition scope, so
     * the status collectors and fire-and-forget feature work launch into it; it must belong to
     * whoever drives the world (inside [worldTest] that is the auto-cancelled test scope, in the
     * desktop harness the inspector's) or that work would outlive the caller.
     */
    val scope: CoroutineScope,
    val ownDeviceId: String = "00000000-0000-4000-9000-0000000000a1",
    val host: String = "https://world.edge",
    /**
     * The ledger this world composes over. Injectable for ONE reason: building a second world over the
     * same backend is how a **process boundary** is expressed here. Everything else a world holds is
     * in-memory and dies with it — which is exactly what a relaunch does to a device — so passing the
     * ledger on is what makes "did this fact survive the process?" an assertable question rather than a
     * device-only one (`changes/fix-lost-upload-acks`).
     */
    val ledgerBackend: InMemoryLedgerStore = InMemoryLedgerStore(),
) {

    // ---- world state + fakes (all public / inspectable) -----------------------------------------

    val store: BackendStore = BackendStore()

    /** The world's gallery rigging around the honest raw-asset fake (see [WorldGallery]). */
    val gallery: WorldGallery = WorldGallery()
    /** The one read seam, straight off the world-owned cell — no enumerator composition to stand up. */
    val enumerator: CandidateSource = gallery.source
    val discoveryStore: InMemoryDiscoveryStore = InMemoryDiscoveryStore()
    val downloadStore: RecordingDownloadStore = RecordingDownloadStore(InMemoryDownloadStore())
    // The SAME ledger the composed cycle writes: this adapter records terminal outcomes into it, exactly
    // as both device adapters do, so the world exercises the real two-phase completion.
    val platform: FakeBackgroundTransfer =
        FakeBackgroundTransfer(store, ownDeviceId, enumerator, ledgerBackend)
    /**
     * The fake execution edge, captured when the real jobs first realize a transport (lazily, on the first
     * transfer — exactly as production does). `null` until then.
     */
    var downloadTransport: FakeDownloadTransport? = null
        private set

    // Wired to the store exactly as the iOS shell wires the real importer: the marker is written from
    // inside the "change block", before the created asset is observable. Without this the world cannot
    // reach an unconfirmed row — a marker written, the confirmation never arriving — which is the state
    // the duplicate-import defect lives in (capability `download-store`).
    val importer: FakePhotoLibraryImporter = FakePhotoLibraryImporter(
        gallery = gallery,
        recordCreatedLocalId = { ref, id -> downloadStore.recordCreatedLocalId(ref, id) },
        clearCreatedLocalId = { ref, id -> downloadStore.clearCreatedLocalId(ref, id) },
        confirmCreatedLocalId = { ref, id -> downloadStore.confirmCreatedLocalId(ref, id) },
    )
    /**
     * Presence over the world's own gallery: an asset the importer created is visible here for exactly
     * the same reason it is visible to upload discovery, so a test cannot assert against an answer the
     * rest of the world disagrees with (capability `harness-world-model`).
     */
    val assetPresence: WorldAssetPresence = WorldAssetPresence(gallery)

    /**
     * The world's "disk" for staged download bytes (capability `download-store`). Real enough to assert
     * the property that matters — bytes SURVIVE a failed, abandoned or unconfirmed import and vanish only
     * once the row is settled — rather than merely that a release call happened.
     */
    val stagedBytes: InMemoryStagedBytes = InMemoryStagedBytes()
    val marker: InMemoryJoinedEventMarker = InMemoryJoinedEventMarker()

    /** Counts the real `Provision` flow's on-join push re-registration (capability `push-registration`):
     *  the `registerPush` effect below increments it, so a test can assert the join path fired it. */
    var registerPushCount: Int = 0
        private set
    val manifestStore: InMemoryDeviceManifestStore = InMemoryDeviceManifestStore()
    val permission: MutablePhotoAccessStatusSource = MutablePhotoAccessStatusSource()

    /** Whether the composition started reporting — the `DiagnosticsReporter.start()` observation. */
    val diagnosticsStarted: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** Every diagnostic dump the composition transmitted, in order (capability `diagnostic-logging`). */
    val diagnosticsSent: MutableStateFlow<List<DiagnosticDump>> = MutableStateFlow(emptyList())

    /** The device logs a dump reads back. Seed one to give the world a log to carry. */
    val deviceLogs: MutableStateFlow<Map<DeviceLogSource.Process, String>> = MutableStateFlow(emptyMap())

    // Selection snapshots under a partial grant (capability `limited-photo-access`): the honest fake
    // over an operator-held cell. Emitting IS the operator lever (see [changeSelection]); replay 0 —
    // a snapshot is a change notification, not a state the composition may re-collect.
    private val selectionChangesCell = MutableSharedFlow<List<Resource>>()
    val albumManager: FakeAlbumManager = FakeAlbumManager()
    val albumMapStore = app.snapsync.fake.InMemoryAlbumMapStore()

    /** The one shared mini-edge client injected into every real common-Ktor seam. */
    val client = miniEdgeClient(store)

    /** The `:adapter:generic:app` enrollment PUT over the mini-edge — the ONE `Enrollment` impl (the
     *  world's byte-identical copy died at step 10, closing the deletion ledger's last row). */
    val manifestUploader: HttpEnrollment = HttpEnrollment(client, host)
    /**
     * The REAL backend-leave seam (the `:adapter:generic:app` [HttpLeaveNotifier] over the mini-edge),
     * bound to this world's own device — which is what the port means (see
     * [app.snapsync.ports.LeaveNotifier]): "this device is leaving". A test that must speak for a
     * DIFFERENT member binds its own instance to that id, so the substitution is visible where it is
     * made rather than hidden in an argument at a call site.
     */
    private val leaveNotifier = HttpLeaveNotifier(client, host) { ownDeviceId }

    private val configCell = MutableStateFlow<EventConfig?>(null)
    val configSource: ConfigSource = object : ConfigSource {
        override val config: StateFlow<EventConfig?> = configCell.asStateFlow()
    }

    // The write side of the same cell [configSource] reads — the composed `LeaveEvent`/`JoinEvent`
    // clear/set the config the container reduces from.
    val configStore: ConfigStore = object : ConfigStore {
        override suspend fun save(config: EventConfig) { configCell.value = config }
        override suspend fun clear() { configCell.value = null }
    }

    // The real common-Ktor seams over the mini-edge (single client, exactly as production shares one).
    val deviceFiles = HttpDeviceFilesSource(client, host)
    val unionSource = HttpEventUnionSource(client, host)

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

    /** Arm the next foreign import to fail (`ImportResult.Failed`, non-terminal) before creating anything. */
    fun failNextImport() {
        importer.failNextImport = true
    }

    /**
     * Arm the next foreign import to create its asset and write its marker, and only THEN report failure
     * — the real adapter's "commit reported failure after the change block ran" path, where the mirror
     * clears the marker again (capability `download-store`).
     */
    fun failNextImportAfterCreating() {
        importer.failNextImportAfterCreating = true
    }

    /**
     * The `SNAPSYNC-9` state, held OPEN: the next import writes its marker, the commit does not land, and
     * the import suspends until [resumeSuspendedImport] — so the photo library answers *absent* about an
     * asset whose transaction is still open, for as long as the test needs.
     *
     * Await [importerSuspended] before driving anything against it, rather than assuming a delay: the
     * import parks on the composition lane and a race here would make every test built on this flaky.
     */
    fun suspendNextImport() {
        importer.suspendNextImport = true
    }

    /**
     * The process-death shape, held open: the marker is written, the asset IS created, and the report
     * never comes — so a presence lookup answers *present*, which is the verdict that settles the row
     * against the marker it already holds. The asset exists; the store row stays **unconfirmed**.
     */
    fun suspendNextImportAfterCommit() {
        importer.suspendNextImportAfterCommit = true
    }

    /** Completes with the ref whose import has actually parked — the signal to drive concurrent triggers. */
    val importerSuspended: CompletableDeferred<AssetRef> get() = importer.suspendedImport

    /**
     * Deliver the suspended import's outcome. `succeeded = true` lands the asset and settles the row
     * against the marker it holds; `false` clears that marker — the two things the real completion
     * callback does, on its two paths.
     */
    fun resumeSuspendedImport(succeeded: Boolean) {
        importer.resumeSuspendedImport(succeeded)
    }

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
     * The world's membership read as the shared `ConfigReader` port — the [membershipUnreadable]
     * lever surfaces as [ConfigRead.Unavailable] with the real locked-device OSStatus
     * (`errSecInteractionNotAllowed`), so the shared entry gate's skip forensics read like a
     * device's. The gate itself is `uploadCore`'s — the world carries no translation of its own.
     */
    private val configReader: ConfigReader = object : ConfigReader {
        override fun read(): ConfigRead = when {
            membershipUnreadable -> ConfigRead.Unavailable(status = -25308)
            else -> configCell.value?.let { ConfigRead.Joined(it) } ?: ConfigRead.None
        }
    }

    // ---- the composed APP graph (the REAL snapSyncApp, over the fakes) --------------------------

    /**
     * Where a minted event routes (the shell's `onEventMinted` lambda, supplied here because the
     * world IS the shell): the default provisions the minted event directly (whole-library); the
     * desktop inspector points it at the status host's pending-join gate so create shows the real
     * join surface, exactly like the iOS app.
     */
    var onEventMinted: suspend (eventId: String) -> Unit = { provision(it) }

    /** The world's photo-access requester: a grant, immediately (the harness overlays its own arming). */
    val requester: PhotoAccessRequester = object : PhotoAccessRequester {
        override fun request() {
            permission.set(PermissionStatus.GRANTED)
        }
        override fun openSettings() {}

        // No limited-library picker exists off device; the selection is changed by the operator lever
        // [changeSelection] instead, which is the same thing the real picker's outcome amounts to.
        override fun choosePhotos() {}
    }

    // Attestation is composed (AppPorts requires the seams) but never exercised: the mini-edge is
    // unauthenticated and nothing in the world wakes the DeviceAttestation lazily composed on [core].
    private val attestKey: AttestKey = object : AttestKey {
        override fun isSupported(): Boolean = false
        override suspend fun generateKey(): String = error("the world does not attest")
        override suspend fun attest(keyId: String, challenge: String): ByteArray =
            error("the world does not attest")
        override suspend fun assert(keyId: String, challenge: String): ByteArray =
            error("the world does not attest")
    }
    private val attestClient: AttestClient = object : AttestClient {
        override suspend fun challenge(): String? = null
        override suspend fun mintToken(
            deviceId: String,
            keyId: String,
            attestation: ByteArray,
            challenge: String,
        ): String? = null
        override suspend fun renewToken(deviceId: String, assertion: ByteArray, challenge: String): String? = null
    }

    /**
     * The world's wall clock, in epoch millis — an operator **lever**, pinned at the epoch so nothing
     * depends on the host clock and every run is deterministic.
     *
     * Advance it to reach a time-gated behaviour. The one that needs it is the membership self-leave
     * (capability `leave-event`), whose second, OFFLINE witness is the device's own persisted deadline:
     * with the clock at the epoch that witness can never be satisfied, so a `404` is always disbelieved —
     * which is the safe default a test must be able to step past deliberately.
     */
    var nowMillis: Long = 0L

    /**
     * The REAL app graph (spec `module-architecture`, "One shared composition"): the same
     * [snapSyncApp] the iOS shell calls, over the world's ports. Features, flows, and the user-tap
     * command bundle all live on this — the world adds only operator levers and inspection around it.
     */
    val core: AppCore = snapSyncApp(
        scope = scope,
        ports = AppPorts(
            // The world's platform-UI ports are in-memory doubles, so there is no real main thread to
            // reach. It takes the SAME lane as the composition scope rather than an unconfined default:
            // a lane that means "wherever the caller happened to be" is precisely what this law ends.
            uiLane = scope.coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext,
            diagnosticsReporter = InMemoryDiagnosticsReporter(
                started = diagnosticsStarted,
                sent = diagnosticsSent,
                isConfigured = true,
            ),
            // The device logs a dump reads back (capability `diagnostic-logging`) — empty until an
            // operator seeds them, which is honest: a world has no device writing log files.
            deviceLogSource = InMemoryDeviceLogSource(deviceLogs),
            configSource = configSource,
            configStore = configStore,
            photoAccess = permission,
            photoAccessRequester = requester,
            selectionChanges = InMemoryPhotoSelectionChangeSource(selectionChangesCell),
            // The operator plays the OS: nothing auto-runs. A selection change updates the cell + N; the
            // operator then invokes the cycle by hand, exactly like every other world trigger. That used
            // to be an inert `pumpSelectionChanged = {}` port here; it is now the world mechanism's own
            // stated answer to the trigger (`OperatorUploadProducer`), which is where a mechanism's
            // response to a kick belongs.
            candidateSource = enumerator,
            // The shared discovery cursor a cutoff-lowering reconfigure invalidates (capability
            // `reconfigure-membership`) — the SAME store the world's upload cycle reads.
            clearDiscoveryCursor = discoveryStore::clearToken,
            ledger = ledgerBackend,
            downloadStore = downloadStore,
            assetPresence = assetPresence,
            // Staging root AND release, one port: the world's staged paths are built from the same
            // root the fake reports, exactly as the App-Group container is on device.
            stagedBytes = stagedBytes,
            importer = importer,
            newDownloadTransport = { transportHost ->
                FakeDownloadTransport(transportHost, stagedBytes.files).also { downloadTransport = it }
            },
            union = unionSource,
            directory = HttpEventDirectory(client, host),
            enrollment = manifestUploader,
            manifestStore = manifestStore,
            eventCreation = HttpEventCreation(client, host),
            eventRename = HttpEventRename(client, host),
            attestKey = attestKey,
            attestClient = attestClient,
            attestStore = InMemoryAttestStore(),
            deviceId = { ownDeviceId },
            clock = { kotlin.time.Instant.fromEpochMilliseconds(nowMillis) },
            // The operator IS the producer: nothing auto-runs; a cycle happens when invoked by hand.
            appDrivenUpload = { OperatorUploadProducer() },
            albumManager = albumManager,
            albumMapStore = albumMapStore,
            // Denylisted-album membership (capability `photo-selection-policy`) — the REAL policy
            // constant over the world's forgeable album membership, exactly as the shell wires it.
            albumExcludedAssetIds = { cutoff -> albumManager.assetIdsInAlbums(DENYLISTED_ALBUM_TITLES, cutoff.at.iso) },
            leaveNotifier = leaveNotifier,
            provision = { cfg -> configCell.value = cfg },
            // Spy the real Provision flow's on-join push re-registration (capability `push-registration`).
            registerPush = { registerPushCount++ },
            onEventMinted = { eventId -> onEventMinted(eventId) },
            log = Logger.withTag("World"),
        ),
    )

    // Re-seated read handles: these ARE the composed graph's instances (never world-local rebuilds).
    val downloadController: DownloadController get() = core.downloadController
    val albumCoordinator: AlbumCoordinator get() = core.albumCoordinator
    val ownGallery: OwnDeviceGalleryStatusSource get() = core.gallery
    val ledgerCounts: ReadingLedgerCountsSource get() = core.ledgerCounts
    val creationStatus: MutableCreationStatusSource get() = core.creationStatus

    /** The rename status the real `RenameEvent` drives (capability `event-rename`). */
    val renameStatus: MutableRenameStatusSource get() = core.renameStatus
    val downloadStatusSource: StoreDownloadStatusSource get() = core.downloadStatusSource
    val syncStatusSource: SyncStatusSource get() = core.syncStatusSource
    val userCommands: UserCommands get() = core.userCommands
    val joinEvent: JoinEvent get() = core.joinEvent

    /** The operator's foreground-refresh: pull the composed status sources (they update on `refresh()`). */
    suspend fun refreshStatus() = core.refreshStatusSources()

    /**
     * Operator lever (capability `limited-photo-access`): the user changed the photo selection under a
     * partial grant to exactly [assetIds]. Mirrors the iOS adapter faithfully: the snapshot is the
     * selected assets' resources mapped through the SAME enumerator seam
     * (`PhotoLibrary.resources(ids, "")` — the empty cutoff admits every asset; the policy filters
     * downstream), emitted whole through the honest fake. Deliver with the scheduler (e.g.
     * `runCurrent`) before asserting — the collector recounts N and updates the cycle's scope cell.
     */
    suspend fun changeSelection(vararg assetIds: String) {
        // The composition's collector is a host-assembly launch (`installPermissionSubscriptions`);
        // await its subscription so an emission is never dropped into a not-yet-collected flow. A
        // world that never installed the host wiring hangs here — deliberately loud, since the lever
        // would otherwise silently do nothing.
        selectionChangesCell.subscriptionCount.first { it > 0 }
        // The sanctioned read the real snapshot source makes: eager, WITH resources (capability
        // `limited-photo-access` — deferring it would need a re-fetch by identifier later, which is the
        // measured storm). Unscoped here because the selection IS the scope.
        val wanted = assetIds.toSet()
        selectionChangesCell.emit(
            gallery.current().filter { normalizeAssetId(it.assetId) in wanted }.flatMap { resourcesFrom(listOf(it)) },
        )
    }

    init {
        // Touch the controller so its lazy construction installs the production `onStaged` hook. The world
        // used to RE-INSTALL that hook with the Job handles kept, because `onStaged` was not a suspend
        // seam and [stageAllDownloads] had no other way to await the launched imports. It is one now, and
        // the feature tracks its own launches (`awaitOutstandingImports`), so the world runs the
        // production wiring unshadowed — one fewer place the harness could diverge from the app.
        core.downloadController
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
        isScreenshot: Boolean = false,
        isScreenRecording: Boolean = false,
        isVideo: Boolean = false,
        pixelWidth: Long = 4032,
        pixelHeight: Long = 3024,
        isEdited: Boolean = false,
    ) {
        gallery.set(
            gallery.current() + RawAsset(
                assetId = assetId,
                creationDate = creationDate,
                rawResources = resources,
                // NEUTRAL facts — the world forges what the platform would have interpreted, never a
                // PhotoKit bitmask (capability `gallery-status`).
                facts = AssetFacts(
                    assetId = assetId,
                    creationDate = CaptureDate(creationDate),
                    isScreenshot = isScreenshot,
                    isScreenRecording = isScreenRecording,
                    isVideo = isVideo,
                    isEdited = isEdited,
                    pixelArea = pixelWidth * pixelHeight,
                ),
            ),
        )
    }

    // ---- selection-policy levers (capability `photo-selection-policy`) ---------------------------
    // Each forges one category the policy excludes, so every rule is exercisable in the harness and the
    // integration tests without PhotoKit — and so an operator can *see* that a screenshot never uploads.

    /** A screenshot. Excluded by media subtype — the sharpest and highest-frequency case. */
    suspend fun addScreenshot(assetId: String, creationDate: String = DEFAULT_DATE) =
        addOwnAsset(assetId, creationDate, isScreenshot = true, pixelWidth = 750, pixelHeight = 1334)

    /** A screen recording. Excluded by media subtype. */
    suspend fun addScreenRecording(assetId: String, creationDate: String = DEFAULT_DATE) =
        addOwnAsset(
            assetId, creationDate,
            isScreenRecording = true, isVideo = true,
            pixelWidth = 886, pixelHeight = 1920,
        )

    /** A compressed image as a messenger would have saved it (1600×1200 ≈ 1.9 MP). Below the image floor. */
    suspend fun addLowResPhoto(assetId: String, creationDate: String = DEFAULT_DATE) =
        addOwnAsset(assetId, creationDate, pixelWidth = 1600, pixelHeight = 1200)

    /** A 1080p recording — BELOW the image floor but ABOVE the video floor, so it must be **admitted**. */
    suspend fun addHdVideo(assetId: String, creationDate: String = DEFAULT_DATE) =
        addOwnAsset(
            assetId, creationDate,
            isVideo = true, pixelWidth = 1920, pixelHeight = 1080,
        )

    /**
     * A GIF as a messenger saves one — 480×270 = 0.13 MP. Excluded by the **resolution floor**, not by a
     * rule reading its MIME: there is no animated-image rule any more (capability
     * `photo-selection-policy`). Kept as a lever because "a received GIF does not upload" is still the
     * operator-visible behaviour worth forging, even though the rule that used to produce it is gone.
     */
    suspend fun addGif(assetId: String, creationDate: String = DEFAULT_DATE) =
        addOwnAsset(
            assetId, creationDate,
            resources = listOf(primaryResource(filename = "giphy.gif", contentType = "image/gif")),
            pixelWidth = 480,
            pixelHeight = 270,
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
        name: String = DEFAULT_EVENT_NAME,
        minPhotoDate: CaptureCutoff = captureCutoff(DEFAULT_CUTOFF),
        startsAt: EventStart = eventStart(DEFAULT_STARTS_AT),
        // The capture-date CEILING is REQUIRED on every membership (capability `join-event`), so the
        // default is a far-future one — the closest thing to the unbounded membership every fixture used
        // while the ceiling was silently dropped at two consumers. Pass a real one (with [endsAt]) to
        // forge a CLOSED window, the shape that surfaced the bug.
        maxPhotoDate: CaptureCeiling = captureCeiling(DEFAULT_FAR_CEILING),
        endsAt: EventEnd? = null,
        direction: Direction = Direction.Both,
        saveToAlbum: Boolean = false,
    ) {
        store.registerEvent(eventId, name, startsAt.at.iso)
        // ENROLL the own device, as a real join does: `join-event`'s enrollment publishes a register-only
        // empty manifest before any photo exists, so a joined device always holds a membership. Setting
        // only the config would forge a device that is joined on-device and unknown to the backend — a
        // state no join produces, and one in which a later leave has no membership to depart.
        //
        // An EXISTING membership is re-activated rather than emptied. A real re-enrollment does write an
        // empty manifest and relies on the cycle's skip-if-unchanged record being invalidated to restore
        // the projection; this operator shortcut writes no such record, so emptying here would model a
        // rejoin that silently loses its own contributions — a state the real path does not produce.
        val existing = store.manifestOf(eventId, ownDeviceId)
        store.putManifest(
            eventId,
            ownDeviceId,
            existing ?: DeviceManifest(deviceId = ownDeviceId, assets = emptyList()),
        )
        configCell.value = EventConfig(
            eventId = eventId,
            name = name,
            minPhotoDate = minPhotoDate,
            maxPhotoDate = maxPhotoDate,
            endsAt = endsAt,
            startsAt = startsAt,
            direction = direction,
            saveToAlbum = saveToAlbum,
        )
    }

    /**
     * Leave the joined event — the **faithful** in-place clear (NOT a world rebuild): run the real
     * [DownloadController.onLeaveOrSwitch] (cancel transfers, prune non-terminal download rows), then
     * the real backend leave (the `:adapter:generic:app` `HttpLeaveNotifier` over the mini-edge — the same
     * `DELETE` the app fires, driving the store's RENAME-ONLY departed-mark), then clear the config cell
     * and the joined-event marker. Deliberately an operator edge, not [UserCommands.leave]: the
     * composed leave's backend notify is fire-and-forget by design, and the operator's leave must be
     * COMPLETE on return so world assertions never race the DELETE (drive `core.userCommands.leave`
     * to exercise the production ordering instead). The gallery, ledger, and **imported foreign
     * photos** are retained (imported download rows are terminal / delete-proof), so re-provisioning
     * the same event afterwards still finds them suppressed (real cross-event dedup). Clearing
     * [configCell] is reactive, so the listing-backed status projection leaves the joined layer with
     * no rebuild. Backend outcomes (the device departed; the event and its bytes RETAINED until the
     * nightly sweep reclaims them, capability `scheduled-cleanup`) are assertable on [store].
     */
    suspend fun leave() {
        core.downloadController.onLeaveOrSwitch()
        configCell.value?.eventId?.let { leaveNotifier.notifyLeaving(it) }
        configCell.value = null
        marker.clear()
    }

    // ---- the upload cycle (the extension tier's shared assembly) --------------------------------

    /**
     * What the joined membership contributes (capability `photo-selection-policy`) — its participation
     * direction AND its cutoff, derived from the config cell through the **same** `Contribution.of` the
     * composition roots use. Both consumers of the policy take this: the upload cycle (which declines with
     * `SKIPPED` for `None`) and the own-device total `N` (which reports 0 without walking).
     *
     * An **unjoined** world yields [SelectionPolicy.None], not a default cutoff — there is no membership,
     * so there is nothing to contribute and `N` is 0, the same answer the cycle reaches.
     */
    suspend fun selectionPolicy(): SelectionPolicy =
        configCell.value
            ?.let {
                // The SAME derivation the shell and the cycle use — this world composes production
                // instances, so a policy built any other way here would not be the one under test.
                selectionPolicyFor(
                    config = it,
                    suppressedAssetIds = { downloadStore.suppressedLocalIds() },
                    albumExcludedAssetIds = { emptySet() },
                )
            }
            // An UNJOINED world contributes nothing, expressed the way every non-contributor is: the
            // deny-everything rule, not an absent policy and not a default cutoff.
            ?: noContribution()

    /**
     * Every event this world's cycles notified (capability `upload-completion-notify`), in order.
     * The mini-edge has no notify route, so this records the call rather than serving it.
     */
    val notified: MutableList<String> = mutableListOf()

    /**
     * The real cycle — assembled by the SAME shared composition the device tiers call ([uploadCore],
     * spec `module-architecture` "One shared composition"), over the world's fakes. Long-lived, as on
     * both tiers: the shared entry gate re-reads the membership on every `run()`, so a provision,
     * leave, or switch takes effect on the next cycle. The world carries no gate, reconciler, or
     * manifest-producer wiring of its own — a wiring difference from production is impossible.
     */
    val cycle: UploadCycle by lazy {
        uploadCore(
            scope,
            UploadPorts(
                diagnosticsReporter = InMemoryDiagnosticsReporter(),
                config = configReader,
                deviceId = { ownDeviceId },
                host = { host },
                ledger = ledgerBackend,
                transfer = platform,
                selectionScope = { core.selectionScope() },
                discoveryStore = discoveryStore,
                deviceFiles = deviceFiles,
                joinedMarker = marker,
                manifestStore = manifestStore,
                enrollment = manifestUploader,
                suppression = downloadStore,
                // The SAME policy wrapper the app graph gets (capability `photo-selection-policy`).
                albumExcludedAssetIds = { cutoff -> albumManager.assetIdsInAlbums(DENYLISTED_ALBUM_TITLES, cutoff.at.iso) },
                // Shared with the app graph, as the world's single-process stand-in for the App-Group map.
                albumCoordinator = core.albumCoordinator,
                // The mini-edge is unauthenticated; the world states its empty answer explicitly.
                token = { null },
                onBatchUploaded = { eventId -> notified += eventId },
            ),
        )
    }

    /**
     * Run one cycle. The membership read, the gate, the leave-side reconcile, and the assembly are all
     * inside the real [cycle]; what is left here is the extension tier's own "pending > 0 ⇒
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

    // ---- download operator action ---------------------------------------------------------------

    /**
     * Deliver a finish for every in-flight transfer, through the **real** jobs (capability
     * `harness-world-model`). The operator plays the network: [outcome] is what the transfer turned out to
     * be, defaulting to an ordinary healthy one.
     *
     * A rejected outcome stages nothing and leaves the resource PENDING for retry — that is the world's
     * existing no-terminal-failure posture, not a new state. This is the only way to reproduce the shape of
     * the bug end-to-end: a `502` arrives here as a *successful* transfer of an error body, and staging it
     * would make it the store's truth forever (capability `photo-download`).
     */
    suspend fun stageAllDownloads(outcome: TransferOutcome = FakeDownloadTransport.HEALTHY) {
        val transport = downloadTransport ?: return
        transport.inFlight().forEach { transport.finish(it.description, outcome) }
        // Await the imports the jobs launched so this action is complete on return — the operator drives
        // the world synchronously, and a racy stage would make every download assertion flaky.
        core.downloadJobs.awaitOutstandingImports()
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

        /**
         * The default event **name** of a provisioned membership. It exists because a membership without
         * a name is not a representable state (capability `event-link`): `provision` used to take a
         * nullable name and coerce `null` to `""`, which forged a config the real stack can no longer
         * hold. The world may model a *backend* event whose details response lacks a name — that is what
         * `BackendStore.registerEvent`'s nullable name is for — but never a joined membership without one.
         */
        const val DEFAULT_EVENT_NAME: String = "Anna's Birthday"

        /**
         * The default capture-date **ceiling** of a provisioned membership. Far enough out that it
         * admits every asset the harness adds, so a test that does not care about the ceiling behaves as
         * the old unbounded default did — while the type still refuses a membership without one
         * (capability `join-event`).
         */
        const val DEFAULT_FAR_CEILING: String = "2099-01-01T00:00:00Z"

        /** A single primary raw resource. */
        fun primaryResource(
            filename: String = "IMG.JPG",
            contentType: String = "image/jpeg",
        ): RawResource = RawResource(
            role = ResourceRole.PRIMARY,
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
