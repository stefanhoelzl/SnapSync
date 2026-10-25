@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.rig

import app.snapsync.compose.AppDevicePorts
import app.snapsync.config.bakedUploadBase
import app.snapsync.inbound.Completion
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Granted
import app.snapsync.inbound.InboundImplementation
import app.snapsync.inbound.Running
import app.snapsync.launchadapters.AdapterFacts
import app.snapsync.launchadapters.AdapterFiles
import app.snapsync.launchadapters.AdapterProcess
import app.snapsync.launchadapters.LaunchAdapters
import app.snapsync.launchadapters.randomDeviceId
import app.snapsync.logging.appMarketingVersion
import app.snapsync.mock.MockDevice
import app.snapsync.mock.MockedSystem
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.WakeId
import app.snapsync.ports.EntryEvents
import app.snapsync.ports.WakeHandlers
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.Foundation.NSFileManager
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSProcessInfo

private val log = Logger.withTag("rig")

/**
 * **A rig build's launch, in the app process** (`docs/testing.md`, "Launch-time adapters"): the adapter choice read once at
 * the process's start, the ports the app composes over, the control channel's own adapters, and the [world] its
 * operator levers move. Built by the app hook while `SnapSyncRoot` initializes, before anything logs.
 *
 * Every decision the hook may not hold is here: what a refused adapter choice composes (nothing), and which real system
 * must be quieted because its mock replaced it.
 */
class RigLaunch internal constructor(
    val launch: LaunchAdapters,
    /** What the app composes over: the real adapters, with the adapter choice's mocked systems swapped in. */
    val ports: AppDevicePorts,
    val controls: RigDevControls,
    val ui: RigUi,
    /** What the operator levers move: the mocked device — an idle one, for a launch that mocks nothing. */
    val world: MockWorld,
    /** What the world service serves of this launch: its mocked systems' events and its screen's. */
    events: WorldEvents,
    /** The real files adapter the adapter choice lives on. */
    internal val files: app.snapsync.ports.Files,
) {
    /** The world the channel serves its callers' acts in: the events, from the last act on. */
    val hostWorld: HostWorld = HostWorld.onADevice(events)

    private val chosen: LaunchAdapters.Chosen? = launch as? LaunchAdapters.Chosen

    /** Why this launch composed nothing, or `null` for one that composed. */
    val uncomposed: String? = (launch as? LaunchAdapters.Refused)?.reasons?.joinToString("; ")

    /** This launch's choice, in one line — `/health` reports it. */
    val description: String = chosen?.choice?.toString() ?: "all real"

    /** The line this launch adds to the app's boot banner. */
    val bootLines: List<String> =
        listOf("[boot] adapters = ${uncomposed?.let { "REFUSED, nothing composed: $it" } ?: description}")

    /** Compose at launch — unless the adapter choice was refused, when nothing is composed and the channel says why. */
    fun launch(compose: () -> Unit) {
        if (uncomposed == null) compose() else log.e { "the adapter choice was refused — nothing is composed: $uncomposed" }
    }

    /** Write every mocked system's changed state now — before an exit. */
    internal fun flush() {
        chosen?.save()
    }
}

/**
 * Read this launch's adapter choice and build what the app composes over it. [real] are the root's real adapters.
 *
 * Where the adapter choice mocks the wake — or composes nothing — the real `BGTaskScheduler` still gets its launch handler, which
 * completes a task at once: iOS requires one registered before launch finishes, and a heartbeat an earlier all-real run
 * scheduled would otherwise launch a process with none. A mocked wake also cancels that real request, so the real
 * operating system stops waking a run whose wakes the channel plays.
 */
