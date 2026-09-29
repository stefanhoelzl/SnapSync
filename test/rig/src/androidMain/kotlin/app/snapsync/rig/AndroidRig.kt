package app.snapsync.rig

import app.snapsync.android.scene.AndroidLifecycle
import app.snapsync.compose.AppCore
import app.snapsync.compose.DevicePorts
import app.snapsync.contracts.EntryDriver
import app.snapsync.feature.upload.AppUploadMechanism
import app.snapsync.launchadapters.AdapterChoice
import app.snapsync.launchadapters.AdapterProcess
import app.snapsync.launchadapters.chosenPorts
import app.snapsync.launchadapters.randomDeviceId
import app.snapsync.mock.MockDevice
import app.snapsync.mock.MockedSystem
import app.snapsync.mock.OperatorDrivenUploads
import app.snapsync.mock.UploadNetwork
import app.snapsync.model.ConfigRead
import app.snapsync.model.SecureSlots
import app.snapsync.model.SecureStoreRead
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.services.config.ConfigService
import app.snapsync.services.logs.LogTailService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Clock

/**
 * **The Android rig build's launch** (`docs/testing.md`, "One control protocol, served by two hosts"): the app composed
 * over the device as mocks, bar the two systems Android has real adapters for — the screen and its foreground life
 * ([ANDROID_CHOICE]). The choice is FIXED rather than read from an adapters file, as the iOS app host reads one: there is
 * nothing real to choose for any other system yet, and a choice that could name one would compose over an adapter
 * nobody wrote. The mocks live in the process's memory, so an exit forgets them.
 *
 * Built while the root initializes, before its process services exist; the channel's server starts once the root has
 * composed ([start]).
 */
class AndroidRigLaunch internal constructor(
    /** What the app composes over: the root's real screen and lifecycle, every other system its mock. */
    val ports: DevicePorts,
    val controls: RigDevControls,
    val ui: RigUi,
    /** What the operator levers move: the mocked device. */
    val world: MockWorld,
) {
    /** This launch's choice, in one line — `/health` reports it. */
    val description: String = ANDROID_CHOICE.toString()

    /** The line this launch adds to the app's boot banner. */
    val bootLines: List<String> = listOf("[boot] adapters = $description (fixed: the Android rig build)")

    /** The app uploader's mechanism: the mocked transfer session creates nothing, so the operator is the engine. */
    val appDrivenUpload: AppUploadMechanism = OperatorDrivenUploads
}

/** Build the launch over the root's [real] adapters. */
fun androidRigLaunch(real: DevicePorts): AndroidRigLaunch {
    val device = MockDevice(ownDeviceId = randomDeviceId())
    device.declaredVersion.value = SERVED_VERSION
    val ports = chosenPorts(real.lazies, ANDROID_CHOICE, device, AdapterProcess.APP)
    val controls = RigDevControls()
    val world = MockWorld(
        device = device,
        mocked = ANDROID_CHOICE.mocked,
        reach = BackendReach(
            base = MOCK_BASE,
            name = "mock",
            port = device.backend.port(device.declaredVersion),
            network = UploadNetwork { url, headers, _ -> device.backend.operator.receive(url, headers) },
            declared = device.declaredVersion,
        ),
        reachRefusal = "",
        operatorRefusal = { "" },
        version = device.declaredVersion,
        os = PlayedOs(device) { it in ANDROID_CHOICE.mocked },
        ownDeviceId = { (ports.secureStore.read(SecureSlots.DEVICE_ID) as? SecureStoreRead.Found)?.value.orEmpty() },
        joinedEventId = { (ConfigService(ports.files, ports.clock).read() as? ConfigRead.Joined)?.config?.eventId },
        setInviteLinkHints = { controls.hints = it },
    )
    return AndroidRigLaunch(ports, controls, RigUi(ports.lazies.ui), world)
}

/**
 * Start the control channel over the composed app, once the main thread is done with the root's own launch — the
 * server reads the process services and the composition, which exist after it. [lifecycle] is the real adapter the
 * `/os` foreground and background verbs deliver through; [filesDir] is where the bound port is published.
 */
fun AndroidRigLaunch.start(
    core: () -> AppCore,
    host: () -> StatusContainerHost,
    lifecycle: AndroidLifecycle,
    filesDir: File,
) {
    CoroutineScope(Dispatchers.Main).launch {
        RigServer(core = core, host = host, hooks = hooks(core, host, lifecycle, filesDir)).start()
    }
}

