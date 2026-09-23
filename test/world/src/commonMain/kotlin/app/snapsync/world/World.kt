package app.snapsync.world

import app.snapsync.feature.membership.toJoinLoad
import app.snapsync.model.JoinLoad
import app.snapsync.push.KtorPushHttpClient
import app.snapsync.ports.PushTokenSource
import app.snapsync.ports.PushHttpClient
import app.snapsync.compose.UploaderProcess
import app.snapsync.compose.AlbumLookupFailure
import app.snapsync.compose.AppCore
import app.snapsync.compose.onCredentialRejected
import app.snapsync.compose.AppPorts
import app.snapsync.compose.UploadRecordPorts
import app.snapsync.compose.UploadPorts
import app.snapsync.compose.snapSyncApp
import app.snapsync.compose.uploadCore
import app.snapsync.download.HttpEventUnionSource
import app.snapsync.eventcreation.HttpEventCreation
import app.snapsync.eventcreation.HttpEventRename
import app.snapsync.fake.inMemoryAttestClient
import app.snapsync.fake.inMemoryConfigReader
import app.snapsync.fake.inMemoryConfigSource
import app.snapsync.fake.inMemoryConfigStore
import app.snapsync.fake.inMemoryProtectedStorage
import app.snapsync.fake.inMemoryAttestKey
import app.snapsync.fake.inMemoryAttestStore
import app.snapsync.fake.inMemoryDeviceLogSource
import app.snapsync.fake.inMemoryDeviceManifestStore
import app.snapsync.fake.inMemoryDiagnosticsReporter
import app.snapsync.fake.inMemoryDownloadStore
import app.snapsync.fake.inMemoryLedgerStore
import app.snapsync.fake.inMemoryPhotoSelectionChangeSource
import app.snapsync.fake.inMemoryStagedBytes
import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.creation.MutableCreationStatusSource
import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.download.StoreDownloadStatusSource
import app.snapsync.feature.membership.JoinEvent
import app.snapsync.feature.membership.SwitchDecision
import app.snapsync.feature.membership.switchDecision
import app.snapsync.feature.membership.MutableRenameStatusSource
import app.snapsync.feature.status.OwnDeviceGalleryStatusSource
import app.snapsync.feature.status.ReadingLedgerCountsSource
import app.snapsync.feature.status.SyncStatusSource
import app.snapsync.feature.upload.UploadCycle
import app.snapsync.join.HttpEventJoin
import app.snapsync.join.HttpManifestPublisher
import app.snapsync.join.HttpEventDirectory
import app.snapsync.http.withCredentialInterceptor
import app.snapsync.membership.HttpDeviceFilesSource
import app.snapsync.membership.HttpLeaveNotifier
import app.snapsync.model.AssetFacts
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureDate
import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.DiagnosticDump
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.EventEnd
import app.snapsync.model.EventStart
import app.snapsync.model.ManifestResource
import app.snapsync.model.PermissionStatus
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
import app.snapsync.model.normalizeAssetId
import app.snapsync.model.resourcesFrom
import app.snapsync.model.selectionPolicyFor
import app.snapsync.model.toFacts
import app.snapsync.model.uploadKey
import app.snapsync.ports.AssetRef
import app.snapsync.ports.AttestClient
import app.snapsync.ports.AttestKey
import app.snapsync.model.CandidateRead
import app.snapsync.model.Candidate
import app.snapsync.ports.CandidateSource
import app.snapsync.ports.ConfigRead
import app.snapsync.ports.ConfigReader
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.ConfigStore
import app.snapsync.ports.CycleResult
import app.snapsync.ports.DeviceLogSource
import app.snapsync.ports.DeviceManifestStore
import app.snapsync.ports.LedgerStore
import app.snapsync.ports.PhotoAccessRequester
import app.snapsync.ports.StagedBytes
import app.snapsync.ports.TransferOutcome
import co.touchlab.kermit.Logger
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

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
    /**
     * The device-facing base every seam composes from — carrying **exactly one** version prefix, as a
     * real build's baked base does (capability `backend-deployment`). The mini-edge serves both
     * versions, so pointing this at the other prefix is how a test exercises the other one.
     */
    val host: String = "https://world.edge/api/v2",
    /**
     * The ledger this world composes over. Injectable for ONE reason: building a second world over the
     * same backend is how a **process boundary** is expressed here. Everything else a world holds is
     * in-memory and dies with it — which is exactly what a relaunch does to a device — so passing the
     * ledger on is what makes "did this fact survive the process?" an assertable question rather than a
     * device-only one (`changes/fix-lost-upload-acks`).
     */
    val ledgerBackend: LedgerStore = inMemoryLedgerStore(),
    /**
     * Whether this world can ATTEST (capability `device-attestation`). **Off by default**, which is the
     * world as it has always been: attestation is composed because `AppPorts` requires the seams, and
     * nothing exercises it.
     *
     * Turn it on to drive anything that depends on a CREDENTIAL CHANGE — the token-changed arm of
     * [AppCore.installPushRegistration] is the reason it exists. Off, `DeviceAttestation.refresh` returns
     * early without attesting, exactly as it does in the upload extension and on a simulator, so every
     * test that does not ask for this sees the behaviour it always saw.
     */
    val attests: Boolean = false,
) {

    // ---- world state + fakes (all public / inspectable) -----------------------------------------

    val store: BackendStore = BackendStore()

    /**
     * What the composed graph logged — an **inspection list**, the same family of operator rigging as the
     * failure levers (capability `harness-world-model`).
     *
     * It exists because a **severity** is part of some contracts rather than an implementation detail. The
     * read-only upload-ledger check reports its fault at `Error` precisely so it reaches crash reporting
     * as an event (capability `crash-reporting`), and the two states it must stay *silent* about are only
     * assertable by watching what it wrote. Asserting on the outcome instead is not available: the check
     * writes nothing, by contract.
     *
     * It ADDS a writer rather than replacing the platform one, so the desktop world harness's console
     * still shows everything it always did.
     */
    val logs: WorldLog = WorldLog()

    /** The world's gallery rigging around the honest raw-asset fake (see [WorldGallery]). */
    val gallery: WorldGallery = WorldGallery()
    /** The one read seam, straight off the world-owned cell — no enumerator composition to stand up. */
    val enumerator: CandidateSource = gallery.source

    /**
     * Operator read: the seam's candidates for [policy].
     *
     * [enumerator] is the **raw** world gallery, not the permission-aware wrapper the app's consumers
     * hold, so it always reports a readable library — a grant it cannot see cannot make it unreadable,
     * and the operator's failure lever throws instead. The empty branch is therefore unreachable rather
     * than a default, and it is spelled out so a reader does not mistake it for one.
     */
    suspend fun readCandidates(policy: SelectionPolicy): List<Candidate> =
        when (val read = enumerator.candidates(policy)) {
            is CandidateRead.Readable -> read.candidates
            CandidateRead.NotReadable -> emptyList()
        }
    val downloadStore: RecordingDownloadStore = RecordingDownloadStore(inMemoryDownloadStore())
    // The SAME ledger the composed cycle writes: this adapter records terminal outcomes into it, exactly
    // as both device adapters do, so the world exercises the real two-phase completion.
    val platform: FakeBackgroundTransfer = FakeBackgroundTransfer(store, ownDeviceId, ledgerBackend)
    // The cycle's library reads — the change feed and the id-scoped key resolve — over the in-memory gallery,
    // bound once beside the job queue exactly as the device roots bind `IosDiscovery`.
    val discovery: FakeUploadDiscovery = FakeUploadDiscovery(enumerator, gallery.contents) { permission.permission.value }
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
    /** The operator's own cell: the rigging owns what it wants to observe and passes it in, rather
     *  than reading it back off the honest double (capability `architecture-guards`). */
    val stagedFiles: MutableSet<String> = mutableSetOf()
    val stagedBytes: StagedBytes = inMemoryStagedBytes(stagedFiles)

    /**
     * Model the photo library **ingesting** a staged resource: it takes a resource's file when it
     * ingests it, which it does only as part of creating an asset, and it does so even when the process
     * that submitted the change then dies (capability `photo-download`).
     *
     * Removing the path from [stagedFiles] IS the entire model — that set is the disk — and this exists
     * to NAME the operation rather than to add a mechanism. Without a name a test spells it as a set
     * removal, and the next reader cannot tell "the library took these" from "the release pass ran",
     * which are the two states the adjudicator must never confuse.
     */
    fun consumeStagedBytes(vararg paths: String) {
        stagedFiles.removeAll(paths.toSet())
    }
    /** Push-registration writes that LANDED on the mini-edge (capability `push-registration`), counted at the
     *  port: the composed registration — its launch/rotation collector and the join's re-PUT — writes through
     *  it, so a test can assert the join path fired it. Counted after the write, so a count is a landed write. */
    var registerPushCount: Int = 0
        private set

    /** Counts the download backstop queued by the real flows — the Background flow arms it, and the backstop entry
     *  re-queues it however it ends — so a test can tell those entries apart (capability `photo-download`). Read
     *  off the world's backstop scheduler port, which the composition now reaches the platform through. */
    val backstopsScheduled: Int get() = backstopScheduler.scheduled

    /** The OS-delivered APNs token, as the world's shell delivers it (none until a test delivers one). */
    val pushTokens: PushTokenSource = PushTokenSource("sandbox")
    val manifestStore: DeviceManifestStore = inMemoryDeviceManifestStore()
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
    val albumManager: FakeAlbumManager = FakeAlbumManager(gallery.contents)
    val albumMapStore = app.snapsync.fake.inMemoryAlbumMapStore()

    /**
     * The one shared mini-edge client injected into every real common-Ktor seam — carrying the REAL
     * cross-cutting interceptor the device installs (`:adapter:generic:app`'s
     * [withCredentialInterceptor], the same function `darwinHttpClient` applies).
     *
     * Applying it here is what makes the version gate reach the screen in this harness rather than
     * being simulated: a `426` from the mini-edge travels the actual path — interceptor → the core's
     * `AppVersionGate` → the read-model the container reduces over. Without it the world would have to
     * write the gate directly, which would assert the harness's own wiring and nothing else.
     *
     * The declared version is an operator lever ([appVersion]) so a test can be an old build; the token
     * is null, because the mini-edge is unauthenticated and the world says so explicitly.
     */
    val client = miniEdgeClient(store).withCredentialInterceptor(
        token = { null },
        // Never fires while the token is null (a rejection must name a sent token), and bound to the production
        // route anyway so the world composes what the device composes.
        onRejected = { sent -> core.onCredentialRejected(sent) },
        appVersion = { appVersion },
        onVersionRefused = { minimum -> core.versionGate.refused(minimum) },
        onServed = { core.versionGate.served() },
    )

    /**
     * The marketing version this world's requests DECLARE (capability `min-app-version`) — an operator
     * lever, so a test can be a build the backend refuses. High by default, so every test that is not
     * about the gate is served.
     */
    var appVersion: String = "99.0"

    /** The `:adapter:generic:app` enrollment PUT over the mini-edge — the ONE `Enrollment` impl (the
     *  world's byte-identical copy died at step 10, closing the deletion ledger's last row). */
    val manifestPublisher: HttpManifestPublisher = HttpManifestPublisher(client, host)
    val eventJoin: HttpEventJoin = HttpEventJoin(client, host)
    /**
     * The REAL backend-leave seam (the `:adapter:generic:app` [HttpLeaveNotifier] over the mini-edge),
     * bound to this world's own device — which is what the port means (see
     * [app.snapsync.ports.LeaveNotifier]): "this device is leaving". A test that must speak for a
     * DIFFERENT member binds its own instance to that id, so the substitution is visible where it is
     * made rather than hidden in an argument at a call site.
     */
    private val leaveNotifier = HttpLeaveNotifier(client, host) { ownDeviceId }

    // The membership: the world's own cells behind the honest config ports (`:adapter:generic:fake`, held to
    // `ConfigStoreContract` as the App-Group file store is). [configCell] is what operator actions write
    // directly; [configReadable] is what the [membershipUnreadable] lever moves.
    private val configCell = MutableStateFlow<EventConfig?>(null)
    private val configReadable = MutableStateFlow(true)
    val configSource: ConfigSource = inMemoryConfigSource(configCell, configReadable)

    // The write side of the same cells — the composed `LeaveEvent`/`JoinEvent` clear/set the config the
    // container reduces from.
    val configStore: ConfigStore = inMemoryConfigStore(configCell, configReadable)

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
     * world then skipped the Provision flow a device runs (capability `harness-world-model`).
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

    /**
     * The world's photo-access requester: the honest fake's, over the same cell as [permission]. Asked while
     * undetermined, the user grants; once the grant is determined a request changes nothing, as on a device.
     * No limited-library picker exists off device; the selection is changed by the operator lever
     * [changeSelection] instead, which is the same thing the real picker's outcome amounts to.
     */
    val requester: PhotoAccessRequester = permission.requester

    // Attestation (capability `device-attestation`), OFF unless [attests] says otherwise — see that
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
    private val attestKey: AttestKey = inMemoryAttestKey(supported = attests)
    private val attestClient: AttestClient = inMemoryAttestClient(mints = attests)

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

    /** One engine for the process, as on a device (it owns a process-lifetime session there). */
    /** The app-driven uploader the world stands in with — inert, and counting the OS wakes that reach it. */
    val operatorEngine: OperatorUploadEngine = OperatorUploadEngine()

    /**
     * The REAL app graph (spec `module-architecture`, "One shared composition"): the same
     * [snapSyncApp] the iOS shell calls, over the world's ports. Features, flows, and the user-tap
     * command bundle all live on this — the world adds only operator levers and inspection around it.
     */
    /** The download backstop's scheduler — counted, never run (the operator plays the OS). */
    val backstopScheduler: CountingBackstopScheduler = CountingBackstopScheduler()

    val core: AppCore = snapSyncApp(
        scope = scope,
        ports = AppPorts(
            // The world's platform-UI ports are in-memory doubles, so there is no real main thread to
            // reach. It takes the SAME lane as the composition scope rather than an unconfined default:
            // a lane that means "wherever the caller happened to be" is precisely what this law ends.
            uiLane = scope.coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext,
            diagnosticsReporter = inMemoryDiagnosticsReporter(
                started = diagnosticsStarted,
                sent = diagnosticsSent,
                isConfigured = true,
            ),
            // A device unlocked since boot: the background entry points record this, and nothing decides on it.
            protectedStorage = inMemoryProtectedStorage(),
            // The device logs a dump reads back (capability `diagnostic-logging`) — empty until an
            // operator seeds them, which is honest: a world has no device writing log files.
            deviceLogSource = inMemoryDeviceLogSource(deviceLogs),
            configSource = configSource,
            // The world's membership lives in-process in the config cell, so there is nothing to re-read.
            configRefresh = {},
            backstopScheduler = backstopScheduler,
            // The world composes an OS without the OS-driven mechanism, and no rig switch: both stated.
            extensionRegistration = { null },
            uploaderPin = { null },
            configStore = configStore,
            photoAccess = permission,
            photoAccessRequester = requester,
            selectionChanges = inMemoryPhotoSelectionChangeSource(selectionChangesCell),
            // The operator plays the OS: nothing auto-runs. A selection change updates the cell + N; the
            // operator then invokes the cycle by hand, exactly like every other world trigger. That used
            // to be an inert `pumpSelectionChanged = {}` port here; it is now the world mechanism's own
            // stated answer to the trigger (`OperatorUploadEngine`), which is where a mechanism's
            // response to a kick belongs.
            candidateSource = enumerator,
            // The SAME ledger the composed cycle writes, and the mini-edge's per-device listing the join-time
            // load seeds it from (capability `upload-state-reconciliation`).
            uploadRecord = UploadRecordPorts(
                ledger = ledgerBackend,
                files = deviceFiles,
            ),
            downloadStore = downloadStore,
            assetPresence = assetPresence,
            // Staging root AND release, one port: the world's staged paths are built from the same
            // root the fake reports, exactly as the App-Group container is on device.
            stagedBytes = stagedBytes,
            importer = importer,
            newDownloadTransport = { transportHost ->
                FakeDownloadTransport(transportHost, stagedFiles).also { downloadTransport = it }
            },
            union = unionSource,
            directory = HttpEventDirectory(client, host),
            eventJoin = eventJoin,
            manifestStore = manifestStore,
            eventCreation = HttpEventCreation(client, host),
            eventRename = HttpEventRename(client, host),
            attestKey = attestKey,
            attestClient = attestClient,
            attestStore = inMemoryAttestStore(),
            deviceIdentity = { ownDeviceId },
            clock = { kotlin.time.Instant.fromEpochMilliseconds(nowMillis) },
            // The operator IS the engine: nothing auto-runs; a cycle happens when invoked by hand.
            appDrivenUpload = { operatorEngine },
            albumManager = albumManager,
            albumMapStore = albumMapStore,
            // Denylisted-album membership (capability `photo-selection-policy`) — the REAL policy
            // constant over the world's forgeable album membership, exactly as the shell wires it.
            leaveNotifier = leaveNotifier,
            // The push registration writes to the mini-edge, counted (see [registerPushCount]). A join in the
            // world runs the REAL Provision flow now — including this re-registration — rather than a
            // world-local provision body (capability `harness-world-model`).
            pushHttpClient = object : PushHttpClient {
                private val inner = KtorPushHttpClient(client)
                override suspend fun put(url: String, jsonBody: String): Result<Unit> =
                    inner.put(url, jsonBody).also { registerPushCount++ }

                override suspend fun post(url: String): Result<Unit> = inner.post(url)
            },
            backendHost = host,
            pushTokens = pushTokens,
            onEventMinted = { eventId -> onEventMinted(eventId) },
            log = logs.logger("World"),
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
        // `limited-photo-access` — deferring it would need a re-fetch by identifier later, an autonomous
        // library fetch the read discipline forbids). Unscoped here because the selection IS the scope.
        val wanted = assetIds.toSet()
        selectionChangesCell.emit(
            gallery.current().filter { normalizeAssetId(it.assetId) in wanted }.flatMap { resourcesFrom(listOf(it)) },
        )
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

    /** Remove an own asset from the gallery (absent from the next cycle's walk, which deletes its in-window rows). */
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
     * Join/provision an event: register its marker, load the upload ledger from this device's stored-file
     * listing as a join does (a first join or a switch — never a re-provision of the joined event; capability
     * `upload-state-reconciliation`), and make its config present (the config gate lifts).
     * [minPhotoDate] is this device's per-membership capture-date cutoff (capability `photo-selection-policy`),
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
     * the real backend leave (the `:adapter:generic:app` `HttpLeaveNotifier` over the mini-edge — the same
     * `DELETE` the app fires, driving the store's RENAME-ONLY departed-mark), then clear the upload ledger
     * (the ledger is the current membership's share set — capability `sync-ledger`) and the config cell.
     * Deliberately an operator edge, not [UserCommands.leave]: the composed leave's backend notify is
     * fire-and-forget by design, and the operator's leave must be COMPLETE on return so world assertions
     * never race the DELETE (drive `core.userCommands.leave` to exercise the production ordering
     * instead). The world has no upload mechanism to stop — the operator is the producer. The gallery and
     * the **imported foreign photos** are retained (imported download rows are terminal / delete-proof),
     * so re-provisioning the same event afterwards still finds them suppressed; the own photos come back
     * `COMPLETED` through the join-time load, not through a retained ledger. Clearing
     * [configCell] is reactive, so the listing-backed status projection leaves the joined layer with
     * no rebuild. Backend outcomes (the device departed; the event and its bytes RETAINED until the
     * nightly sweep reclaims them, capability `scheduled-cleanup`) are assertable on [store].
     */
    suspend fun leave() {
        core.downloadController.onLeaveOrSwitch()
        configCell.value?.eventId?.let { leaveNotifier.notifyLeaving(it) }
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
     * The real cycle — assembled by the SAME shared composition the device tiers call ([uploadCore],
     * spec `module-architecture` "One shared composition"), over the world's fakes. Long-lived, as on
     * both tiers: the shared entry gate re-reads the membership on every `run()`, so a provision,
     * leave, or switch takes effect on the next cycle. The world carries no gate, reconciler, or
     * manifest-producer wiring of its own — a wiring difference from production is impossible.
     */
    val cycle: UploadCycle by lazy { uploadCore(scope, uploadPorts) }

    /** What [cycle] is built over — the extension tier's inbound port reads its ledger and log from the same bundle. */
    val uploadPorts: UploadPorts by lazy {
            UploadPorts(
                diagnosticsReporter = inMemoryDiagnosticsReporter(),
                // The world composes the app graph on an OS without the OS-driven mechanism, so its one cycle
                // takes the app process's admission — the same resolution the device app engine gates on.
                process = UploaderProcess.App { core.appUploadAdmission() },
                config = configReader,
                deviceIdentity = { ownDeviceId },
                host = host,
                // A constant of the running build, as on device: read once, when the cycle is composed. The
                // metadata client above reads the [appVersion] lever per request instead, which is what lets a
                // test play an old build against the version gate.
                appVersion = appVersion,
                ledger = ledgerBackend,
                transfer = platform,
                discovery = discovery,
                selectionScope = { core.selectionScope() },
                manifestStore = manifestStore,
                manifestPublisher = manifestPublisher,
                suppression = downloadStore,
                // The app tier's cycle: the same port and the same declared answer as the app graph's status
                // total (capability `photo-selection-policy`) — admit on doubt.
                albumManager = albumManager,
                albumLookupFailure = AlbumLookupFailure.AdmitOnDoubt,
                // Shared with the app graph, as the world's single-process stand-in for the App-Group map.
                albumCoordinator = core.albumCoordinator,
                // The mini-edge is unauthenticated; the world states its empty answer explicitly.
                token = { null },
            )
    }

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
