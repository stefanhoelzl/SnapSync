package app.snapsync.rig

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.snapsync.android.link.AndroidLinks
import app.snapsync.android.scene.AndroidLifecycle
import app.snapsync.compose.AppCore
import app.snapsync.compose.AppDevicePorts
import app.snapsync.contracts.EntryDriver
import app.snapsync.launchadapters.AdapterChoice
import app.snapsync.launchadapters.AdapterFacts
import app.snapsync.launchadapters.AdapterFiles
import app.snapsync.launchadapters.AdapterProcess
import app.snapsync.launchadapters.LaunchAdapters
import app.snapsync.launchadapters.randomDeviceId
import app.snapsync.mock.MockedSystem
import app.snapsync.model.uploadersCarried
import app.snapsync.ports.EntryEvents
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileResult
import app.snapsync.ports.Files
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.rig.gallery.addMotionPhoto
import app.snapsync.rig.gallery.androidGalleryReader
import app.snapsync.rig.gallery.seedMediaStore
import app.snapsync.services.logs.LogTailService
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Clock

/**
 * **The Android rig build's launch** (`docs/testing.md`, "Launch-time adapters"): the app composed over the adapter choice
 * read once at the process's start from the adapters file, as the iOS app host reads one — with two differences that
 * both come from Android not having every real adapter yet. **No file is not "all real"**: it is [ANDROID_DEFAULT],
 * every system mocked but the screen and its foreground life, which is what every launch without a file has always
 * composed. And **a choice may leave real only the systems Android has an adapter for** ([ANDROID_REAL_ADAPTERS]) —
 * naming another `real`, or omitting it (which the file's grammar reads as real), refuses the launch: it would compose
 * over an adapter nobody wrote. A refused launch composes nothing; the process stops at start naming why.
 *
 * A launch read from an adapters file keeps its mocked systems' state, as the iOS host does — saved beside the file and
 * restored at the next start — because a real system then outlives the process and the mocks must too: a real config
 * file saying "joined" over a backend mock that forgot the event is a device no real phone can be. The default launch
 * saves nothing: every system that holds state is mocked there, so an exit forgets them all, together.
 *
 * Built while the root initializes, before its process services exist; the channel's server starts once the root has
 * composed ([start]).
 */
class AndroidRigLaunch internal constructor(
    val launch: LaunchAdapters.Chosen,
    /** What the app composes over: the root's real adapters, with the choice's mocked systems swapped in. */
    val ports: AppDevicePorts,
    val controls: RigDevControls,
    val ui: RigUi,
    /** What the operator levers move: the mocked device. */
    val world: MockWorld,
    /** What the world service serves of this launch: its mocked systems' events and its screen's. */
    events: WorldEvents,
    /** The real files adapter the adapter choice lives on. */
    internal val files: Files,
    /** The real api's base, the build's resolved one. */
    realBase: String,
) {
    /** Where this launch's backend is: the mock's synthetic base, or the real api's. */
    /** The world the channel serves its callers' acts in: the events, from the last act on. */
    val hostWorld: HostWorld = HostWorld.onADevice(events)

    /** Where the app's runtime tells its entry points' work: the mocked device's, which feeds [hostWorld]'s events. */
    val entryEvents: EntryEvents = world.device.entryEvents.port()

    val uploadBase: String = if (MockedSystem.BACKEND in launch.choice.mocked) MOCK_BASE else realBase

    /** This launch's choice, in one line — `/health` reports it. */
    val description: String = launch.choice.toString()

    /** The line this launch adds to the app's boot banner. */
    val bootLines: List<String> = listOf("[boot] adapters = $description")
}

