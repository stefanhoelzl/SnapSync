@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.rig

import app.snapsync.compose.DevicePorts
import app.snapsync.config.bakedUploadBase
import app.snapsync.logging.appMarketingVersion
import app.snapsync.mock.MockDevice
import app.snapsync.mock.UploadNetwork
import app.snapsync.launchadapters.LaunchAdapters
import app.snapsync.launchadapters.AdapterChoice
import app.snapsync.launchadapters.AdapterFacts
import app.snapsync.launchadapters.AdapterFiles
import app.snapsync.launchadapters.AdapterParse
import app.snapsync.launchadapters.AdapterProcess
import app.snapsync.launchadapters.randomDeviceId
import app.snapsync.mock.MockedSystem
import app.snapsync.model.ConfigRead
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.SecureSlots
import app.snapsync.model.SecureStoreRead
import app.snapsync.model.WakeId
import app.snapsync.ports.WakeHandlers
import app.snapsync.services.config.ConfigService
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import platform.Foundation.NSFileManager
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSProcessInfo
import kotlinx.cinterop.cValue
import kotlin.time.Duration.Companion.milliseconds

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
    val bootLines: List<String> = listOf("[boot] adapters = ${uncomposed?.let { "REFUSED, nothing composed: $it" } ?: description}")

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
    val launch = LaunchAdapters.load(real.files, AdapterProcess.APP, AdapterFacts(osCarriesUploadExtension(), appMarketingVersion(), ::randomDeviceId))
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
    val world = MockWorld(
        device = device,
        mocked = mocked,
        reach = BackendReach(
            base = bakedUploadBase(),
            name = "mock",
            port = device.backend.port(device.declaredVersion),
            network = UploadNetwork { url, headers, _ -> device.backend.operator.receive(url, headers) },
            declared = device.declaredVersion,
        ).takeIf { MockedSystem.BACKEND in mocked },
        reachRefusal = REAL_BACKEND,
        operatorRefusal = { REAL_BACKEND },
        version = device.declaredVersion.takeIf { MockedSystem.BACKEND in mocked },
        os = PlayedOs(device) { it in mocked },
        ownDeviceId = { (ports.secureStore.read(SecureSlots.DEVICE_ID) as? SecureStoreRead.Found)?.value.orEmpty() },
        joinedEventId = { (ConfigService(ports.files, ports.clock).read() as? ConfigRead.Joined)?.config?.eventId },
        setInviteLinkHints = { controls.hints = it },
    )
    return RigLaunch(launch, ports, controls, RigUi(ports.lazies.ui), world, real.files)
}

/**
 * Write the mocked systems' state every [SAVE_INTERVAL], for the life of the process: the app is the one writer (the
 * extension, in its own process, never writes one), so the next start of any process — a relaunch, a background wake —
 * finds what this one left.
 */
private fun keepSaving(chosen: LaunchAdapters.Chosen) {
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        while (isActive) {
            delay(SAVE_INTERVAL)
            runCatching { chosen.save() }.onFailure { log.w(it) { "the mocked systems' state was not saved this time" } }
        }
    }
}

/**
 * The adapter choice verbs (`docs/testing.md`, "Launch-time adapters"):
 *
 * - `POST /device/adapters/current` — the adapter choice this launch runs, the file behind it and the folder it lives in, and why it
 *   was refused if it was.
 * - `POST /device/adapters` — the body is the next choice, as the adapters file holds it. An adapter choice that does not parse answers `400`,
 *   an incoherent one `409` with every broken rule, and one written while the device is a member of an event `409`:
 *   a membership would be carried from one set of systems into another — a real event's into a mocked backend, or the
 *   reverse. Written, the state saved, the app EXITS; the next start of any process reads it.
 * - `POST /device/adapters/clear` — delete the adapter choice and every mocked system's state; the app exits, and starts all real.
 */
