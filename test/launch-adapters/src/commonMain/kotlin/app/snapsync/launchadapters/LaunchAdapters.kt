@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.launchadapters

import app.snapsync.compose.DevicePorts
import app.snapsync.mock.MockedSystem
import app.snapsync.mock.MockState

import app.snapsync.mock.MockDevice
import app.snapsync.mock.TemporaryFiles
import app.snapsync.model.CycleResult
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.ExtensionHandlers
import app.snapsync.ports.ExtensionHost
import app.snapsync.ports.Files
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Where a rig build keeps its launch-time adapters (`docs/testing.md`, "Launch-time adapters"): a `rig/` folder in the
 * SHARED area — the App Group, which every process of the app reaches — holding the adapters file, each mocked system's
 * durable state, and the mocked databases. Only rig-build code reads or writes it; a production build has none of it.
 */
object AdapterFiles {
    const val FOLDER: String = "rig"
    const val CHOICE: String = "$FOLDER/adapters"
    const val DATABASES: String = "$FOLDER/databases"

    fun state(system: MockedSystem): String = "$FOLDER/state/${system.key}.json"
}

/** Which process a launch is — the one fact that changes which face of a mock it gets. */
enum class AdapterProcess { APP, EXTENSION }

/** The platform facts a mocked device is built with — the build's, never the adapters file's. */
class AdapterFacts(
    /** Whether this operating system carries the OS-driven upload mechanism (iOS ≥26.1). */
    val osDrivenUpload: Boolean,
    /** The marketing version this build declares — what the backend mock's version gate reads. */
    val appVersion: String,
    /** A device id for a Keychain mock that holds none yet — a fresh one per device ([randomDeviceId]). */
    val freshDeviceId: () -> String,
    /**
     * The systems this platform has a REAL adapter for, or `null` where every system has one (iOS). A choice that leaves
     * any other system real — naming it `real`, or omitting it, which the file's grammar reads as real — is refused: it
     * would compose over an adapter nobody wrote.
     */
    val realAdapters: Set<MockedSystem>? = null,
    /**
     * What a launch with no adapters file composes over: `null` for all real (iOS), or the platform's default choice
     * where not every system has a real adapter (Android: every system without one mocked).
     */
    val whenAbsent: AdapterChoice? = null,
)

/** A fresh device id, for a Keychain mock that holds none yet. */
@OptIn(ExperimentalUuidApi::class)
fun randomDeviceId(): String = Uuid.random().toString()

/**
 * **What a process launched with** (`docs/testing.md`, "Launch-time adapters"), read once at its start from
 * [AdapterFiles.CHOICE] through the real [Files] adapter — never through a port the adapter choice might have mocked:
 *
 * - [AllReal] — there is no adapters file: an ordinary rig build, every system real.
 * - [Chosen] — a coherent choice: the mocked systems' mocks, restored from their state files.
 * - [Refused] — an adapters file that does not parse, is not coherent, or cannot be read. The process composes NOTHING and
 *   the control channel says why. Never a fall-back to real: a run believed mocked that reached a real system could
 *   write to the shared `snap-sync-dev` zone, where real users' photos live.
 */
sealed interface LaunchAdapters {
    data object AllReal : LaunchAdapters

    class Refused(val reasons: List<String>) : LaunchAdapters

    class Chosen internal constructor(
        val choice: AdapterChoice,
        val device: MockDevice,
        /** The real files adapter the state lives on — the same one the adapter choice was read through. */
        private val files: Files,
        val process: AdapterProcess,
    ) : LaunchAdapters {
        private val written = mutableMapOf<MockedSystem, String>()

        /**
         * [real] with every mocked system's ports swapped for its mock's faces — [root]'s faces: the upload extension's
         * root reaches only the shared files, has no App Attest, and reports to a channel nobody observes, in its own
         * process and inside the app alike.
         */
        fun ports(real: DevicePorts, root: AdapterProcess): DevicePorts = chosenPorts(real.lazies, choice, device, root)

        /**
         * Write every mocked system's state that changed since the last write — each file whole and atomically. Only the
         * app process writes (the coherence rules keep every system the extension would write real whenever the
         * extension runs in its own process), so a write never races another process's. Answers what it wrote.
         *
         * A snapshot taken while the app is mutating a mock may catch it half-way; the next write retakes it, so a
         * torn state lives at most one interval and never survives a quiet moment.
         */
        fun save(): List<MockedSystem> = choice.mocked.filter { system ->
            val text = runCatchingCancellable { MockState.encode(device, system) }.getOrNull() ?: return@filter false
            if (written[system] == text) return@filter false
            val ok = files.write(FileArea.SHARED, AdapterFiles.state(system), text.encodeToByteArray()) is FileResult.Ok
            if (ok) written[system] = text
            ok
        }
    }

    /** What [root] composes over: [real], with this launch's mocked systems swapped in — [real] itself when none are. */
    fun portsFor(real: DevicePorts, root: AdapterProcess): DevicePorts = (this as? Chosen)?.ports(real, root) ?: real

