package app.snapsync.world

import app.snapsync.model.AssetId
import app.snapsync.model.InviteLinkHints
import app.snapsync.feature.membership.toJoinLoad
import app.snapsync.model.JoinLoad
import app.snapsync.ports.PushTokenSource
import app.snapsync.services.backend.ManifestPublisher
import app.snapsync.compose.UploaderProcess
import app.snapsync.compose.AlbumLookupFailure
import app.snapsync.compose.AppCore
import app.snapsync.compose.AppPorts
import app.snapsync.compose.PushPorts
import app.snapsync.compose.UploadRecordPorts
import app.snapsync.compose.UploadPorts
import app.snapsync.host.ComposedApp
import app.snapsync.host.snapSyncHost
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.model.PlannedResource
import app.snapsync.time.SystemClock
import app.snapsync.presentation.CutoffFormatter
import kotlinx.datetime.TimeZone
import app.snapsync.compose.uploadCore
import app.snapsync.fake.inMemoryConfigReader
import app.snapsync.fake.inMemoryConfigSource
import app.snapsync.fake.inMemoryConfigStore
import app.snapsync.fake.inMemoryProcessInfo
import app.snapsync.fake.inMemoryDeviceIntegrity
import app.snapsync.fake.inMemoryAttestStore
import app.snapsync.fake.inMemoryDeviceLogSource
import app.snapsync.fake.inMemoryDeviceManifestStore
import app.snapsync.fake.inMemoryPushRegistrationRecord
import app.snapsync.fake.inMemoryCrashReporter
import app.snapsync.fake.inMemoryFiles
import app.snapsync.compose.ProcessPorts
import app.snapsync.compose.ProcessServices
import app.snapsync.compose.snapSyncProcess
import app.snapsync.model.CrashEvent
import app.snapsync.ports.ProcessMetrics
import app.snapsync.ports.EntryContext
import app.snapsync.fake.inMemoryDownloadStore
import app.snapsync.fake.inMemoryLedgerStore
import app.snapsync.fake.inMemoryStagedBytes
import app.snapsync.fake.inMemoryBackgroundTime
import app.snapsync.fake.inMemoryExtensionRegistry
import app.snapsync.fake.HeldBackgroundTime
import app.snapsync.feature.upload.TailTrigger
import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.creation.readmodel.MutableCreationStatusSource
import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.download.StoreDownloadStatusSource
import app.snapsync.feature.membership.JoinEvent
import app.snapsync.feature.membership.SwitchDecision
import app.snapsync.feature.membership.switchDecision
import app.snapsync.feature.membership.readmodel.MutableRenameStatusSource
import app.snapsync.feature.status.OwnDeviceGalleryStatusSource
import app.snapsync.feature.status.ReadingLedgerCountsSource
import app.snapsync.feature.status.readmodel.SyncStatusSource
import app.snapsync.feature.upload.UploadCycle
import app.snapsync.model.AssetFacts
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureDate
import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.EventEnd
import app.snapsync.model.EventStart
import app.snapsync.model.ManifestResource
import app.snapsync.model.RawAsset
import app.snapsync.model.RawResource
import app.snapsync.model.Resource
import app.snapsync.model.ResourceRole
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.UserCommands
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.eventStart
import app.snapsync.model.noContribution
import app.snapsync.model.resourcesFrom
import app.snapsync.model.selectionPolicyFor
import app.snapsync.model.uploadKey
import app.snapsync.model.AssetRef
import app.snapsync.ports.Backend
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.model.CandidateRead
import app.snapsync.model.Candidate
import app.snapsync.ports.CandidateSource
import app.snapsync.services.gallery.GalleryAlbums
import app.snapsync.model.SELECTION_CALIBRATION
import app.snapsync.services.gallery.GalleryCandidateSource
import app.snapsync.model.ConfigRead
import app.snapsync.ports.ConfigReader
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.ConfigStore
import app.snapsync.model.CycleResult
import app.snapsync.ports.DeviceLogSource
import app.snapsync.ports.DeviceManifestStore
import app.snapsync.ports.PushRegistrationRecord
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.LedgerStore
import app.snapsync.ports.StagedBytes
import app.snapsync.model.TransferOutcome
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The controllable in-memory **world** (`docs/testing.md`): the backend object store,
 * the mini-edge, and the operator levers — wrapped around `:adapter:generic:fake`'s honest doubles — that the
 * REAL app graph runs against. Since migration step 10 the world composes that graph through the
 * **same** [snapSyncApp] the iOS shell calls (`docs/architecture.md`, "One shared composition"),
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
    /**
     * The backend this world composes over (`docs/testing.md`, "The world's backend is one seam
     * with two implementations") — the mini-edge unless a test asks for the real `api/` (`denoBackend()`, JVM
     * only). Everything the world does to its backend goes through it.
     */
    val backend: WorldBackend = MiniEdgeBackend(),
    /**
     * The device-facing base every seam composes from — carrying **exactly one** version prefix, as a
     * real build's baked base does (`docs/deployment.md`). The mini-edge serves both
     * versions, so pointing this at the other prefix is how a test exercises the other one.
     */
    val host: String = backend.base,
    /**
     * The ledger this world composes over. Injectable for ONE reason: building a second world over the
     * same backend is how a **process boundary** is expressed here. Everything else a world holds is
     * in-memory and dies with it — which is exactly what a relaunch does to a device — so passing the
     * ledger on is what makes "did this fact survive the process?" an assertable question rather than a
     * device-only one (`changes/fix-lost-upload-acks`).
     */
    val ledgerBackend: LedgerStore = inMemoryLedgerStore(),
    /**
     * The download store the app graph writes and the cycle reads for echo suppression — the honest in-memory one
     * unless a test hands in the real storage service (as the "composition opens no database" test does).
     */
    downloadBackend: DownloadStore = inMemoryDownloadStore(),
    /**
     * Whether this world can ATTEST (capability `privacy-security`). **Off by default**, which is the
     * world as it has always been: attestation is composed because `AppPorts` requires the seams, and
     * nothing exercises it.
     *
     * Turn it on to drive anything that depends on a CREDENTIAL CHANGE — the token-changed arm of
     * [AppCore.installPushRegistration] is the reason it exists. Off, `DeviceAttestation.refresh` returns
     * early without attesting, exactly as it does in the upload extension and on a simulator, so every
     * test that does not ask for this sees the behaviour it always saw.
     */
    val attests: Boolean = false,
    /**
     * Whether this world's join gate acts on an invite link's dev/test hints (`autoJoin` + its overrides,
     * capability `join-event`). **[InviteLinkHints.Ignored] by default — the phone's shipped answer**, so a
     * world composes exactly what a production root composes. The control channel's JVM host passes
     * [InviteLinkHints.Honoured], as the rig's boot hook does on a device.
     */
    val inviteLinkHints: InviteLinkHints = InviteLinkHints.Ignored,
    /**
     * Where this world's build reports (capability `privacy-security`): a distributed build's destination by default,
     * or `null` for a build that reports nowhere, which keeps a bug report in [privateFiles] instead.
     */
    val dsn: String? = WORLD_DSN,
) {

    // ---- world state + fakes (all public / inspectable) -----------------------------------------

    /**
     * The mini-edge's in-memory store — **mini-edge-only** (`docs/testing.md`, "Neutral inspection
     * and minted event ids beside the mini-edge-only surface"). On a world over any other backend reading it
     * fails with a stated error rather than answering an empty store, which would let a test assert against
     * state the backend never held. Backend-neutral code reads through [objectsOf], [unionOf], [isRegistered]
     * and the levers beside them instead.
     */
    val store: BackendStore
        get() = (backend as? MiniEdgeBackend)?.store ?: error(
            "World.store is the mini-edge's in-memory store, and this world is over the ${backend.name} " +
                "backend. Read through the backend-neutral API (objectsOf, unionOf, isRegistered, manifestOf) " +
                "and seed through provisionMinted / addForeignDeviceMinted instead.",
        )

    /**
     * What the composed graph logged — an **inspection list**, the same family of operator rigging as the
     * failure levers (`docs/testing.md`).
     *
     * It exists because a **severity** is part of some contracts rather than an implementation detail. The
     * read-only upload-ledger check reports its fault at `Error` precisely so it reaches crash reporting
     * as an event (capability `privacy-security`), and the two states it must stay *silent* about are only
     * assertable by watching what it wrote. Asserting on the outcome instead is not available: the check
     * writes nothing, by contract.
     *
     * It ADDS a writer rather than replacing the platform one, so the desktop world harness's console
     * still shows everything it always did.
     */
    val logs: WorldLog = WorldLog()

    /** The world's gallery rigging around the honest raw-asset fake (see [WorldGallery]). */
    val permission: MutablePhotoAccessStatusSource = MutablePhotoAccessStatusSource()

    /** The world's gallery, over the same grant cell as [permission] (see [WorldGallery]). */
    val gallery: WorldGallery = WorldGallery(permission.cell)

    /** The raw library read over the world's gallery — the same service the app composes, without the grant wrapper. */
    val enumerator: CandidateSource = GalleryCandidateSource(gallery)

    /**
     * Operator read: the seam's candidates for [policy].
     *
     * [enumerator] is the **raw** world gallery, not the permission-aware wrapper the app's consumers
     * hold, so it always reports a readable library — a grant it cannot see cannot make it unreadable,
     * and the operator's failure lever throws instead. The empty branch is therefore unreachable rather
     * than a default, and it is spelled out so a reader does not mistake it for one.
     */
    /**
     * Operator read: the members of the gallery's denylisted albums captured since [cutoff] — through the same
     * album service and calibration the policy derivation uses, so the inspector shows what a cycle would subtract.
     */
    suspend fun denylistedAlbumMembers(cutoff: CaptureCutoff): Set<AssetId> =
        GalleryAlbums(gallery).assetIdsInAlbums(SELECTION_CALIBRATION, cutoff)

    suspend fun readCandidates(policy: SelectionPolicy): List<Candidate> =
        when (val read = enumerator.candidates(policy)) {
            is CandidateRead.Readable -> read.candidates
            CandidateRead.NotReadable -> emptyList()
        }
    val downloadStore: RecordingDownloadStore = RecordingDownloadStore(downloadBackend)
    // The SAME ledger the composed cycle writes: this adapter records terminal outcomes into it, exactly
    // as both device adapters do, so the world exercises the real two-phase completion.
    // It completes a transfer with a real PUT over the backend's bare client — the network an OS transfer
    // crosses — so a completed object is one the chosen backend itself accepted.
    val platform: FakeUpload = FakeUpload(backend.newClient())
    // The cycle's library reads — the change feed and the id-scoped key resolve — over the in-memory gallery,
    // bound once beside the job queue exactly as the device roots bind `GalleryDiscovery`.
    val discovery: FakeUploadDiscovery = FakeUploadDiscovery(gallery)
    /**
     * The operating system's background download session — the transfers it holds for this app. Durable across a
     * [relaunch], as a background `URLSession` is: a relaunched app finds the transfers the dead process started, and
     * their completions arrive there. Each launch's composition registers its own handlers on it.
     */
    val download: FakeDownload = FakeDownload()

    /** [download], once this launch has brought the session up (a transfer, a cancel, or a handback) — else `null`. */
    val downloadTransport: FakeDownload? get() = download.takeIf { it.realized }

    /**
     * The app uploader's transfer session as the operating system plays it — the world's app uploader is the inert
     * [operatorEngine], so this session holds no jobs; the operator hands its background events back through it.
     */
    val appUpload: WorldAppUpload = WorldAppUpload()

    /**
     * The import rigging — the operator's script for how the library answers a change, and what was imported. The
     * marker writes are the composed core's own gallery handlers, exactly as on a device: the marker lands from
     * inside the "change block", before the created asset is observable. Without that the world could not reach an
     * unconfirmed row — the state the duplicate-import defect lives in (capability `receiving-photos`).
     */
    val importer: WorldImports get() = gallery.imports
    /**
     * The world's "disk" for staged download bytes (capability `receiving-photos`). Real enough to assert
     * the property that matters — bytes SURVIVE a failed, abandoned or unconfirmed import and vanish only
     * once the row is settled — rather than merely that a release call happened.
     */
    /** The operator's own cell: the rigging owns what it wants to observe and passes it in, rather
     *  than reading it back off the honest double (`docs/architecture.md`). */
    val stagedFiles: MutableSet<String> = mutableSetOf()
    val stagedBytes: StagedBytes = inMemoryStagedBytes(stagedFiles)

    /**
     * Model the photo library **ingesting** a staged resource: it takes a resource's file when it
     * ingests it, which it does only as part of creating an asset, and it does so even when the process
     * that submitted the change then dies (capability `receiving-photos`).
     *
     * Removing the path from [stagedFiles] IS the entire model — that set is the disk — and this exists
     * to NAME the operation rather than to add a mechanism. Without a name a test spells it as a set
     * removal, and the next reader cannot tell "the library took these" from "the release pass ran",
     * which are the two states the adjudicator must never confuse.
     */
    fun consumeStagedBytes(vararg paths: String) {
        stagedFiles.removeAll(paths.toSet())
    }
    /** Push-registration writes that LANDED on the mini-edge (capability `receiving-photos`), counted at the
     *  port: the composed registration — its launch/rotation collector and the join's re-PUT — writes through
     *  it, so a test can assert the join path fired it. Counted after the write, so a count is a landed write. */
    var registerPushCount: Int = 0
        private set

    /**
     * The operating system's scheduled wakes — the queue the heartbeat lands in, durable across [relaunch]. The
     * operator delivers a wake through [WorldWake.fire]; nothing fires one on its own.
     */
    val wake: WorldWake = WorldWake()

    /** How many times the tail runner re-armed the app uploader's heartbeat — counted, never run. */
    val heartbeatsScheduled: Int get() = wake.heartbeatsScheduled

    /**
     * The operating system's table of outstanding background-time holds (`docs/architecture.md`, "Background
     * time is an outbound port named for the need") — the operator's cell, read to see which wakes still hold time
     * and expired through [expireBackgroundTime]. Durable across [relaunch] only in the sense a real table is not:
     * a relaunch is a new process, so the operator clears nothing and the dead process's holds simply never end.
     */
    val backgroundTimeHolds: MutableStateFlow<List<HeldBackgroundTime>> = MutableStateFlow(emptyList())

    /** Operator lever: the operating system says every outstanding background-time hold's time is up. */
    fun expireBackgroundTime() {
        backgroundTimeHolds.value.forEach { it.expire() }
    }

    /** The app's foreground life — the operator activates and backgrounds the app through it. */
    val lifecycle: WorldLifecycle = WorldLifecycle()

    /** The links the platform opens the app with — the operator opens one through it. */
    val links: WorldLinks = WorldLinks()

    /** The platform's push service — the operator delivers tokens and silent pushes through it. */
    val pushNotifications: WorldPushNotifications = WorldPushNotifications()

    /** The platform's user interface — what the core last showed, and the operator's taps. */
    val ui: WorldUi = WorldUi()

    /** The build's development controls — see [WorldDevControls]. */
    val devControls: WorldDevControls = WorldDevControls(inviteLinkHints)

    /** The OS-delivered APNs token, as the world's shell delivers it (none until a test delivers one). */
    val pushTokens: PushTokenSource = PushTokenSource("sandbox")
    val manifestStore: DeviceManifestStore = inMemoryDeviceManifestStore()

    /** The last push registration the backend accepted — an App-Group file on a device, so durable across [relaunch]. */
    private val pushRegistrationRecord: PushRegistrationRecord = inMemoryPushRegistrationRecord()
    /** Whether the process started reporting — the `CrashReporter.start` observation. */
    val diagnosticsStarted: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** Every diagnostic dump the process transmitted, in order, as it left (capability `privacy-security`). */
    val diagnosticsSent: MutableStateFlow<List<CrashEvent>> = MutableStateFlow(emptyList())

    /**
     * The app process's own files (the PRIVATE area) — durable across [relaunch], as a device's are. The App-Group
     * (SHARED) area the other stores model is not held here.
     */
    val privateFiles: MutableMap<String, ByteArray> = mutableMapOf()

    /** The device logs a dump reads back. Seed one to give the world a log to carry. */
    val deviceLogs: MutableStateFlow<Map<DeviceLogSource.Process, String>> = MutableStateFlow(emptyMap())

    val albumMapStore = app.snapsync.fake.inMemoryAlbumMapStore()

    /**
     * The world's backend client — bare: it carries nothing but the engine. The credential, the declared version
     * and every verdict are the port's and the composed services', exactly as on a device, so a `426` from the
     * mini-edge travels the actual path — `HttpBackend` → the authenticated backend → the core's version gate → the
     * read-model the container reduces over — rather than being simulated. [NeutralBackend]'s byte seeding uses it
     * directly, since bytes are the OS transfer's route, not the backend port's.
     */
    val client = backend.newClient()

    /**
     * The [Backend] port the composition stands on: the production `HttpBackend`, declaring the [appVersion] lever
     * per call and counting push registrations ([registerPushCount]) — see [WorldBackendPort].
     */
    val backendPort: Backend = WorldBackendPort(client, host, appVersion = { appVersion }, onDeviceConfig = { registerPushCount++ })

    /**
     * The marketing version this world's requests DECLARE (capability `app-update-required`) — an operator
     * lever, so a test can be a build the backend refuses. High by default, so every test that is not
     * about the gate is served.
     */
    var appVersion: String = "99.0"

    /** The app's manifest publisher — the composed backend service, which the world's extension-tier cycle shares. */
    val manifestPublisher: ManifestPublisher get() = core.backend.manifest

    // The membership: the world's own cells behind the honest config ports (`:adapter:generic:fake`, held to
    // `ConfigStoreContract` as the App-Group file store is). [configCell] is what operator actions write
    // directly; [configReadable] is what the [membershipUnreadable] lever moves.
    private val configCell = MutableStateFlow<EventConfig?>(null)
    private val configReadable = MutableStateFlow(true)
    val configSource: ConfigSource = inMemoryConfigSource(configCell, configReadable)

    // The write side of the same cells — the composed `LeaveEvent`/`JoinEvent` clear/set the config the
    // container reduces from.
    val configStore: ConfigStore = inMemoryConfigStore(configCell, configReadable)


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
     * clears the marker again (capability `receiving-photos`).
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
     * Force the membership to read as **unreadable** (capability `background-upload`) — the state a real
     * device is in before its first unlock after a boot, where the Keychain cannot be read at all.
     *
     * It is a lever rather than a property of [configCell] because a nullable cell can express only
     * *joined* or *absent*, which is exactly the modelling gap that mattered: the outcome three shipped
     * bugs turned on was the one no test could reach. Set it and a cycle takes [CycleGate.Skip].
     */
    var membershipUnreadable: Boolean
        get() = !configReadable.value
        set(value) {
            configReadable.value = !value
        }

    /**
     * The world's membership read as the shared `ConfigReader` port — the honest fake over the same cells,
     * so the [membershipUnreadable] lever surfaces as [ConfigRead.Unavailable] exactly as an unreadable
     * store answers, and writes are refused meanwhile. The gate itself is `uploadCore`'s — the world
     * carries no translation of its own.
     */
    private val configReader: ConfigReader = inMemoryConfigReader(configCell, configReadable)

    // ---- the composed APP graph (the REAL snapSyncApp, over the fakes) --------------------------

    /**
     * Where a minted event routes (the shell's `onEventMinted` lambda, supplied here because the
     * world IS the shell). The default JOINS it through the real path — its details load, then the composed
     * command bundle's commit, which runs `flow/Provision` — with the join gate's default choices, as a
     * member who confirms the gate unchanged would. The desktop inspector points it at the status host's
     * pending-join gate instead, so create shows the real join surface, exactly like the iOS app.
     *
     * It used to call the operator's [provision] lever, which writes the config cell directly: a create in the
     * world then skipped the Provision flow a device runs (`docs/testing.md`).
     */
    var onEventMinted: suspend (eventId: String) -> Unit = { eventId -> joinMinted(eventId) }

    private suspend fun joinMinted(eventId: String) {
        val event = core.joinEvent.loadDetails(eventId).toJoinLoad() as? JoinLoad.Found
            ?: error("a minted event $eventId has no loadable details — the mini-edge lost its marker")
        userCommands.commitJoin(
            eventId, event.name, event.startsAt, event.endsAt, event.deletesAt,
            CaptureCutoff(event.startsAt.at), CaptureCeiling(event.endsAt.at), Direction.Both, false,
        )
    }

    // Attestation (capability `privacy-security`), OFF unless [attests] says otherwise — see that
    // parameter for why the default is off and what turning it on is for.
    //
    // TWO BACKEND BEHAVIOURS THE WORLD STILL DOES NOT MODEL, stated rather than left to be discovered:
    // the token GATE itself (every mini-edge route answers without one), and `PUT /devices/<id>` answering
    // 401 for a device the backend holds no attestation record for. Modelling either means teaching the
    // mini-edge to gate, which is a change to what this harness models rather than a lever on it.
    //
    // The honest doubles come from `:adapter:generic:fake`; the LEVER is here, which is the split the
    // fake-honesty gate enforces. `renews = false` is the fake's own faithful default: a refresh falls
    // through to a full attestation, the path a device actually takes when the backend holds no record.
    //
    // What this makes reachable: the CREDENTIAL arm of `AppCore.installPushRegistration` — see
    // `:test:integration`'s `a_new_credential_re_registers_the_push_token_with_no_new_delivery`, which
    // needs a token change to happen at all.
    //
    // Attesting goes through the backend port like every other call: the mini-edge serves the three `/attest/…`
    // routes and mints for the in-memory integrity's attestations (`MiniEdgeAttest.kt`). The real `api/` verifies a
    // genuine App Attest attestation, which nothing off a device produces, so an attesting world needs the mini-edge.
    private val integrity: DeviceIntegrity = inMemoryDeviceIntegrity(available = attests)
    // The Keychain's attestation record: durable across a relaunch, as on a device.
    private val attestStore = inMemoryAttestStore()

    /**
     * The world's wall clock, in epoch millis — an operator **lever**, pinned at the epoch so nothing
     * depends on the host clock and every run is deterministic.
     *
     * Advance it to reach a time-gated behaviour. The one that needs it is the membership self-leave
     * (capability `manage-membership`), whose second, OFFLINE witness is the device's own persisted deadline:
     * with the clock at the epoch that witness can never be satisfied, so a `404` is always disbelieved —
     * which is the safe default a test must be able to step past deliberately.
     */
    var nowMillis: Long = 0L

    /**
     * The world's clock: the operator's pinned [nowMillis], read at every call, in UTC — so a rendered capture date
     * is the same on every machine the world runs on.
     */
    private val worldClock: app.snapsync.ports.Clock = object : app.snapsync.ports.Clock {
        override fun now() = kotlin.time.Instant.fromEpochMilliseconds(nowMillis)
        override fun timeZone() = TimeZone.UTC
    }

    /**
     * The app-driven uploader the world stands in with — one for the process, as on a device (it owns a
     * process-lifetime session there): inert, and counting the tail units the composed runner asks of it. Its
     * session's drain report reaches the core of the launch that is current.
     */
    val operatorEngine: OperatorUploadEngine = OperatorUploadEngine()

    /**
     * The job the composed app runs under — a child of the caller's [scope], so the caller still owns its
     * lifetime, and the one thing [relaunch] ends: a process death takes every collector and in-flight feature
     * launch with it, and nothing else.
     */
    private var appJob: Job = Job(scope.coroutineContext[Job])
    private var appScope: CoroutineScope = CoroutineScope(scope.coroutineContext + appJob)

    /**
     * What this launch's process set up first — its crash reporting started (`snapSyncProcess`, every root's first
     * act). Replaced by [relaunch], with the process.
     */
    var process: ProcessServices = appProcess()
        private set

    /**
     * The core AND the status host over it, from the shared host composition the iOS shell calls (spec
     * `docs/architecture.md`, "One shared composition"). The host is assembled on first touch of [statusHost], which
     * installs the permission-grant subscriptions, exactly as on the phone; a world whose [statusHost] is never
     * touched installs none (the desktop harness, whose operator plays the OS, and every background cold start).
     * The push registration is installed as this is composed, on every launch, as on the phone.
     * Replaced by [relaunch], which is the only thing that replaces it.
     */
    var composed: ComposedApp = snapSyncHost(appScope, process, appPorts(), cutoffFormatter())
        private set

    /**
     * One app process's per-process services, as its root sets them up: the world's [dsn] (by default it plays a
     * distributed build), no process metrics (a JVM has no provider), and no log writers installed — Kermit's writer
     * list is JVM-global, and a world is one of many processes in this JVM, so it does not own the logger.
     */
    private fun appProcess(): ProcessServices = snapSyncProcess(
        ProcessPorts(
            crashReporter = inMemoryCrashReporter(started = diagnosticsStarted, dumps = diagnosticsSent),
            processMetrics = ProcessMetrics.None,
            logSinks = emptyList(),
            files = inMemoryFiles(shared = null, private = privateFiles),
            clock = worldClock,
            entryContext = EntryContext.NoOp,
            dsn = dsn,
            bootLines = emptyList(),
            ownsGlobalLogger = false,
        ),
    )

    /**
     * The upload extension's per-process services — a separate process, with its own channel and nothing observed:
     * what the world reads is the app's reporting.
     */
    fun extensionProcess(): ProcessServices = snapSyncProcess(
        ProcessPorts(
            crashReporter = inMemoryCrashReporter(),
            processMetrics = ProcessMetrics.None,
            logSinks = emptyList(),
            files = inMemoryFiles(shared = null, private = null),
            clock = worldClock,
            entryContext = EntryContext.NoOp,
            dsn = WORLD_DSN,
            bootLines = emptyList(),
            ownsGlobalLogger = false,
        ),
    )

    /**
     * The ports one launch of the app composes over. A function, not a value, because [relaunch] composes a new
     * app over them: every port here is built over a cell the world holds as **durable** (see [relaunch]), so a
     * relaunched app finds what a relaunched process would find, and nothing else.
     */
    private fun appPorts(): AppPorts = AppPorts(
        // The world's platform-UI ports are in-memory doubles, so there is no real main thread to
        // reach. It takes the SAME lane as the composition scope rather than an unconfined default:
        // a lane that means "wherever the caller happened to be" is precisely what this law ends.
        uiLane = scope.coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext,
        // A device unlocked since boot: the background entry points record this, and nothing decides on it.
        processInfo = inMemoryProcessInfo(),
        // The device logs a dump reads back (capability `privacy-security`) — empty until an
        // operator seeds them, which is honest: a world has no device writing log files.
        deviceLogSource = inMemoryDeviceLogSource(deviceLogs),
        configSource = configSource,
        // The world's membership lives in-process in the config cell, so there is nothing to re-read.
        configRefresh = {},
        // The operator's table of holds: a wake's hold is visible there until it ends, and the operator expires it.
        backgroundTime = inMemoryBackgroundTime(backgroundTimeHolds),
        wake = wake,
        // The world composes an OS without the OS-driven mechanism, and no rig switch: both stated.
        extensionRegistry = inMemoryExtensionRegistry(),
        devControls = devControls,
        pushNotifications = pushNotifications,
        lifecycle = lifecycle,
        links = links,
        ui = ui,
        configStore = configStore,
        photoAccess = permission,
        // The operator plays the OS: nothing uploads on its own. A selection change updates the cell + N and
        // reaches the world uploader's inert units (`OperatorUploadEngine`), counted; the operator invokes the
        // cycle by hand, exactly like every other world trigger.
        gallery = gallery,
        // The SAME ledger the composed cycle writes, and the mini-edge's per-device listing the join-time
        // load seeds it from (capability `photo-sharing`).
        uploadRecord = UploadRecordPorts(ledger = ledgerBackend),
        downloadStore = downloadStore,
        // Staging root AND release, one port: the world's staged paths are built from the same
        // root the fake reports, exactly as the App-Group container is on device.
        stagedBytes = stagedBytes,
        download = download,
        appUpload = appUpload,
        // The backend: the production `HttpBackend` over the mini-edge (or the real `api/`), every need-shaped
        // service composed over it inside the core, as on the phone.
        backend = backendPort,
        manifestStore = manifestStore,
        integrity = integrity,
        attestStore = inMemoryAttestStore(),
        deviceIdentity = { ownDeviceId },
        appStoreUrl = WORLD_APP_STORE_URL,
        // The operator IS the engine: nothing auto-runs; a cycle happens when invoked by hand.
        appDrivenUpload = { operatorEngine },
        albumMapStore = albumMapStore,
        // Denylisted-album membership (capability `photo-sharing`) — the REAL policy
        // constant over the world's forgeable album membership, exactly as the shell wires it.
        // The push registration writes to the backend through the composed service, counted at the port (see
        // [registerPushCount]). A join in the world runs the REAL Provision flow — including this re-registration —
        // rather than a world-local provision body (`docs/testing.md`).
        push = PushPorts(
            tokens = pushTokens,
            record = pushRegistrationRecord,
        ),
        onEventMinted = { eventId -> onEventMinted(eventId) },
        log = logs.logger("World"),
    )



    /**
     * One launch's cutoff formatter, as a root builds it: the zone read once from the process's clock. The world's one
     * stated clock deviation: the core's clock is the process's — the operator's pinned [nowMillis] — while the
     * screen's "now" is the wall clock, so a status screen over the world renders dates a person would see.
     */
    private fun cutoffFormatter(): CutoffFormatter = CutoffFormatter(now = SystemClock::now, zone = worldClock.timeZone())

    /**
     * **Process death and a cold launch** (`docs/testing.md`, "The world relaunches its app over
     * its durable state"): end the running app — every collector and feature launch it owns — and compose a new
     * one, through the same shared host composition, over the same ports.
     *
     * What survives is exactly what survives on a device, and this is the one place that says so:
     * - **durable**: the ledger and the download store (App-Group databases); the membership, the manifest
     *   record and the last-registered push record (App-Group files); the attestation record (Keychain); the staged files (App-Group directory); the
     *   photo library, its albums and the album map; the backend; the operating system's upload jobs and its
     *   download session; the permission grant; the push token the OS re-delivers at every launch; the reporter's
     *   received dumps and the log;
     * - **process memory**, gone: the composed core and everything it holds (the version gate, the status
     *   sources' last reads, the create and rename latches, the cycle), the status host, and the download
     *   transport this process had realized.
     *
     * The new app installs nothing but its push registration until its host is touched, as a background relaunch
     * installs nothing else until a scene connects; that registration sees the token the OS re-delivers and
     * publishes it only if it differs from the last one the backend accepted.
     */
    fun relaunch() {
        appJob.cancel()
        appJob = Job(scope.coroutineContext[Job])
        appScope = CoroutineScope(scope.coroutineContext + appJob)
        download.relaunched()
        cycleOfThisLaunch = null
        uploadPortsOfThisLaunch = null
        process = appProcess()
        composed = snapSyncHost(appScope, process, appPorts(), cutoffFormatter())
    }

    /** The REAL app graph — the composition's core (never a world-local rebuild). */
    val core: AppCore get() = composed.core

    /** The status host the composition assembles over [core] — the one a phone would run over these ports. */
    val statusHost: StatusContainerHost get() = composed.host

    // Re-seated read handles: these ARE the composed graph's instances (never world-local rebuilds).
    val downloadController: DownloadController get() = core.downloadController
    val albumCoordinator: AlbumCoordinator get() = core.albumCoordinator
    val ownGallery: OwnDeviceGalleryStatusSource get() = core.gallery
    val ledgerCounts: ReadingLedgerCountsSource get() = core.ledgerCounts
    val creationStatus: MutableCreationStatusSource get() = core.creationStatus

    /** The rename status the real `RenameEvent` drives (capability `manage-membership`). */
    val renameStatus: MutableRenameStatusSource get() = core.renameStatus
    val downloadStatusSource: StoreDownloadStatusSource get() = core.downloadStatusSource
    val syncStatusSource: SyncStatusSource get() = core.syncStatusSource
    val userCommands: UserCommands get() = core.userCommands
    val joinEvent: JoinEvent get() = core.joinEvent

    /** The operator's foreground-refresh: pull the composed status sources (they update on `refresh()`). */
    suspend fun refreshStatus() = core.refreshStatusSources()

    /**
     * Operator lever (capability `photo-access`): the user changed the photo selection under a partial grant to
     * exactly [assetIds] — delivered through the gallery's registered handler, as the real observer delivers one:
     * the whole selection, with its resources. The observer must be open (host assembly opens it), or this fails
     * loudly. Deliver with the scheduler (e.g. `runCurrent`) before asserting — the core recounts N and updates the
     * cycle's scope cell.
     */
    fun changeSelection(vararg assetIds: String) {
        val wanted = assetIds.mapTo(mutableSetOf(), ::AssetId)
        gallery.changeSelection(gallery.current().filter { it.assetId in wanted })
    }

    // ---- device model + operator gallery actions ------------------------------------------------

    /**
     * Add one of the OWN device's photos to the gallery (default: a single primary JPEG).
     *
     * The origin facts default to an ordinary 12 MP camera photo, so an asset added without them is
     * **admitted** by the selection policy (capability `photo-sharing`) — see [addScreenshot] and
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
                assetId = AssetId(assetId),
                creationDate = creationDate,
                rawResources = resources,
                // NEUTRAL facts — the world forges what the platform would have interpreted, never a
                // PhotoKit bitmask (capability `sync-status`).
                facts = AssetFacts(
                    assetId = AssetId(assetId),
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

    // ---- selection-policy levers (capability `photo-sharing`) ---------------------------
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
     * `photo-sharing`). Kept as a lever because "a received GIF does not upload" is still the
     * operator-visible behaviour worth forging, even though the rule that used to produce it is gone.
     */
    suspend fun addGif(assetId: String, creationDate: String = DEFAULT_DATE) =
        addOwnAsset(
            assetId, creationDate,
            resources = listOf(primaryResource(filename = "giphy.gif", contentType = "image/gif")),
            pixelWidth = 480,
            pixelHeight = 270,
        )

    /** A Live Photo: a primary still and its paired motion, two resources that upload separately. */
    suspend fun addLivePhoto(assetId: String, creationDate: String = DEFAULT_DATE) =
        addOwnAsset(
            assetId, creationDate,
            resources = listOf(
                primaryResource(),
                RawResource(
                    role = ResourceRole.LIVE,
                    mimeContentType = "video/quicktime",
                    originalFilename = "IMG.MOV",
                    handle = Unit,
                ),
            ),
        )

    /** Arm the photo library's next enumeration to fail (a read that could not be made). */
    fun failNextEnumeration() {
        gallery.failNextEnumeration = true
    }

    /**
     * Bytes of an own asset's resources land on the backend with NO acknowledgement reaching the app — the upload
     * the OS completed while the process was gone. Through the backend's public byte route, so on either backend.
     */
    suspend fun landBytesWithoutAck(assetId: String) {
        val asset = gallery.current().single { it.assetId == AssetId(assetId) }
        asset.rawResources.forEach { raw ->
            // Only a resource with an upload role is ever sent; one without is not part of the asset's upload.
            val role = raw.role ?: return@forEach
            val key = uploadKey(asset.assetId, role, raw.originalFilename)
            neutral.upload(ownDeviceId, asset.assetId, ManifestResource(role, raw.mimeContentType, key, raw.originalFilename))
        }
    }

    /**
     * **An install upgraded from a build that predates per-asset byte release** (capability `receiving-photos`): a
     * confirmed import of [ref] whose resource rows, with their staged paths, survive, and whose files are still on
     * the staging "disk". Returns the staged paths.
     *
     * The one lever that writes app-private state, and deliberately so: no path in the current app can produce
     * this state — every import releases its bytes inline, which is the fix that shipped without the backlog pass
     * behind it — so the only honest way to reach it is to write what the older build left. What a test then
     * asserts is the staging directory's files, which are observable.
     */
    suspend fun seedLegacyStagedBacklog(ref: AssetRef): Set<String> {
        val primaryKey = "${ref.sourceAssetId}-primary.heic"
        val liveKey = "${ref.sourceAssetId}-live.mov"
        val paths = listOf("${stagedBytes.stagingRoot()}$primaryKey", "${stagedBytes.stagingRoot()}$liveKey")
        downloadStore.plan(
            ref,
            DEFAULT_DATE,
            listOf(
                PlannedResource(primaryKey, "https://world.edge/p", "primary", "image/heic", "IMG.HEIC"),
                PlannedResource(liveKey, "https://world.edge/l", "live", "video/quicktime", "IMG.MOV"),
            ),
        )
        downloadStore.markStaged(ref, primaryKey, paths[0])
        downloadStore.markStaged(ref, liveKey, paths[1])
        downloadStore.markImported(ref, AssetId("LOCAL-${ref.sourceAssetId}"))
        stagedFiles += paths
        return paths.toSet()
    }

    /** Append [text] to a process's device log — the log a diagnostic dump reads back. */
    fun appendDeviceLog(process: DeviceLogSource.Process, text: String) {
        deviceLogs.value = deviceLogs.value + (process to (deviceLogs.value[process].orEmpty() + text))
    }

    /** Put an existing own asset into an album some app made — e.g. `placeInAlbum("WhatsApp", "A1")`. */
    fun placeInAlbum(albumTitle: String, assetId: String) {
        gallery.placeIn(albumTitle, assetId)
    }

    /** Remove an own asset from the gallery (absent from the next cycle's walk, which deletes its in-window rows). */
    suspend fun removeAsset(assetId: String) {
        gallery.set(gallery.current().filterNot { it.assetId == AssetId(assetId) })
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

    // ---- backend-neutral inspection, levers and minted event ids -------------------------------
    //
    // Capability `docs/testing.md`, "Neutral inspection and minted event ids beside the mini-edge-only
    // surface". The reads and levers are [neutral]'s; the minted-id helpers are here, because a provision is
    // the world's own membership as much as the backend's.

    /** The backend-neutral reads and levers — the same calls over the mini-edge and the real backend. */
    val neutral: NeutralBackend by lazy { NeutralBackend(backend, client, host, backendPort, appVersion = { appVersion }) }

    /**
     * [provision], with the event id **the backend mints** — the only kind the real backend accepts, and the
     * one every backend-neutral test uses. The event is created and the own device joined through the
     * backend's public surface (the same `POST /events` and join the app sends); the rest is [provision]'s.
     */
    suspend fun provisionMinted(
        name: String = DEFAULT_EVENT_NAME,
        minPhotoDate: CaptureCutoff = captureCutoff(DEFAULT_CUTOFF),
        startsAt: EventStart = eventStart(DEFAULT_STARTS_AT),
        maxPhotoDate: CaptureCeiling = captureCeiling(DEFAULT_FAR_CEILING),
        endsAt: EventEnd? = null,
        direction: Direction = Direction.Both,
        saveToAlbum: Boolean = false,
    ): String {
        val eventId = neutral.createEvent(name, startsAt.at.iso, endsAt?.at?.iso)
        neutral.join(eventId, ownDeviceId)
        activate(eventId, name, minPhotoDate, startsAt, maxPhotoDate, endsAt, direction, saveToAlbum)
        return eventId
    }

    /**
     * [addForeignDevice] through the backend's public surface: the device joins [eventId] — or an event the
     * backend mints for it when [eventId] is `null` — uploads every resource's bytes where the app's uploader
     * addresses them, and publishes its manifest. Returns the event id.
     */
    suspend fun addForeignDeviceMinted(
        deviceId: String,
        assets: List<DeviceManifestAsset>,
        eventId: String? = null,
    ): String {
        val event = eventId ?: neutral.createEvent(DEFAULT_EVENT_NAME, DEFAULT_STARTS_AT, endsAt = null)
        neutral.join(event, deviceId)
        assets.forEach { asset ->
            asset.resources.forEach { resource -> neutral.upload(deviceId, asset.assetId, resource) }
        }
        neutral.publish(event, foreignManifest(deviceId, assets))
        return event
    }


    /**
     * Join/provision an event: register its marker, load the upload ledger from this device's stored-file
     * listing as a join does (a first join or a switch — never a re-provision of the joined event; capability
     * `photo-sharing`), and make its config present (the config gate lifts).
     * [minPhotoDate] is this device's per-membership capture-date cutoff (capability `photo-sharing`),
     * always present. It defaults to [DEFAULT_CUTOFF], which precedes [DEFAULT_DATE] so an asset added with
     * default arguments is in scope.
     *
     * [startsAt] is the EVENT's start date — the floor under [minPhotoDate]. It defaults to
     * [DEFAULT_STARTS_AT], which is at or before [DEFAULT_CUTOFF], so a default provision is a started
     * event whose floor binds nothing. Pass a FUTURE value to model an event that has not begun: the real
     * stack then admits no photo at all, and the status line reads not-started.
     */
    suspend fun provision(
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
        activate(eventId, name, minPhotoDate, startsAt, maxPhotoDate, endsAt, direction, saveToAlbum)
    }

    /** The membership half of a provision: the join-time load, then the config the container reduces from. */
    private suspend fun activate(
        eventId: String,
        name: String,
        minPhotoDate: CaptureCutoff,
        startsAt: EventStart,
        maxPhotoDate: CaptureCeiling,
        endsAt: EventEnd?,
        direction: Direction,
        saveToAlbum: Boolean,
    ) {
        // The join-time load, exactly as `flow/Provision` runs it — the composed instance, not a copy.
        loadShareSetFor(eventId)
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
     * the real backend leave (the composed leave service over the world's backend port — the same
     * `DELETE` the app fires, driving the store's RENAME-ONLY departed-mark), then clear the upload ledger
     * (the ledger is the current membership's share set — capability `photo-sharing`) and the config cell.
     * Deliberately an operator edge, not [UserCommands.leave]: the composed leave's backend notify is
     * fire-and-forget by design, and the operator's leave must be COMPLETE on return so world assertions
     * never race the DELETE (drive `core.userCommands.leave` to exercise the production ordering
     * instead). The world has no upload mechanism to stop — the operator is the producer. The gallery and
     * the **imported foreign photos** are retained (imported download rows are terminal / delete-proof),
     * so re-provisioning the same event afterwards still finds them suppressed; the own photos come back
     * `COMPLETED` through the join-time load, not through a retained ledger. Clearing
     * [configCell] is reactive, so the listing-backed status projection leaves the joined layer with
     * no rebuild. Backend outcomes (the device departed; the event and its bytes RETAINED until the
     * nightly sweep reclaims them, capability `event-lifetime`) are assertable on [store].
     */
    suspend fun leave() {
        core.downloadController.onLeaveOrSwitch()
        configCell.value?.eventId?.let { core.backend.leave.notifyLeaving(it) }
        ledgerBackend.clear()
        configCell.value = null
    }

    /**
     * The join-time load for a provision of [eventId]: the composed [AppCore.shareSetLoad] — the instance
     * `flow/Provision` runs — gated by the same `switchDecision` rule, so a re-provision of the joined event
     * loads nothing, as on a device.
     */
    private suspend fun loadShareSetFor(eventId: String) {
        if (switchDecision(configCell.value?.eventId, eventId) != SwitchDecision.Stay) core.shareSetLoad.load()
    }

    // ---- the upload cycle (the extension tier's shared assembly) --------------------------------

    /**
     * What the joined membership contributes (capability `photo-sharing`) — its participation
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
     * The real cycle — assembled by the SAME shared composition the device tiers call ([uploadCore],
     * `docs/architecture.md` "One shared composition"), over the world's fakes. Long-lived, as on
     * both tiers: the shared entry gate re-reads the membership on every `run()`, so a provision,
     * leave, or switch takes effect on the next cycle. The world carries no gate, reconciler, or
     * manifest-producer wiring of its own — a wiring difference from production is impossible.
     */
    val cycle: UploadCycle
        get() = cycleOfThisLaunch ?: uploadCore(appScope, process, uploadPorts).also { cycleOfThisLaunch = it }
    private var cycleOfThisLaunch: UploadCycle? = null

    /** What [cycle] is built over — the extension's handlers read its ledger and log from the same bundle. */
    val uploadPorts: UploadPorts
        get() = uploadPortsOfThisLaunch ?: buildUploadPorts().also { uploadPortsOfThisLaunch = it }
    private var uploadPortsOfThisLaunch: UploadPorts? = null

    private fun buildUploadPorts(): UploadPorts =
            UploadPorts(
                // The world composes the app graph on an OS without the OS-driven mechanism, so its one cycle
                // takes the app process's admission — the same resolution the device app engine gates on.
                process = UploaderProcess.App({ core.appUploadAdmission() }, { core.photoPermission.value }),
                config = configReader,
                deviceIdentity = { ownDeviceId },
                host = host,
                // A constant of the running build, as on device: read once, when the cycle is composed. The
                // metadata client above reads the [appVersion] lever per request instead, which is what lets a
                // test play an old build against the version gate.
                appVersion = appVersion,
                ledger = ledgerBackend,
                upload = platform,
                gallery = gallery,
                discovery = discovery,
                selectionScope = { core.selectionScope() },
                manifestStore = manifestStore,
                manifestPublisher = manifestPublisher,
                suppression = downloadStore,
                // The app tier's cycle: the same port and the same declared answer as the app graph's status
                // total (capability `photo-sharing`) — admit on doubt.
                albumManager = GalleryAlbums(gallery),
                albumLookupFailure = AlbumLookupFailure.AdmitOnDoubt,
                // Shared with the app graph, as the world's single-process stand-in for the App-Group map.
                albumCoordinator = core.albumCoordinator,
                // The mini-edge is unauthenticated; the world states its empty answer explicitly.
                token = { null },
                freshToken = { null },
            )

    /**
     * Run one cycle. The membership read, the gate, and the assembly are all
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
     * `docs/testing.md`). The operator plays the network: [outcome] is what the transfer turned out to
     * be, defaulting to an ordinary healthy one.
     *
     * A rejected outcome stages nothing and leaves the resource PENDING for retry — that is the world's
     * existing no-terminal-failure posture, not a new state. This is the only way to reproduce the shape of
     * the bug end-to-end: a `502` arrives here as a *successful* transfer of an error body, and staging it
     * would make it the store's truth forever (capability `receiving-photos`).
     */
    suspend fun stageAllDownloads(outcome: TransferOutcome = FakeDownload.HEALTHY) {
        val transport = downloadTransport ?: return
        transport.inFlight().forEach { transport.finish(it.description, outcome) }
        // Await the stagings the jobs launched, then the import the tail runs for them, so this action is complete
        // on return — the operator drives the world synchronously, and a racy stage would make every download
        // assertion flaky. The import is the tail's first unit (capability `receiving-photos`), requested as a staged
        // download does; it joins the tail those stagings already requested.
        core.downloadJobs.awaitOutstandingStagings()
        core.tail.runner.request(TailTrigger.DOWNLOAD_STAGED)
    }

    companion object {
        const val DEFAULT_DATE: String = "2026-06-01T10:00:00Z"

        /** The App Store page a world's build names — the update-required screen's one remedy. */
        const val WORLD_APP_STORE_URL: String = "https://apps.apple.com/app/id0000000000"

        /**
         * The world's default capture-date cutoff (capability `photo-sharing`). Strictly precedes
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
         * a name is not a representable state (capability `join-event`): `provision` used to take a
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
            val key = uploadKey(AssetId(assetId), ResourceRole.PRIMARY, filename)
            return DeviceManifestAsset(
                assetId = AssetId(assetId),
                creationDate = creationDate,
                resources = listOf(ManifestResource(ResourceRole.PRIMARY, contentType, key, filename)),
            )
        }
    }
}

/** The reporting destination a world's processes carry: a world plays a distributed build, which reports. */
private const val WORLD_DSN: String = "in-memory://world"