fun adapterCommands(launch: RigLaunch): Map<String, RigCommand> = mapOf(
    "adapters/current" to RigCommand { _, _ -> CommandResult.ok(currentAdapters(launch)) },
    "adapters" to RigCommand { _, body ->
        val text = body?.takeIf { it.isNotBlank() }
            ?: return@RigCommand CommandResult.badRequest("the body is the choice: one `system=mock|real` per line")
        when (val parsed = AdapterChoice.parse(text)) {
            is AdapterParse.Invalid -> CommandResult.badRequest(parsed.problems.joinToString("; "))
            is AdapterParse.Parsed -> {
                val incoherence = parsed.choice.incoherence()
                val joined = launch.world.joinedEventId()
                when {
                    incoherence.isNotEmpty() -> CommandResult.refused("the adapter choice is not coherent: " + incoherence.joinToString("; "))
                    joined != null -> CommandResult.refused(
                        "this device is a member of event $joined; an adapter choice change would carry the membership into another " +
                            "set of systems. Leave, or reset (POST /device/reset), first",
                    )
                    else -> writeAndExit(launch, parsed.choice)
                }
            }
        }
    },
    "adapters/clear" to RigCommand { _, _ ->
        val folder = (launch.files.locate(FileArea.SHARED, AdapterFiles.FOLDER) as? FileResult.Ok)?.value
            ?: return@RigCommand CommandResult.refused("the App Group has no location, so there is no adapter choice to clear")
        NSFileManager.defaultManager.removeItemAtPath(folder, error = null)
        exitSoon()
        CommandResult.ok("""{"cleared":${jsonString(folder)},"exiting":true}""")
    },
)

private fun writeAndExit(launch: RigLaunch, choice: AdapterChoice): CommandResult {
    launch.flush()
    val written = launch.files.write(FileArea.SHARED, AdapterFiles.CHOICE, choice.render().encodeToByteArray())
    if (written !is FileResult.Ok) return CommandResult(status = 500, body = """{"error":${jsonString("not written: $written")}}""")
    exitSoon()
    return CommandResult.ok(
        buildJsonObject {
            put("written", choice.toString())
            put("exiting", true)
            put("next", "launch the app again — its next start, and every later one, composes over this adapter choice")
        }.toString(),
    )
}

private fun currentAdapters(launch: RigLaunch): String = buildJsonObject {
    put("launch", launch.uncomposed?.let { "refused" } ?: launch.description)
    putJsonArray("mocked") { launch.world.mocked.sortedBy { it.ordinal }.forEach { add(JsonPrimitive(it.key)) } }
    putJsonArray("refusedBecause") { (launch.launch as? LaunchAdapters.Refused)?.reasons.orEmpty().forEach { add(JsonPrimitive(it)) } }
    put("file", (launch.files.read(FileArea.SHARED, AdapterFiles.CHOICE) as? FileResult.Ok)?.value?.decodeToString())
    // Where the adapter choice lives, as a platform path: `simctl` does not list an ad-hoc-signed app's App Group, so this is how a
    // simulator script finds the folder to write an adapter choice into before a launch.
    put("folder", (launch.files.locate(FileArea.SHARED, AdapterFiles.FOLDER) as? FileResult.Ok)?.value)
    putJsonArray("systems") { MockedSystem.entries.forEach { add(JsonPrimitive("${it.key}: ${it.what}")) } }
}.toString()

/** Exit the app once the answer has left — the next start of any process reads what was written. */
private fun exitSoon() {
    CoroutineScope(Dispatchers.Default).launch {
        delay(EXIT_DELAY)
        log.i { "exiting for the next launch to read its adapter choice" }
        platform.posix.exit(0)
    }
}

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
    if (world.isMocked(MockedSystem.LIBRARY)) {
        put("device/gallery/wipe", "the photo library is mocked on this launch; a mocked library is never PhotoKit's to wipe")
    }
    putAll(uploadJobRefusals())
    if (world.isMocked(MockedSystem.UPLOAD_QUEUE)) {
        put("device/upload-jobs/perform", "the upload-job queue is mocked on this launch — its jobs verbs (POST /device/jobs/…) play it")
    }
    if (world.isMocked(MockedSystem.EXTENSION_REGISTRY)) {
        put("device/upload-extension/record", "the extension's registration is mocked on this launch; its record is the mock's")
    }
}

private const val REAL_BACKEND =
    "the backend is REAL on this launch — the shared snap-sync-dev zone, where real users' photos live — so the channel " +
        "never reaches it and has no mock of it to move; mock `backend` (POST /device/adapters) to seed one"

private val SAVE_INTERVAL = 500.milliseconds
private val EXIT_DELAY = 500.milliseconds

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