/** Build the launch over the root's [real] adapters; [uploadBase] is the real backend's, the build's resolved one. */
fun androidRigLaunch(real: AppDevicePorts, uploadBase: String): AndroidRigLaunch {
    val facts = AdapterFacts(
        osDrivenUpload = false,
        appVersion = SERVED_VERSION,
        freshDeviceId = ::randomDeviceId,
        realAdapters = ANDROID_REAL_ADAPTERS,
        whenAbsent = ANDROID_DEFAULT,
        absentSystems = ANDROID_ABSENT_SYSTEMS,
    )
    val launch = when (val read = LaunchAdapters.load(real.files.value, AdapterProcess.APP, facts)) {
        is LaunchAdapters.Chosen -> read
        is LaunchAdapters.Refused -> error(
            "the adapter choice was refused, so nothing is composed: " + read.reasons.joinToString("; "),
        )
        LaunchAdapters.AllReal -> error("an Android launch always has a choice: no adapters file is $ANDROID_DEFAULT")
    }
    if (real.files.value.exists(FileArea.SHARED, AdapterFiles.CHOICE) == FileResult.Ok(true)) keepSaving(launch)
    val device = launch.device
    val mocked = launch.choice.mocked
    val ports = launch.portsFor(real)
    val controls = RigDevControls()
    val world = launchWorld(device, mocked, ports, controls, mockBase = MOCK_BASE, realBackend = REAL_BACKEND)
    val events = deviceEvents(world)
    val ui = RigUi(ports.ui, screenShown(events))
    return AndroidRigLaunch(launch, ports, controls, ui, world, events, real.files.value, uploadBase)
}

/**
 * The adapter choice verbs, as the iOS app host serves them (`launchAdapterCommands`), with Android's differences:
 * `/device/adapters/current` lists the systems Android can make real; a choice leaving a system real that Android has
 * no adapter for is refused, as is one breaking a rule over a system Android does not have at all; and
 * `/device/adapters/clear` deletes the choice file alone — the app exits, and its next start is [ANDROID_DEFAULT].
 */
private fun adapterVerbs(launch: AndroidRigLaunch): AdapterVerbs =
    AdapterVerbs(
        files = launch.files,
        world = launch.world,
        launchLine = launch.description,
        current = {
            it.copy(
                realAdapters = ANDROID_REAL_ADAPTERS.sortedBy { system -> system.ordinal }.map { system -> system.key },
            )
        },
        refusal = { choice ->
            val broken = choice.incoherence(ANDROID_ABSENT_SYSTEMS) +
                MockedSystem.entries.filter { it !in ANDROID_REAL_ADAPTERS && !choice.isMocked(it) }
                    .map { "${it.key}=real: Android has no real ${it.key} adapter yet" }
            if (broken.isEmpty()) null else "the adapter choice cannot compose: " + broken.joinToString("; ")
        },
        save = launch.launch::save,
        next = "launch the app again: its next start composes over this adapter choice",
        clear = {
            when (val deleted = launch.files.delete(FileArea.SHARED, AdapterFiles.CHOICE)) {
                is FileResult.Ok, FileResult.NotFound -> Cleared.Done(AdapterFiles.CHOICE)
                else -> Cleared.Failed("not cleared: $deleted")
            }
        },
        exit = { exitProcess(0) },
    )

/**
 * Start the control channel over the composed app, once the main thread is done with the root's own launch — the
 * server reads the process services and the composition, which exist after it. [lifecycle] is the real adapter the
 * `os` service's foreground and background deliveries go through; [context] is the application — its files directory is where
 * the bound port is published, and a real library is seeded through its media store.
 */
fun AndroidRigLaunch.start(
    core: () -> AppCore,
    host: () -> StatusContainerHost,
    lifecycle: AndroidLifecycle,
    links: AndroidLinks,
    context: Context,
) {
    CoroutineScope(Dispatchers.Main).launch {
        RigServer(hooks = hooks(core, host, lifecycle, links, context)).start()
    }
}