private fun AndroidRigLaunch.hooks(
    core: () -> AppCore,
    host: () -> StatusContainerHost,
    lifecycle: AndroidLifecycle,
    filesDir: File,
): RigHooks = RigHooks(
    bootedAt = Clock.System.now().toString(),
    uploadTier = "operator-driven",
    uploadBase = MOCK_BASE,
    transferBinding = "mock",
    // The platform calls the app's entry points on the main thread, so the rig does too.
    mainLane = Dispatchers.Main,
    deviceLog = LogTailService(ports.files)::tail,
    triggerGroups = mapOf(
        "app" to TriggerGroup(
            lane = Dispatchers.Main,
            wired = appTriggers(ChosenEntryDriver(world::isMocked, MockEntryDriver(world.device, world.os), AndroidEntryDriver(lifecycle))) +
                ("onExpiry" to RigTrigger.Fire { arg -> if (arg == "next") world.os.expireNext() else world.os.expire() }),
            excluded = emptyMap(),
        ),
    ),
    userCommands = userCommands(dispatch = ui::dispatch, state = { host().container.stateFlow.value }),
    excludedUserCommands = excludedUserCommands(),
    deviceCommands = world.honouredLevers() + mapOf(
        "reset" to resetCommand(reset = controls::reset),
        "gallery/seed" to seedCommand { n, kind -> world.seedMockLibrary(n, kind) },
    ),
    readGallery = world.mockGalleryReader(),
    osExtensionEnabled = { null },
    publishBoundPort = { bound -> rigPortFilePath(filesDir.path)?.let { File(it).writeText(bound.toString()) } },
    contracts = emptyList(),
    refusals = androidRefusals(world),
    osExtensionNotApplicable = "Android has no upload extension: its uploader runs in the app's own process",
    osRecord = world.os::record,
    adapters = description,
)

/**
 * The Android operating system, driven where its system is real: the app's foreground life, through the same adapter
 * method the process's resume and pause reach, on the main thread. Every other system is mocked on this build, so
 * [ChosenEntryDriver] hands its deliveries to the mock and never here.
 */
private class AndroidEntryDriver(private val lifecycle: AndroidLifecycle) : EntryDriver {
    override fun foreground() = lifecycle.deliverForeground()

    override fun background() = lifecycle.deliverBackground()

    override fun pushToken(hex: String) = mocked("push")

    override fun pushTokenFailure(description: String) = mocked("push")

    override fun silentPush(eventId: String?, done: () -> Unit) = mocked("push")

    override fun continueLink(url: String) = mocked("links")

    override fun backgroundTask(identifier: String, done: () -> Unit) = mocked("wake")

    override fun backgroundTransfers(identifier: String, done: () -> Unit) = mocked("the transfer sessions")

    private fun mocked(system: String): Nothing =
        error("$system is mocked on the Android rig build, so its delivery is the mock's — this driver was routed wrongly")
}

/** What the Android host refuses of the shared vocabulary, each with its reason. */
private fun androidRefusals(world: MockWorld): Map<String, String> = world.leverRefusals() + buildMap {
    RigVocabulary.extensionEntries.forEach {
        put(it, "Android has no upload extension: its uploader runs in the app's own process, and none is composed yet")
    }
    RigVocabulary.adapterCommands.forEach {
        put(it, "the Android rig build's adapter choice is fixed (${ANDROID_CHOICE}): no other system has a real adapter yet")
    }
    put(
        "device/relaunch",
        "process death is the app's exit followed by a launch (`adb shell am force-stop`, then start it); the channel " +
            "cannot relaunch the process it runs in",
    )
    put("device/gallery/wipe", "the photo library is mocked on this build; a mocked library is fresh for every launch")
    put("device/uploaders", "Android composes no OS-driven upload mechanism for a switch to choose between")
    put("device/process-metrics", "process-metric reports are MetricKit's; no Android provider is composed")
    RigVocabulary.appHostCommands.filter { it.startsWith("device/upload-") }.forEach {
        put(it, "the mocked upload-job queue is operated by the jobs verbs, not by an operating system to play")
    }
    put(RigVocabulary.CONTRACT, "no port-contract binding runs on Android yet")
}

/** Every system mocked but the two Android has real adapters for: the screen and its foreground life. */
private val ANDROID_CHOICE = AdapterChoice(MockedSystem.entries.toSet() - setOf(MockedSystem.SCREEN, MockedSystem.LIFECYCLE))

/** The device-facing base the mocked backend is addressed at, carrying exactly one version prefix. */
private const val MOCK_BASE = "https://in-memory.backend/api/v2"

/** The version this build declares to the backend mock — high, so its version gate serves it. */
private const val SERVED_VERSION = "99.0"
