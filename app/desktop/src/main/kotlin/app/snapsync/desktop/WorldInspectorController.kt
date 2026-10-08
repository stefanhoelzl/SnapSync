package app.snapsync.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.snapsync.jvm.JvmApp
import app.snapsync.jvm.JvmMocks
import app.snapsync.mock.BackendCall
import app.snapsync.mock.BuildInfoMock
import app.snapsync.mock.DeclaredVersion
import app.snapsync.mock.DownloadSessionMock
import app.snapsync.mock.LibraryAssets
import app.snapsync.model.AssetId
import app.snapsync.model.CandidateRead
import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.DeviceRefusal
import app.snapsync.model.EventPhotoSet
import app.snapsync.model.FileArea
import app.snapsync.model.GalleryAccess
import app.snapsync.model.InviteLinkHints
import app.snapsync.model.Layer
import app.snapsync.model.ManifestResource
import app.snapsync.model.NetworkAccess
import app.snapsync.model.RawAsset
import app.snapsync.model.ResourceRole
import app.snapsync.model.SELECTION_CALIBRATION
import app.snapsync.model.StoreKind
import app.snapsync.model.StoreLink
import app.snapsync.model.TransferOutcome
import app.snapsync.model.UiIntent
import app.snapsync.model.UiState
import app.snapsync.model.UploadError
import app.snapsync.model.WakeId
import app.snapsync.model.noContribution
import app.snapsync.model.runCatchingCancellable
import app.snapsync.model.selectionPolicyFor
import app.snapsync.model.uploadKey
import app.snapsync.ports.ExpiringCompletion
import app.snapsync.services.config.CONFIG_FILE_NAME
import app.snapsync.services.gallery.GalleryAlbums
import app.snapsync.services.gallery.GalleryCandidateSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDateTime
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlin.time.Duration.Companion.seconds

/**
 * The single mutation path for the full-stack world harness (test equipment — no tests): every inspector control
 * goes through a named method here, never an inline mutation in a composable.
 *
 * The app is the one the JVM root composes (`:app:jvm`'s [JvmApp] — the same `snapSyncHost` the iOS root calls) over
 * the device as mocks ([JvmMocks]). The phone pane shows what the app showed on its `Ui` port — [shown] — and hands
 * the screen's taps back to it as intents ([tap]): it builds no status host of its own. Every control plays a mock's
 * operator face — the person holding the phone, the operating system, the backend, another member — and every
 * mutation ends with the operating system's foreground entry, as the phone refreshes its status on foreground.
 *
 * The device is a **live stateful stack** that cannot be un-deposited, so presets build a **fresh** app over fresh
 * mocks and bump [generation]; the root keys the phone pane on it.
 */