private fun AndroidRigLaunch.hooks(
    core: () -> AppCore,
    host: () -> StatusContainerHost,
    lifecycle: AndroidLifecycle,
    links: AndroidLinks,
    context: Context,
): RigHooks = RigHooks(
    bootedAt = Clock.System.now().toString(),
    uploadTier = { uploadersCarried(osSupportsOsDrivenUpload = false) },
    uploadBase = uploadBase,
    transferBinding = "mock",
    deviceLog = LogTailService(ports.files.value)::tail,
    osExtensionEnabled = { null },
    servicesOf = { hooks ->
        world.servedServices(
            androidHostServices(world, lifecycle, links),
            hooks.world.acts,
            reconcileUploads = { core().uploadTransitions.onOverrideChanged() },
        ) + world.servedHostServices(hostServices(hooks, core, host, context), hooks.world.acts)
    },
    publishBoundPort = { bound ->
        rigPortFilePath(
            context.filesDir.path,
        )?.let { File(it).writeText(bound.toString()) }
    },
    refusals = androidRefusals(world, androidHostServices(world, lifecycle, links)),
    osExtensionNotApplicable = "Android has no upload extension: its uploader runs in the app's own process",
    adapters = description,
    world = hostWorld,
)

/**
 * The Android operating system, driven where its system is real: the app's foreground life, through the same adapter
 * method the process's resume and pause reach, on the main thread, and an App Link, as the VIEW intent a running
 * activity is handed. Every other system has no Android adapter yet, so [ChosenEntryDriver] hands its deliveries to
 * the mock and never here.
 */
