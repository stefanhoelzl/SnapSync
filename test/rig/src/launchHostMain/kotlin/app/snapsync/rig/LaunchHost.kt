package app.snapsync.rig

import app.snapsync.compose.AppDevicePorts
import app.snapsync.contracts.attest.InMemoryKeyAttestation
import app.snapsync.data.trust.TrustSlots
import app.snapsync.launchadapters.AdapterChoice
import app.snapsync.launchadapters.AdapterFiles
import app.snapsync.launchadapters.AdapterParse
import app.snapsync.launchadapters.LaunchAdapters
import app.snapsync.levers.AdaptersLevers
import app.snapsync.levers.AdaptersWritten
import app.snapsync.levers.CurrentAdapters
import app.snapsync.levers.LeverRefused
import app.snapsync.mock.MockDevice
import app.snapsync.mock.MockedSystem
import app.snapsync.model.ConfigRead
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.ports.Files
import app.snapsync.ports.SecureStoreRead
import app.snapsync.services.config.ConfigService
import app.snapsync.services.databases.SharedDatabase
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

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
    ports: AppDevicePorts,
    controls: RigDevControls,
    mockBase: String,
    realBackend: String,
): MockWorld = MockWorld(
    device = device,
    mocked = mocked,
    reach = BackendReach(
        base = mockBase,
        name = "mock",
        transport = device.backend.port(device.declaredVersion),
        network = device.backend.byteRoute,
        declared = device.declaredVersion,
        attester = { InMemoryKeyAttestation() },
        ownToken = { id ->
            val own = (ports.secureStore.value.read(TrustSlots.DEVICE_ID) as? SecureStoreRead.Found)?.value
            heldToken(ports.secureStore.value, id.takeIf { it == own })
        },
    ).takeIf { MockedSystem.BACKEND in mocked },
    reachRefusal = realBackend,
    operatorRefusal = { realBackend },
    version = device.declaredVersion.takeIf { MockedSystem.BACKEND in mocked },
    store = null,
    os = PlayedOs(device) { it in mocked },
    ownDeviceId = { (ports.secureStore.value.read(TrustSlots.DEVICE_ID) as? SecureStoreRead.Found)?.value.orEmpty() },
    joinedEventId = {
        val config = ConfigService(SharedDatabase(ports.databases.value, legacy = null), ports.clock.value)
        (config.read() as? ConfigRead.Joined)?.config?.eventId?.value
    },
    controls = controls.levers,
)

/**
 * Write the mocked systems' state every [SAVE_INTERVAL], for the life of the process: the app is the one writer (an
 * extension, in its own process, never writes one), so the next start of any process finds what this one left.
 */
internal fun keepSaving(chosen: LaunchAdapters.Chosen) {
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        while (isActive) {
            delay(SAVE_INTERVAL)
            runCatching { chosen.save() }.onFailure {
                log.w(
                    it,
                ) { "the mocked systems' state was not saved this time" }
            }
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

    /** Not cleared: [why] says why — [refused] when this host cannot, rather than failed to. */
    class Failed(val why: String, val refused: Boolean = false) : Cleared
}

/** Where one app host's adapter choice verbs differ from the other's. */
internal class AdapterVerbs(
    /** The real files adapter the adapter choice lives on. */
    val files: Files,
    val world: MockWorld,
    /** [CurrentAdapters.launch]: this launch's choice, in one line. */
    val launchLine: String,
    /** This host's own facts of [CurrentAdapters]: the iOS host's refusal reasons, the Android host's real adapters. */
    val current: (CurrentAdapters) -> CurrentAdapters,
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
 * The adapter choice verbs (`docs/testing.md`, "Launch-time adapters"), as the typed `adapters` service:
 *
 * - `current` — the choice this launch runs, the file behind it and the folder it lives in, and the host's own facts
 *   ([AdapterVerbs.current]).
 * - `choose` — the next choice, as the adapters file holds it. One that does not parse is a bad argument; one this host
 *   cannot compose ([AdapterVerbs.refusal]), or one written while the device is a member of an event, is refused — a
 *   membership would be carried from one set of systems into another, a real event's into a mocked backend or the
 *   reverse. Written, the state saved, the app EXITS once the answer has left; the next start reads it.
 * - `clear` — [AdapterVerbs.clear], and the app exits.
 */
internal fun adaptersService(verbs: AdapterVerbs): HostService<AdaptersLevers> = HostService(
    AdaptersLevers,
    object : AdaptersLevers {
        override suspend fun current(): CurrentAdapters = verbs.current(
            CurrentAdapters(
                launch = verbs.launchLine,
                mocked = verbs.world.mocked.sortedBy { it.ordinal }.map { it.key },
                file = verbs.files.read(FileArea.SHARED, AdapterFiles.CHOICE).let { it as? FileResult.Ok }?.value
                    ?.decodeToString(),
                folder = (verbs.files.locate(FileArea.SHARED, AdapterFiles.FOLDER) as? FileResult.Ok)?.value,
                systems = MockedSystem.entries.map { "${it.key}: ${it.what}" },
            ),
        )

        override suspend fun choose(choice: String): AdaptersWritten {
            val parsed = when (val read = AdapterChoice.parse(choice)) {
                is AdapterParse.Invalid -> throw IllegalArgumentException(read.problems.joinToString("; "))
                is AdapterParse.Parsed -> read.choice
            }
            verbs.refusal(parsed)?.let { throw LeverRefused(it) }
            verbs.world.joinedEventId()?.let { joined ->
                throw LeverRefused(
                    "this device is a member of event $joined; an adapter choice change would carry the membership into " +
                        "another set of systems. Leave, or reset, first",
                )
            }
            verbs.save()
            val written = verbs.files.write(FileArea.SHARED, AdapterFiles.CHOICE, parsed.render().encodeToByteArray())
            check(written is FileResult.Ok) { "not written: $written" }
            exitSoon(verbs.exit)
            return AdaptersWritten(written = parsed.toString(), next = verbs.next)
        }

        override suspend fun clear(): String = when (val cleared = verbs.clear()) {
            is Cleared.Failed -> if (cleared.refused) throw LeverRefused(cleared.why) else error(cleared.why)
            is Cleared.Done -> cleared.what.also { exitSoon(verbs.exit) }
        }
    },
)

/** How often a launch read from an adapters file saves its mocked systems' state. */
private val SAVE_INTERVAL = 500.milliseconds

/** Long enough for the answer to leave before the process does. */
private val EXIT_DELAY = 500.milliseconds