    companion object {
        private val loaded = AtomicReference<LaunchAdapters?>(null)

        /**
         * This process's adapter choice — read on the first call and the same answer after it, so the app's root and the
         * upload extension's root, when the control channel runs the latter inside the app, share ONE mocked device.
         * [process] is the first caller's: in the extension's own process that is the extension.
         */
        fun load(files: Files, process: AdapterProcess, facts: AdapterFacts): LaunchAdapters {
            loaded.load()?.let { return it }
            val launch = read(files, process, facts)
            return if (loaded.compareAndSet(null, launch)) launch else loaded.load()!!
        }

        /** The adapter choice already read in this process, or `null` before any root asked. */
        fun current(): LaunchAdapters? = loaded.load()

        /**
         * The adapter choice a root's ports were already built from — REFUSED where none was read yet, never all-real: a
         * caller that asks before the read is out of order, and composing nothing is the only answer that cannot reach
         * a real system by mistake.
         */
        fun alreadyRead(): LaunchAdapters =
            current() ?: Refused(listOf("the adapter choice was asked for before this process read it"))

        internal fun read(files: Files, process: AdapterProcess, facts: AdapterFacts): LaunchAdapters =
            when (val text = files.read(FileArea.SHARED, AdapterFiles.CHOICE)) {
                is FileResult.Ok -> when (val parsed = AdapterChoice.parse(text.value.decodeToString())) {
                    is AdapterParse.Invalid -> Refused(parsed.problems.map { "${AdapterFiles.CHOICE}: $it" })
                    is AdapterParse.Parsed -> (parsed.choice.incoherence() + parsed.choice.unwritten(facts)).takeIf { it.isNotEmpty() }
                        ?.let(::Refused)
                        ?: restore(parsed.choice, files, process, facts)
                }
                // The platform's default launch keeps nothing: no file chose it, so there is no state to carry.
                FileResult.NotFound -> facts.whenAbsent?.let { restore(it, files, process, facts, persisted = false) } ?: AllReal
                else -> Refused(listOf("${AdapterFiles.CHOICE} could not be read: $text"))
            }

        /** Every system [facts] has no real adapter for that this choice leaves real, each naming why. */
        private fun AdapterChoice.unwritten(facts: AdapterFacts): List<String> {
            val real = facts.realAdapters ?: return emptyList()
            return MockedSystem.entries.filter { it !in real && !isMocked(it) }.map {
                "${it.key}=real: this platform has no real ${it.key} adapter yet (real adapters: " +
                    real.sortedBy { r -> r.ordinal }.joinToString(",") { r -> r.key } + ")"
            }
        }

        /**
         * The mocked device [choice] composes over. A [persisted] launch — one an adapters file chose — keeps its mocked
         * databases in files and restores each mocked system's saved state; one that is not starts every mock fresh, in
         * memory.
         */
        private fun restore(
            choice: AdapterChoice,
            files: Files,
            process: AdapterProcess,
            facts: AdapterFacts,
            persisted: Boolean = true,
        ): LaunchAdapters {
            val databases = choice.takeIf { persisted && it.isMocked(MockedSystem.DATABASES) }?.let {
                (files.locate(FileArea.SHARED, AdapterFiles.DATABASES) as? FileResult.Ok)?.value
                    ?: return Refused(listOf("the mocked databases' folder ${AdapterFiles.DATABASES} has no location"))
            }
            val device = MockDevice(
                ownDeviceId = facts.freshDeviceId(),
                osDrivenUpload = facts.osDrivenUpload,
                databaseDirectory = databases,
                // A mocked download leaves its bytes where the app adopts them from — the real disk, unless it is mocked.
                temporaryFiles = if (choice.isMocked(MockedSystem.FILES)) null else TemporaryFiles.on(files),
                // A real photo library hands the mocked queue its own resources, which it must take.
                acceptsAnyUploadHandle = !choice.isMocked(MockedSystem.LIBRARY),
            )
            device.declaredVersion.value = facts.appVersion
            val problems = choice.mocked.filter { persisted }.mapNotNull { system ->
                when (val state = files.read(FileArea.SHARED, AdapterFiles.state(system))) {
                    is FileResult.Ok -> runCatchingCancellable { MockState.restore(device, system, state.value.decodeToString()) }
                        .exceptionOrNull()?.let { "${AdapterFiles.state(system)} does not restore: ${it.message}" }
                    FileResult.NotFound -> null
                    else -> "${AdapterFiles.state(system)} could not be read: $state"
                }
            }
            return if (problems.isEmpty()) Chosen(choice, device, files, process) else Refused(problems)
        }
    }
}

/**
 * The upload extension's entry port under a launch's adapter choice: [inner] itself, unless this launch cannot compose the cycle —
 * a [LaunchAdapters.Refused] choice, or the extension's OWN process invoked while the adapter choice mocks its registration (a registration
 * an earlier all-real run left behind; the operating system still honours it). Then the invocation is answered
 * `completed` and nothing is composed: nothing the adapter choice says is mocked can be reached from here.
 */
fun chosenExtensionHost(inner: ExtensionHost, launch: LaunchAdapters): ExtensionHost {
    val refuses = launch is LaunchAdapters.Refused ||
        (launch is LaunchAdapters.Chosen && launch.process == AdapterProcess.EXTENSION && launch.choice.isMocked(MockedSystem.EXTENSION_REGISTRY))
    return if (refuses) UncomposedExtensionHost(inner) else inner
}

/** An extension host whose invocations are answered without a composition — see [chosenExtensionHost]. */
private class UncomposedExtensionHost(private val inner: ExtensionHost) : ExtensionHost {
    override fun listen(handlers: ExtensionHandlers) = inner.listen(
        ExtensionHandlers(onProcess = { CycleResult.COMPLETED }, onTerminate = {}),
    )
}