private class AndroidEntryDriver(
    private val lifecycle: AndroidLifecycle,
    private val links: AndroidLinks,
) : EntryDriver {
    override fun foreground() = lifecycle.deliverForeground()

    override fun background() = lifecycle.deliverBackground()

    override fun pushToken(hex: String) = mocked("push")

    override fun pushTokenFailure(description: String) = mocked("push")

    override fun silentPush(eventId: String?, done: () -> Unit) = mocked("push")

    // An App Link as the platform hands it to a running activity: a VIEW intent carrying the URL, fragment and all.
    override fun continueLink(url: String) =
        links.deliverIntent("onNewIntent", Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    override fun backgroundTask(identifier: String, done: () -> Unit) = mocked("wake")

    override fun backgroundTransfers(identifier: String, done: () -> Unit) = mocked("the transfer sessions")

    private fun mocked(system: String): Nothing =
        error(
            "$system is mocked on the Android rig build, so its delivery is the mock's — this driver was routed wrongly",
        )
}

/** Each delivery through the system that makes it: the mock's where the launch mocks it, the platform's adapter otherwise. */
private fun androidEntries(world: MockWorld, lifecycle: AndroidLifecycle, links: AndroidLinks): EntryDriver =
    ChosenEntryDriver(world::isMocked, MockEntryDriver(world.device, world.os), AndroidEntryDriver(lifecycle, links))

/** The services this host implements itself: the deliveries, on the main thread the platform delivers on. No extension. */
private fun androidHostServices(world: MockWorld, lifecycle: AndroidLifecycle, links: AndroidLinks) = HostServices(
    entries = PlayedEntries(androidEntries(world, lifecycle, links), Dispatchers.Main, world.os::atRelease),
    extensionHost = null,
    noExtensionHost = "Android has no upload extension: its uploader runs in the app's own process",
)

/** What the Android host refuses of the shared vocabulary, each with its reason. */
private fun androidRefusals(world: MockWorld, host: HostServices): Map<String, String> = world.serviceRefusals(host)

/**
 * The Android host's own services (`HostServices.kt`): its screen, its process — which the channel cannot relaunch,
 * running in it — its library (MediaStore's where real, the mock's otherwise), its adapter choice, and no in-app
 * contract or upload-job queue.
 */
private fun AndroidRigLaunch.hostServices(
    hooks: RigHooks,
    core: () -> AppCore,
    host: () -> StatusContainerHost,
    context: Context,
): List<HostService<*>> = listOf(
    world.screenService(Dispatchers.Main, ui, shown = { host().screen.value }),
    hostService(
        hooks,
        core,
        host,
        relaunch = refusing(
            "process death is the app's exit followed by a launch (`adb shell am force-stop`, then start it); the channel " +
                "cannot relaunch the process it runs in",
        ),
        reinstall = refusing(
            "deleting the app ends the process the channel runs in; `adb uninstall` it and install it again",
        ),
        invite = refusing(
            "an event's key leaves the creating phone only inside its invite: read it from the joined screen's share link",
        ),
        disk = refusing(
            "the disk census is iOS's (the App Group is not pullable there); read an Android app's files with `adb shell run-as`",
        ),
        processMetrics = refusing("process-metric reports are MetricKit's; no Android provider is composed"),
    ),
    if (world.isMocked(MockedSystem.LIBRARY)) {
        galleryService(
            seed = honoured { n, kind -> world.seedMockLibrary(n, kind) },
            wipe = refusing("a mocked library is fresh for every launch"),
            addMoving = refusing(
                "the photo library is mocked on this launch and holds no media bytes; add a moving photo through the " +
                    "typed `library` service's `add` of kind `live-photo`",
            ),
            read = honoured(world.mockGalleryReader()),
        )
    } else {
        galleryService(
            seed = honoured { n, kind -> seedMediaStore(context, log, n, kind) },
            wipe = refusing(
                "a real library's photos are the member's: remove them through MediaStore (`adb shell` and a scan), never " +
                    "through the channel",
            ),
            addMoving = honoured { photo -> addMotionPhoto(context, log, photo) },
            read = honoured(androidGalleryReader(core, context)),
        )
    },
    adaptersService(adapterVerbs(this)),
    contractService(
        emptyList(),
        refusal = "Android's port-contract bindings run as device tests (androidPlatformTest), not in the app",
    ),
    uploadJobService(
        process = refusing(NO_UPLOAD_EXTENSION),
        perform = refusing(NO_UPLOAD_EXTENSION),
        extensionRecord = refusing(NO_UPLOAD_EXTENSION),
    ),
)

private const val NO_UPLOAD_EXTENSION =
    "Android has no upload extension and no OS-held upload-job queue: its uploader runs in the app's own process"

/** The systems Android has a real adapter for — the only ones an adapter choice may leave real. */
private val ANDROID_REAL_ADAPTERS: Set<MockedSystem> = setOf(
    MockedSystem.SCREEN,
    MockedSystem.LIFECYCLE,
    MockedSystem.CLOCK,
    MockedSystem.FILES,
    MockedSystem.DATABASES,
    MockedSystem.PREFERENCES,
    MockedSystem.KEYCHAIN,
    MockedSystem.INTEGRITY,
    MockedSystem.BACKEND,
    MockedSystem.LINKS,
    MockedSystem.LIBRARY,
    MockedSystem.SYSTEM_UI,
    MockedSystem.WAKE,
    MockedSystem.BACKGROUND_TIME,
    MockedSystem.UPLOAD_SESSION,
    MockedSystem.DOWNLOADS,
    MockedSystem.PUSH,
    MockedSystem.PROCESS_INFO,
    MockedSystem.NETWORK,
    MockedSystem.DEVICE_CONDITIONS,
    MockedSystem.CRASH_REPORTER,
)

/**
 * The systems Android does not have: the PhotoKit upload-job queue and the upload extension's registration. Nothing on
 * this root composes over their mocks (no upload cycle, no extension), so the rules they trigger do not apply here.
 */
private val ANDROID_ABSENT_SYSTEMS: Set<MockedSystem> = setOf(
    MockedSystem.UPLOAD_QUEUE,
    MockedSystem.EXTENSION_REGISTRY,
)

/** What a launch with no adapters file composes: every system mocked but the screen and its foreground life. */
private val ANDROID_DEFAULT = AdapterChoice(
    MockedSystem.entries.toSet() - setOf(MockedSystem.SCREEN, MockedSystem.LIFECYCLE),
)

/** Why a mock lever over a real backend is refused. */
private const val REAL_BACKEND = "the backend is real on this launch; its state is the real api's, not a mock's"

private val log = Logger.withTag("rig")

/** The device-facing base the mocked backend is addressed at, carrying exactly one version prefix. */
private const val MOCK_BASE = "https://in-memory.backend/api/v2"

/** The version this build declares to the backend mock — high, so its version gate serves it. */
private const val SERVED_VERSION = "99.0"
