package app.snapsync.rig

import app.snapsync.compose.DevicePorts
import app.snapsync.launchadapters.AdapterChoice
import app.snapsync.launchadapters.AdapterFiles
import app.snapsync.launchadapters.AdapterParse
import app.snapsync.launchadapters.LaunchAdapters
import app.snapsync.mock.MockDevice
import app.snapsync.mock.MockedSystem
import app.snapsync.mock.UploadNetwork
import app.snapsync.model.ConfigRead
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.SecureSlots
import app.snapsync.model.SecureStoreRead
import app.snapsync.ports.Files
import app.snapsync.services.config.ConfigService
import co.touchlab.kermit.Logger
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/*
 * What the two APP hosts — iOS and Android — share of a launch over the launch-time adapters (`docs/testing.md`,
 * "Launch-time adapters"), and the JVM host does not: the world a launch's mocked systems make, the saving of their
 * state, the exit that hands the next start a new choice, and the adapter choice verbs. Where the platforms differ,
 * each says so through [AdapterVerbs].
 */

private val log = Logger.withTag("rig")

/**
 * The world the operator levers move on a launch that mocks [mocked] of [device]: the backend mock addressed at
 * [mockBase] where it is mocked, and every lever over a real backend refused for [realBackend].
 */
internal fun launchWorld(
    device: MockDevice,
    mocked: Set<MockedSystem>,
    ports: DevicePorts,
    controls: RigDevControls,
    mockBase: String,
    realBackend: String,
): MockWorld = MockWorld(
    device = device,
    mocked = mocked,
    reach = BackendReach(
        base = mockBase,
        name = "mock",
        port = device.backend.port(device.declaredVersion),
        network = UploadNetwork { url, headers, _ -> device.backend.operator.receive(url, headers) },
        declared = device.declaredVersion,
    ).takeIf { MockedSystem.BACKEND in mocked },
    reachRefusal = realBackend,
    operatorRefusal = { realBackend },
    version = device.declaredVersion.takeIf { MockedSystem.BACKEND in mocked },
    os = PlayedOs(device) { it in mocked },
    ownDeviceId = { (ports.secureStore.read(SecureSlots.DEVICE_ID) as? SecureStoreRead.Found)?.value.orEmpty() },
    joinedEventId = { (ConfigService(ports.files, ports.clock).read() as? ConfigRead.Joined)?.config?.eventId },
    setInviteLinkHints = { controls.hints = it },
)

/**
 * Write the mocked systems' state every [SAVE_INTERVAL], for the life of the process: the app is the one writer (an
 * extension, in its own process, never writes one), so the next start of any process finds what this one left.
 */
internal fun keepSaving(chosen: LaunchAdapters.Chosen) {
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        while (isActive) {
            delay(SAVE_INTERVAL)
            runCatching { chosen.save() }.onFailure { log.w(it) { "the mocked systems' state was not saved this time" } }
        }
    }
}

/** [exit] the app once the answer has left — the next start of any process reads what was written. */
internal fun exitSoon(exit: () -> Unit) {
    CoroutineScope(Dispatchers.Default).launch {
        delay(EXIT_DELAY)
        log.i { "exiting for the next launch to read its adapter choice" }
        exit()
    }
}

/** What clearing the adapter choice came to. */
internal sealed interface Cleared {
    /** Cleared: [what] is the path or name that is gone. */
    class Done(val what: String) : Cleared

    /** Not cleared: the [answer] says why. */
    class Failed(val answer: CommandResult) : Cleared
}

/** Where one app host's adapter choice verbs differ from the other's. */
internal class AdapterVerbs(
    /** The real files adapter the adapter choice lives on. */
    val files: Files,
    val world: MockWorld,
    /** `/device/adapters/current`'s `launch`: this launch's choice, in one line. */
    val launchLine: String,
    /** This host's own fields of `/device/adapters/current`, written after `mocked`. */
    val current: JsonObjectBuilder.() -> Unit,
    /** Why this host cannot compose [AdapterChoice], as its refusal reads — or `null` when it can. */
    val refusal: (AdapterChoice) -> String?,
    /** Write the mocked systems' changed state now, before an exit. */
    val save: () -> Unit,
    /** What the answer to a written choice tells the caller to do next. */
    val next: String,
    /** Delete the adapter choice, this host's way. */
    val clear: () -> Cleared,
    /** End the process. */
    val exit: () -> Unit,
)