@OptIn(CompositionOnly::class)
fun rigLaunch(platform: AppDevicePorts): RigLaunch {
    val real = attestable(platform)
    val launch = LaunchAdapters.load(
        real.files.value,
        AdapterProcess.APP,
        AdapterFacts(osCarriesUploadExtension(), appMarketingVersion(), ::randomDeviceId),
    )
    val chosen = launch as? LaunchAdapters.Chosen
    val mocked = chosen?.choice?.mocked.orEmpty()
    if (launch is LaunchAdapters.Refused || MockedSystem.WAKE in mocked) {
        real.wake.value.inbound(WakeHandlers(onWake = QuietWake))
        if (MockedSystem.WAKE in mocked) real.wake.value.cancel(WakeId.Heartbeat)
    }
    chosen?.let(::keepSaving)
    val ports = launch.portsFor(real)
    val controls = RigDevControls()
    val device = chosen?.device ?: MockDevice()
    val world = launchWorld(device, mocked, ports, controls, mockBase = bakedUploadBase(), realBackend = REAL_BACKEND)
    val events = deviceEvents(world)
    return RigLaunch(launch, ports, controls, RigUi(ports.ui, screenShown(events)), world, events, real.files.value)
}

/**
 * The adapter choice verbs (`adaptersService`), as the iOS app host serves them: `current` says why this launch's choice
 * was refused, if it was; and `clear` deletes the adapter choice's whole folder — the choice and every mocked system's
 * state — so the app exits and starts all real.
 */
internal fun adapterVerbs(launch: RigLaunch): AdapterVerbs =
    AdapterVerbs(
        files = launch.files,
        world = launch.world,
        launchLine = launch.uncomposed?.let { "refused" } ?: launch.description,
        current = { it.copy(refusedBecause = (launch.launch as? LaunchAdapters.Refused)?.reasons.orEmpty()) },
        refusal = { choice ->
            val incoherence = choice.incoherence()
            if (incoherence.isEmpty()) null else "the adapter choice is not coherent: " + incoherence.joinToString("; ")
        },
        save = launch::flush,
        next = "launch the app again — its next start, and every later one, composes over this adapter choice",
        clear = {
            val folder = (launch.files.locate(FileArea.SHARED, AdapterFiles.FOLDER) as? FileResult.Ok)?.value
            if (folder == null) {
                Cleared.Failed("the App Group has no location, so there is no adapter choice to clear", refused = true)
            } else {
                NSFileManager.defaultManager.removeItemAtPath(folder, error = null)
                Cleared.Done(folder)
            }
        },
        exit = { platform.posix.exit(0) },
    )

/**
 * The app host's refusals of the shared vocabulary for this launch (`docs/testing.md`): every typed service over a system
 * its adapter choice leaves real. Its own services refuse theirs themselves (`rigServices`).
 */
fun iosRefusals(launch: RigLaunch, host: HostServices): Map<String, String> = launch.world.serviceRefusals(host)

private const val REAL_BACKEND =
    "the backend is REAL on this launch — the shared snap-sync-dev zone, where real users' photos live — so the channel " +
        "never reaches it and has no mock of it to move; mock `backend` (`adapters.choose`) to seed one"

/**
 * Run [start] once the main thread is done with what it is doing now — the root's own initialization, which builds the
 * launch before its process services exist, and the launch call that follows. The channel's server reads those
 * services, so it starts after them.
 */
fun startWhenReady(start: () -> Unit) {
    CoroutineScope(Dispatchers.Main).launch { start() }
}

/**
 * Whether this operating system carries the upload extension at all (iOS ≥26.1) — the fact the root reads for itself,
 * read again here because the launch is built before the root's own copy exists. It shapes only the registration mock:
 * a mocked registration exists exactly where a real one could.
 */
private fun osCarriesUploadExtension(): Boolean = NSProcessInfo.processInfo.isOperatingSystemAtLeastVersion(
    cValue<NSOperatingSystemVersion> {
        majorVersion = 26
        minorVersion = 1
        patchVersion = 0
    },
)

/** A real wake a mocked one replaces: answered at once, nothing run — the operating system's own task is released. */
@OptIn(InboundImplementation::class)
private object QuietWake : Granted<WakeId> {
    override fun handle(arg: WakeId, completion: Completion): Running {
        completion.complete()
        return Unexpirable
    }

    private object Unexpirable : Running {
        override fun expire() = Unit
    }
}

/**
 * The real adapters a rig build's app attests with: the platform's own on a phone, whose App Attest the local backend
 * verifies; on a simulator, which has none, the platform's own but for a software Android key the local backend's `any`
 * trust accepts — so a simulator app holds a real token, as every caller of the backend must.
 */
internal expect fun attestable(real: AppDevicePorts): AppDevicePorts