class WorldInspectorController(
    private val scope: CoroutineScope,
    /** The composition lane [scope] runs on. */
    private val lane: CoroutineDispatcher,
) {

    // ---- the current app + its device (rebuilt on preset) ------------------------------------------

    var app: JvmApp<JvmMocks> = compose()
        private set

    private val mocks: JvmMocks get() = app.durable

    /** Bumped only when [app] is replaced (presets); the phone pane is keyed on this. */
    var generation: Int by mutableIntStateOf(0)
        private set

    /** The inspector's render state, recomputed after every mutation. */
    var snapshot: InspectorSnapshot by mutableStateOf(InspectorSnapshot.EMPTY)
        private set

    /** What the app last showed on its screen — the phone pane renders this, and nothing else. */
    val shown: StateFlow<UiState?> get() = mocks.screen.operator.shown

    /** A person's tap on the phone pane, handed to the app as the intent a tap produces. */
    fun tap(intent: UiIntent) {
        mocks.screen.operator.tap(intent)
        scope.launch { afterMutation() }
    }

    /** What the next permission dialog resolves to. */
    var armedGrants: Boolean by mutableStateOf(true)
        private set

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

    /** The photos the operator put in the library — anything else in it arrived by import. */
    private val ownAssetIds = mutableSetOf<String>()

    /** The current app's own collectors (the share sheet's echo), cancelled when a preset replaces it. */
    private var appWatchers: Job? = null

    init {
        launchMutation { start() }
    }

    // ---- the OS invocations -----------------------------------------------------------------------

    /**
     * One extension invocation — the upload `process()` cycle — then the silent push a member's upload makes the
     * backend send, whose receiver is the download reconcile.
     */
    fun invokeExtension() = launchMutation {
        val result = mocks.extensionHost.operator.process()
        appendConsole("invoke: upload cycle → $result")
        joinedEventId()?.let { event ->
            mocks.pushService.operator.deliverMessage(mapOf("eventId" to event), NoCompletion)
            appendConsole("silent push → $event")
        }
    }

    /** The operating system wakes the app for its heartbeat. */
    fun fireHeartbeat() = launchMutation { mocks.wakes.operator.fire(WakeId.Heartbeat, NoCompletion) }

    // ---- membership -------------------------------------------------------------------------------

    fun setPermission(status: GalleryAccess) = launchMutation { mocks.library.operator.access = status }

    /**
     * The device's network as the operating system reports it to the app (capability `sync-status`). The app tells the
     * member only after the network watch's grace, so a notice appears a few seconds after the lever, as on a phone.
     */
    fun setNetwork(access: NetworkAccess) = launchMutation { mocks.connectivity.operator.access = access }

    fun armNextRequest(grants: Boolean) {
        armedGrants = grants
        mocks.library.operator.requestAnswer = if (grants) GalleryAccess.GRANTED else GalleryAccess.DENIED
    }

    /**
     * Create an event the way a person does — the create form's intent — with a chosen window. The create opens the
     * app's join gate on the phone pane, exactly like the iOS app. The operator picks past or future so BOTH sides of
     * the event-start floor are drivable through the real stack.
     */
    fun createEvent(name: String, startsAt: String, endsAt: String) = launchMutation {
        mocks.screen.operator.tap(
            UiIntent.CreateEvent(name, LocalDateTime.parse(startsAt), LocalDateTime.parse(endsAt)),
        )
    }

    /** The inspector's Leave button — the phone's Leave, confirmed. */
    fun leaveEvent() = launchMutation { mocks.screen.operator.tap(UiIntent.LeaveEvent) }

    // ---- gallery ---------------------------------------------------------------------------------

    fun addAsset() = addOwn(LibraryAssets.photo("own-${ownAssetSeq++}"))

    fun removeAsset(assetId: String) = launchMutation { mocks.library.operator.remove(AssetId(assetId)) }

    // Selection policy (capability `photo-sharing`): an asset the policy decides on — most it EXCLUDES, so the operator
    // can watch it land in the gallery and then *not* upload and *not* enter the union, and see that N does not inflate.
    fun addPolicyAsset(kind: PolicyAsset) = addOwn(kind.build("${kind.prefix}-${ownAssetSeq++}"))

    /** An ordinary photo that WhatsApp also saved into its album — excluded by the album denylist. */
    fun addWhatsAppAlbumPhoto() = launchMutation {
        val id = "wa-${ownAssetSeq++}"
        addToLibrary(LibraryAssets.photo(id))
        mocks.library.operator.placeIn("WhatsApp", id)
    }

    private fun addOwn(asset: RawAsset) = launchMutation { addToLibrary(asset) }

    private fun addToLibrary(asset: RawAsset) {
        ownAssetIds += asset.assetId.value
        mocks.library.operator.add(asset)
    }

    // ---- backend ---------------------------------------------------------------------------------

    /** Another member joins the joined event with one complete photo. */
    fun injectForeignDevice() = launchMutation {
        val eventId = joinedEventId() ?: return@launchMutation
        val deviceId = "foreign-${foreignDeviceSeq++}"
        injectMember(eventId, deviceId)
        appendConsole("injected $deviceId with one complete asset into $eventId")
    }

    // ---- upload jobs -----------------------------------------------------------------------------

    fun completeJob(key: String) = launchMutation { mocks.uploadQueue.operator.completeJob(key) }

    fun failJob(key: String, error: UploadError) = launchMutation { mocks.uploadQueue.operator.failJob(key, error) }

    /** The OS lands one of the app uploader's transfers — its end reported to the app as it happens. */
    fun completeAppUpload(key: String) = launchMutation { mocks.uploadSession.operator.complete(key) }

    fun setJobLimit(limit: Int) = launchMutation { mocks.uploadQueue.operator.jobLimit = limit.coerceAtLeast(0) }

    // ---- downloads -------------------------------------------------------------------------------

    /**
     * The OS finishes every in-flight transfer as [outcome]. The failure outcomes are the operator playing a bad
     * network: a `502` with an error body — which `URLSession` reports as a *successful* transfer, the shape of the
     * shipped bug (capability `receiving-photos`) — or a body short of its `Content-Length`. Either is rejected, nothing
     * stages, and the downloads stay pending.
     */
    fun stageAllDownloads(outcome: TransferOutcome = DownloadSessionMock.HEALTHY) = launchMutation {
        val session = mocks.downloads.operator
        session.inFlight().forEach { session.finish(it.description, outcome) }
    }

    // ---- failure levers --------------------------------------------------------------------------

    fun setBackendOffline(offline: Boolean) = launchMutation { mocks.backend.operator.offline = offline }

    /**
     * The backend refuses this phone as not genuine for [reason], or stops (`null`) — capability `privacy-security`, "A
     * refused phone is told why". The app learns of it at its next attestation: a wake, or a tap on Create or Join.
     */
    fun setRefusedAttestation(
        reason: DeviceRefusal?,
    ) = launchMutation { mocks.backend.operator.refuseAttestation = reason }

    /**
     * The membership made **unreadable** (capability `background-upload`) — the state a real device is in before its
     * first unlock after a boot. A lever here because it is otherwise unreachable by a reviewer.
     */
    fun setMembershipUnreadable(unreadable: Boolean) =
        launchMutation { mocks.disk.operator.deny(FileArea.SHARED, CONFIG_FILE_NAME, unreadable) }

    /**
     * Hold one of [HOLDS] unanswered, or answer it — the only way to review a screen the app shows only while it waits:
     * a backend call (the join gate loading or committing, the create in flight), or the library's enumeration (the
     * status screen before anything is counted).
     */
    fun setHeld(hold: String, held: Boolean) = launchMutation {
        val call = BackendCall.ofKey(hold)
        when {
            call != null && held -> mocks.backend.operator.hold(call)
            call != null -> mocks.backend.operator.release(call)
            held -> mocks.library.operator.holdEnumeration()
            else -> mocks.library.operator.releaseEnumeration()
        }
    }

    /** A one-shot lever: [change] the device's mocks, then say what was done in the console as [said]. */
    fun lever(said: String, change: JvmMocks.() -> Unit) = launchMutation {
        mocks.change()
        appendConsole(said)
    }

    // ---- presets (a fresh app over a fresh device) -----------------------------------------------

    fun presetClean() = installFresh("clean") { }

    fun presetEnrolled() = installFresh("enrolled") {
        joinNewEvent()
        addToLibrary(LibraryAssets.photo("own-a1"))
        addToLibrary(LibraryAssets.photo("own-a2"))
    }

    fun presetFreshJoin() = installFresh("fresh join") {
        joinNewEvent()
        addToLibrary(LibraryAssets.photo("own-a1"))
    }

    /**
     * An own photo whose bytes the backend already holds (as if uploaded by an earlier install), THEN a join: the
     * join-time load finds them, so the next invoke uploads nothing new.
     */
    fun presetReprovisionDedup() = installFresh("re-provision (dedup)") {
        val photo = LibraryAssets.photo("own-a1")
        addToLibrary(photo)
        photo.rawResources.forEach { raw ->
            val role = raw.role ?: return@forEach
            mocks.backend.operator.deposit(mocks.ownDeviceId, photo.assetId, role, raw.originalFilename)
        }
        joinNewEvent()
    }

    fun presetForeignDownload() = installFresh("foreign download") {
        val eventId = joinNewEvent() ?: return@installFresh
        injectMember(eventId, "foreign-${foreignDeviceSeq++}")
    }

    private fun installFresh(label: String, setup: suspend () -> Unit) {
        scope.launch {
            app = compose()
            ownAssetSeq = 0
            foreignDeviceSeq = 0
            injectedDeviceIds.clear()
            ownAssetIds.clear()
            generation++ // re-bind the phone pane to the new app's screen
            start()
            setup()
            appendConsole("preset: $label")
            afterMutation()
        }
    }

    // ---- shared plumbing -------------------------------------------------------------------------

    /** A fresh app over a fresh device, as the JVM root composes it. */
    private fun compose(): JvmApp<JvmMocks> {
        val build = BuildInfoMock(
            uploadHost = BACKEND_BASE,
            declaredVersion = DeclaredVersion(APP_VERSION),
            dsn = DSN,
            store = StoreLink(APP_STORE_URL, StoreKind.APP_STORE),
            apnsEnvironment = "sandbox",
        )
        val mocks = JvmMocks(inviteLinkHints = InviteLinkHints.Ignored, network = null)
        return JvmApp(scope, lane, mocks) { device ->
            device.adapters(
                build,
                attests = true,
                backend = device.backend.port(build.declaredVersion),
                logSinks = emptyList(),
            )
        }
    }

    /**
     * The app's first moments on a phone the person is holding: the screen is built (host assembly, and every state it
     * shows from then on), the app becomes active, and the share sheet's hand-offs are echoed to the clipboard.
     */
    private fun start() {
        app.composed.host
        mocks.screen.operator.live()
        mocks.lifecycle.operator.foreground()
        mocks.library.operator.requestAnswer = if (armedGrants) GalleryAccess.GRANTED else GalleryAccess.DENIED
        appWatchers?.cancel()
        appWatchers = scope.launch {
            launch {
                mocks.systemUi.operator.shared.collect { shared ->
                    shared.lastOrNull()?.let { url ->
                        runCatchingCancellable { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null) }
                        appendConsole("share invite → $url")
                    }
                }
            }
            // The snapshot follows what the app does on its own — an import landing, a screen change — as well as the
            // operator's actions, so the inspector never shows a library the app has since changed.
            launch { mocks.library.operator.contents.collect { snapshot = snapshotNow() } }
            launch { shown.collect { snapshot = snapshotNow() } }
        }
    }

    /**
     * Create an event and confirm its join on the phone, as a person does — answers the joined event, or `null` when the
     * app never reached it.
     */
    private suspend fun joinNewEvent(): String? {
        mocks.screen.operator.tap(
            UiIntent.CreateEvent(PRESET_EVENT, LocalDateTime.parse(PAST_START), LocalDateTime.parse(PAST_END)),
        )
        withTimeoutOrNull(AWAIT) { shown.first { (it?.layer as? Layer.JoiningEvent)?.range != null } }
            ?: return null.also { appendConsole("preset: the join gate never opened") }
        mocks.screen.operator.tap(UiIntent.ConfirmJoin)
        return withTimeoutOrNull(AWAIT) { shown.first { it?.layer is Layer.Joined } }
            .let { (it?.layer as? Layer.Joined)?.membership?.eventId }
    }

    /** Another member, through the backend: it joins, its photo's bytes land, and it publishes its manifest. */
    private suspend fun injectMember(eventId: String, deviceId: String) {
        val backend = mocks.backend.port()
        val asset = AssetId("$deviceId-a1")
        val key = uploadKey(asset, ResourceRole.PRIMARY, FOREIGN_FILENAME)
        backend.joinEvent(null, eventId, deviceId)
        mocks.backend.operator.deposit(deviceId, asset, ResourceRole.PRIMARY, FOREIGN_FILENAME)
        backend.publishManifest(
            null,
            eventId,
            deviceId,
            DeviceManifest(
                deviceId,
                listOf(
                    DeviceManifestAsset(
                        asset,
                        LibraryAssets.DEFAULT_DATE,
                        listOf(ManifestResource(ResourceRole.PRIMARY, "image/heic", key, FOREIGN_FILENAME)),
                    ),
                ),
            ),
        )
        injectedDeviceIds += deviceId
    }

    private fun joinedEventId(): String? = (shown.value?.layer as? Layer.Joined)?.membership?.eventId

    /** Run a mutation, then refresh and recompute the snapshot (no app rebuild). */
    private fun launchMutation(body: suspend () -> Unit) {
        scope.launch {
            body()
            afterMutation()
        }
    }

    /** The operating system's foreground entry — the phone's own status refresh — then the inspector's snapshot. */
    private suspend fun afterMutation() {
        mocks.lifecycle.operator.foreground()
        snapshot = snapshotNow()
    }

    private suspend fun snapshotNow(): InspectorSnapshot {
        val library = mocks.library.operator
        val joined = (shown.value?.layer as? Layer.Joined)?.membership
        // What the selection policy would exclude (capability `photo-sharing`) — the REAL policy over the REAL
        // enumeration of the library, so the row badge cannot drift from what the cycle does. Echo is reported
        // separately (an imported photo), so the echo set is empty here; the album lookup is real.
        val reads = mocks.library.port()
        val policy = joined?.let { config ->
            selectionPolicyFor(
                config = config,
                suppressedAssetIds = { emptySet() },
                albumExcludedAssetIds = { GalleryAlbums(reads).assetIdsInAlbums(SELECTION_CALIBRATION, it) },
            )
        } ?: noContribution()
        val candidates = when (val read = GalleryCandidateSource(reads).candidates(policy)) {
            is CandidateRead.Readable -> read.candidates
            CandidateRead.NotReadable -> emptyList()
        }
        val admitted = EventPhotoSet(policy) { candidates }.assets().mapTo(mutableSetOf()) { it.facts.assetId }
        val policyExcluded = candidates.mapTo(mutableSetOf()) { it.facts.assetId } - admitted
        val galleryRows = library.current().map {
            GalleryRow(
                it.assetId.value,
                imported = it.assetId.value !in ownAssetIds,
                policyExcluded = it.assetId in policyExcluded,
            )
        }
        val backend = (listOf(mocks.ownDeviceId) + injectedDeviceIds).map { id ->
            DeviceObjects(
                deviceId = id,
                own = id == mocks.ownDeviceId,
                objects = mocks.backend.operator.objectsOf(id).sorted(),
            )
        }
        val queue = mocks.uploadQueue.operator
        val jobs = queue.liveJobKeys().map { key -> JobRow(key, attempts = queue.created.count { it.filename == key }) }
        val session = mocks.uploadSession.operator
        val appUploads = session.liveKeys().map { key -> JobRow(key, attempts = session.created.count { it == key }) }
        val downloads = mocks.downloads.operator.inFlight().map {
            DownloadRow(
                url = it.url,
                description = it.description,
            )
        }
        return InspectorSnapshot(
            joinedEventId = joined?.eventId,
            galleryRows = galleryRows,
            backend = backend,
            jobs = jobs,
            appUploads = appUploads,
            downloads = downloads,
            jobLimit = queue.jobLimit,
            backendOffline = mocks.backend.operator.offline,
            refusedAttestation = mocks.backend.operator.refuseAttestation,
            membershipUnreadable = mocks.disk.operator.isDenied(FileArea.SHARED, CONFIG_FILE_NAME),
            held = BackendCall.entries.filter { mocks.backend.operator.isHeld(it) }.mapTo(mutableSetOf()) { it.key } +
                listOfNotNull(ENUMERATION.takeIf { mocks.library.operator.enumerationHeld }),
        )
    }

    /** A completion the harness hands the app for an OS entry it plays: nothing waits on its release. */
    private object NoCompletion : ExpiringCompletion {
        override fun complete() = Unit

        override fun onExpired(action: () -> Unit) = Unit
    }

    companion object {
        /** A bad network's answer: a `502` and an error body. */
        val BAD_GATEWAY: TransferOutcome = TransferOutcome(statusCode = 502, expectedBytes = -1L, receivedBytes = 137L)

        /** A body short of its declared length. */
        val SHORT_READ: TransferOutcome = TransferOutcome(
            statusCode = 200,
            expectedBytes = 5_000L,
            receivedBytes = 1_200L,
        )

        private const val CONSOLE_CAP = 200
        private val AWAIT = 10.seconds
        private const val BACKEND_BASE = "https://in-memory.backend/api/v2"
        private const val APP_VERSION = "99.0"
        private const val DSN = "in-memory://desktop"
        private const val APP_STORE_URL = "https://apps.apple.com/app/id0000000000"
        private const val PRESET_EVENT = "Anna's Birthday"
        private const val FOREIGN_FILENAME = "IMG.HEIC"
    }
}