/**
 * The adapter choice verbs (`docs/testing.md`, "Launch-time adapters"):
 *
 * - `POST /device/adapters/current` — the choice this launch runs, the file behind it and the folder it lives in, and
 *   the host's own facts ([AdapterVerbs.current]).
 * - `POST /device/adapters` — the body is the next choice, as the adapters file holds it. One that does not parse
 *   answers `400`; one this host cannot compose ([AdapterVerbs.refusal]), or one written while the device is a member
 *   of an event, `409` — a membership would be carried from one set of systems into another, a real event's into a
 *   mocked backend or the reverse. Written, the state saved, the app EXITS; the next start reads it.
 * - `POST /device/adapters/clear` — [AdapterVerbs.clear], and the app exits.
 */
internal fun launchAdapterCommands(verbs: AdapterVerbs): Map<String, RigCommand> = mapOf(
    "adapters/current" to RigCommand { _, _ -> CommandResult.ok(currentAdapters(verbs)) },
    "adapters" to RigCommand { _, body ->
        val text = body?.takeIf { it.isNotBlank() }
            ?: return@RigCommand CommandResult.badRequest("the body is the choice: one `system=mock|real` per line")
        when (val parsed = AdapterChoice.parse(text)) {
            is AdapterParse.Invalid -> CommandResult.badRequest(parsed.problems.joinToString("; "))
            is AdapterParse.Parsed -> {
                val refusal = verbs.refusal(parsed.choice)
                val joined = verbs.world.joinedEventId()
                when {
                    refusal != null -> CommandResult.refused(refusal)
                    joined != null -> CommandResult.refused(
                        "this device is a member of event $joined; an adapter choice change would carry the membership into another " +
                            "set of systems. Leave, or reset (POST /device/reset), first",
                    )
                    else -> writeAndExit(verbs, parsed.choice)
                }
            }
        }
    },
    "adapters/clear" to RigCommand { _, _ ->
        when (val cleared = verbs.clear()) {
            is Cleared.Failed -> cleared.answer
            is Cleared.Done -> {
                exitSoon(verbs.exit)
                CommandResult.ok("""{"cleared":${jsonString(cleared.what)},"exiting":true}""")
            }
        }
    },
)

private fun writeAndExit(verbs: AdapterVerbs, choice: AdapterChoice): CommandResult {
    verbs.save()
    val written = verbs.files.write(FileArea.SHARED, AdapterFiles.CHOICE, choice.render().encodeToByteArray())
    if (written !is FileResult.Ok) return CommandResult(status = 500, body = """{"error":${jsonString("not written: $written")}}""")
    exitSoon(verbs.exit)
    return CommandResult.ok(
        buildJsonObject {
            put("written", choice.toString())
            put("exiting", true)
            put("next", verbs.next)
        }.toString(),
    )
}

private fun currentAdapters(verbs: AdapterVerbs): String = buildJsonObject {
    put("launch", verbs.launchLine)
    putJsonArray("mocked") { verbs.world.mocked.sortedBy { it.ordinal }.forEach { add(JsonPrimitive(it.key)) } }
    verbs.current(this)
    put("file", (verbs.files.read(FileArea.SHARED, AdapterFiles.CHOICE) as? FileResult.Ok)?.value?.decodeToString())
    // Where the adapter choice lives, as a platform path: `simctl` does not list an ad-hoc-signed app's App Group, so
    // this is how a simulator script finds the folder to write an adapter choice into before a launch.
    put("folder", (verbs.files.locate(FileArea.SHARED, AdapterFiles.FOLDER) as? FileResult.Ok)?.value)
    putJsonArray("systems") { MockedSystem.entries.forEach { add(JsonPrimitive("${it.key}: ${it.what}")) } }
}.toString()

/** How often a launch read from an adapters file saves its mocked systems' state. */
private val SAVE_INTERVAL = 500.milliseconds

/** Long enough for the answer to leave before the process does. */
private val EXIT_DELAY = 500.milliseconds
