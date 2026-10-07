@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.rig

import app.snapsync.compose.DevicePorts
import app.snapsync.config.bakedUploadBase
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
import app.snapsync.ports.WakeHandlers
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
    val ports: DevicePorts,
    val controls: RigDevControls,
    val ui: RigUi,
    /** What the operator levers move: the mocked device — an idle one, for a launch that mocks nothing. */
    val world: MockWorld,
    /** The real files adapter the adapter choice lives on. */
    internal val files: app.snapsync.ports.Files,
) {
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
fun rigLaunch(real: DevicePorts): RigLaunch {
    val launch = LaunchAdapters.load(
        real.files,
        AdapterProcess.APP,
        AdapterFacts(osCarriesUploadExtension(), appMarketingVersion(), ::randomDeviceId),
    )
    val chosen = launch as? LaunchAdapters.Chosen
    val mocked = chosen?.choice?.mocked.orEmpty()
    if (launch is LaunchAdapters.Refused || MockedSystem.WAKE in mocked) {
        real.wake.listen(WakeHandlers(onWake = { _, completion -> completion.complete() }))
        if (MockedSystem.WAKE in mocked) real.wake.cancel(WakeId.Heartbeat)
    }
    chosen?.let(::keepSaving)
    val ports = launch.portsFor(real, AdapterProcess.APP)
    val controls = RigDevControls()
    val device = chosen?.device ?: MockDevice()
    val world = launchWorld(device, mocked, ports, controls, mockBase = bakedUploadBase(), realBackend = REAL_BACKEND)
    return RigLaunch(launch, ports, controls, RigUi(ports.lazies.ui), world, real.files)
}

/**
 * The adapter choice verbs (`launchAdapterCommands`), as the iOS app host serves them: `/device/adapters/current` says
 * why this launch's choice was refused, if it was; and `/device/adapters/clear` deletes the adapter choice's whole
 * folder — the choice and every mocked system's state — so the app exits and starts all real.
 */
fun adapterCommands(launch: RigLaunch): Map<String, RigCommand> = launchAdapterCommands(
    AdapterVerbs(
        files = launch.files,
        world = launch.world,
        launchLine = launch.uncomposed?.let { "refused" } ?: launch.description,
        current = {
            putJsonArray(
                "refusedBecause",
            ) { (launch.launch as? LaunchAdapters.Refused)?.reasons.orEmpty().forEach { add(JsonPrimitive(it)) } }
        },
        refusal = { choice ->
            val incoherence = choice.incoherence()
            if (incoherence.isEmpty()) null else "the adapter choice is not coherent: " + incoherence.joinToString("; ")
        },
        save = launch::flush,
        next = "launch the app again — its next start, and every later one, composes over this adapter choice",
        clear = {
            val folder = (launch.files.locate(FileArea.SHARED, AdapterFiles.FOLDER) as? FileResult.Ok)?.value
            if (folder == null) {
                Cleared.Failed(
                    CommandResult.refused("the App Group has no location, so there is no adapter choice to clear"),
                )
            } else {
                NSFileManager.defaultManager.removeItemAtPath(folder, error = null)
                Cleared.Done(folder)
            }
        },
        exit = { platform.posix.exit(0) },
    ),
)

/**
 * The app host's refusals of the shared vocabulary for this launch (`docs/testing.md`): every operator lever over a
 * system its adapter choice leaves real, the played operating system's expiry unless the background-time holds are mocked, and
 * the verbs whose subject the adapter choice replaced.
 */
fun iosRefusals(launch: RigLaunch): Map<String, String> = buildMap {
    val world = launch.world
    putAll(world.leverRefusals())
    world.refusalFor(MockedSystem.BACKGROUND_TIME)?.let { reason ->
        RigVocabulary.playedOsEntries.forEach { put(it, "a real operating system's expiry is its own. $reason") }
    }
    put(
        "device/relaunch",
        "process death on a device is the app's exit (POST /device/adapters does one) followed by a launch; the channel " +
            "cannot relaunch the process it runs in",
    )
    put(
        "device/reinstall",
        "deleting the app ends the process the channel runs in; delete and reinstall it from outside (`ios-device` / " +
            "`simctl uninstall`, then install)",
    )
    put(
        "device/event-key/lose",
        "the phone's own store keeps the event's key; losing it is restoring onto a new phone, outside the channel",
    )
    put(
        "device/invite",
        "an event's key leaves the creating phone only inside its invite: read it from the joined screen's share link",
    )
    if (world.isMocked(MockedSystem.LIBRARY)) {
        put(
            "device/gallery/wipe",
            "the photo library is mocked on this launch; a mocked library is never PhotoKit's to wipe",
        )
    }
    putAll(uploadJobRefusals())
    if (world.isMocked(MockedSystem.UPLOAD_QUEUE)) {
        put(
            "device/upload-jobs/perform",
            "the upload-job queue is mocked on this launch — its jobs verbs (POST /device/jobs/…) play it",
        )
    }
    if (world.isMocked(MockedSystem.EXTENSION_REGISTRY)) {
        put(
            "device/upload-extension/record",
            "the extension's registration is mocked on this launch; its record is the mock's",
        )
    }
}

private const val REAL_BACKEND =
    "the backend is REAL on this launch — the shared snap-sync-dev zone, where real users' photos live — so the channel " +
        "never reaches it and has no mock of it to move; mock `backend` (POST /device/adapters) to seed one"

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