/** The inspector's render snapshot (recomputed after each mutation). */
data class InspectorSnapshot(
    val joinedEventId: String?,
    val galleryRows: List<GalleryRow>,
    val backend: List<DeviceObjects>,
    val jobs: List<JobRow>,
    /** The app uploader's transfers in flight, on its background session. */
    val appUploads: List<JobRow>,
    val downloads: List<DownloadRow>,
    val jobLimit: Int,
    val backendOffline: Boolean,
    /** Why the backend refuses this phone's attestation, or `null` for a genuine phone. */
    val refusedAttestation: DeviceRefusal?,
    val membershipUnreadable: Boolean,
    /** Which of [HOLDS] are held. */
    val held: Set<String>,
) {
    companion object {
        val EMPTY =
            InspectorSnapshot(
                null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), Int.MAX_VALUE, false, null, false, emptySet(),
            )
    }
}

/** A photo in the library: [imported] when it arrived by import (another member's) rather than from the operator. */
data class GalleryRow(val assetId: String, val imported: Boolean, val policyExcluded: Boolean = false)
data class DeviceObjects(val deviceId: String, val own: Boolean, val objects: List<String>)
data class JobRow(val key: String, val attempts: Int)

/** A transfer the OS's download session holds: where it fetches from, and the app's tag for it. */
data class DownloadRow(val url: String, val description: String)

/** The library's enumeration, as a hold beside the backend's calls. */
private const val ENUMERATION = "enumeration"

/** What an operator can hold unanswered: each backend call, by its key, and the library's enumeration. */
val HOLDS: List<String> = BackendCall.entries.map { it.key } + ENUMERATION

/** The assets the selection policy decides on, as the inspector adds them. */
enum class PolicyAsset(val label: String, val prefix: String, val build: (String) -> RawAsset) {
    SCREENSHOT("+ Screenshot", "shot", { LibraryAssets.screenshot(it) }),
    SCREEN_RECORDING("+ Screen rec", "rec", { LibraryAssets.screenRecording(it) }),
    GIF("+ GIF", "gif", { LibraryAssets.gif(it) }),
    LOW_RES("+ Low-res", "lowres", { LibraryAssets.lowResPhoto(it) }),

    /** A 1080p recording: below the IMAGE floor, above the VIDEO floor — so it must still upload. */
    HD_VIDEO("+ 1080p video", "video", { LibraryAssets.hdVideo(it) }),
}
