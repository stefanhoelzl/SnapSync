@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.mix

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
 * Where a rig build keeps its launch-time mix (`docs/testing.md`, "The launch-time mock mix"): a `rig/` folder in the
 * SHARED area — the App Group, which every process of the app reaches — holding the mix file, each mocked system's
 * durable state, and the mocked databases. Only rig-build code reads or writes it; a production build has none of it.
 */
object MixFiles {
    const val FOLDER: String = "rig"
    const val MIX: String = "$FOLDER/mix"
    const val DATABASES: String = "$FOLDER/databases"

    fun state(system: MockedSystem): String = "$FOLDER/state/${system.key}.json"
}

/** Which process a launch is — the one fact that changes which face of a mock it gets. */
enum class MixProcess { APP, EXTENSION }

/** The platform facts a mocked device is built with — the build's, never the mix file's. */
class MixFacts(
    /** Whether this operating system carries the OS-driven upload mechanism (iOS ≥26.1). */
    val osDrivenUpload: Boolean,
    /** The marketing version this build declares — what the backend mock's version gate reads. */
    val appVersion: String,
    /** A device id for a Keychain mock that holds none yet — a fresh one per device ([randomDeviceId]). */
    val freshDeviceId: () -> String,
)

/** A fresh device id, for a Keychain mock that holds none yet. */
@OptIn(ExperimentalUuidApi::class)
fun randomDeviceId(): String = Uuid.random().toString()

/**
 * **What a process launched with** (`docs/testing.md`, "The launch-time mock mix"), read once at its start from
 * [MixFiles.MIX] through the real [Files] adapter — never through a port the mix might have mocked:
 *
 * - [AllReal] — there is no mix file: an ordinary rig build, every system real.
 * - [Mixed] — a coherent mix: the mocked systems' mocks, restored from their state files.
 * - [Refused] — a mix file that does not parse, is not coherent, or cannot be read. The process composes NOTHING and
 *   the control channel says why. Never a fall-back to real: a run believed mocked that reached a real system could
 *   write to the shared `snap-sync-dev` zone, where real users' photos live.
 */
sealed interface LaunchMix {
    data object AllReal : LaunchMix

    class Refused(val reasons: List<String>) : LaunchMix

    class Mixed internal constructor(
        val mix: Mix,
        val device: MockDevice,
        /** The real files adapter the state lives on — the same one the mix was read through. */
        private val files: Files,
        val process: MixProcess,
    ) : LaunchMix {
        private val written = mutableMapOf<MockedSystem, String>()

        /**
         * [real] with every mocked system's ports swapped for its mock's faces — [root]'s faces: the upload extension's
         * root reaches only the shared files, has no App Attest, and reports to a channel nobody observes, in its own
         * process and inside the app alike.
         */
        fun ports(real: DevicePorts, root: MixProcess): DevicePorts = mixedPorts(real.lazies, this, root)

        /**
         * Write every mocked system's state that changed since the last write — each file whole and atomically. Only the
         * app process writes (the coherence rules keep every system the extension would write real whenever the
         * extension runs in its own process), so a write never races another process's. Answers what it wrote.
         *
         * A snapshot taken while the app is mutating a mock may catch it half-way; the next write retakes it, so a
         * torn state lives at most one interval and never survives a quiet moment.
         */
        fun save(): List<MockedSystem> = mix.mocked.filter { system ->
            val text = runCatchingCancellable { MockState.encode(device, system) }.getOrNull() ?: return@filter false
            if (written[system] == text) return@filter false
            val ok = files.write(FileArea.SHARED, MixFiles.state(system), text.encodeToByteArray()) is FileResult.Ok
            if (ok) written[system] = text
            ok
        }
    }

    /** What [root] composes over: [real], with this launch's mocked systems swapped in — [real] itself when none are. */
    fun portsFor(real: DevicePorts, root: MixProcess): DevicePorts = (this as? Mixed)?.ports(real, root) ?: real

    companion object {
        private val loaded = AtomicReference<LaunchMix?>(null)

        /**
         * This process's launch mix — read on the first call and the same answer after it, so the app's root and the
         * upload extension's root, when the control channel runs the latter inside the app, share ONE mocked device.
         * [process] is the first caller's: in the extension's own process that is the extension.
         */
        fun load(files: Files, process: MixProcess, facts: MixFacts): LaunchMix {
            loaded.load()?.let { return it }
            val launch = read(files, process, facts)
            return if (loaded.compareAndSet(null, launch)) launch else loaded.load()!!
        }

        /** The launch mix already read in this process, or `null` before any root asked. */
        fun current(): LaunchMix? = loaded.load()

        /**
         * The launch mix a root's ports were already built from — REFUSED where none was read yet, never all-real: a
         * caller that asks before the read is out of order, and composing nothing is the only answer that cannot reach
         * a real system by mistake.
         */
        fun alreadyRead(): LaunchMix =
            current() ?: Refused(listOf("the launch mix was asked for before this process read it"))

        internal fun read(files: Files, process: MixProcess, facts: MixFacts): LaunchMix =
            when (val text = files.read(FileArea.SHARED, MixFiles.MIX)) {
                is FileResult.Ok -> when (val parsed = Mix.parse(text.value.decodeToString())) {
                    is MixParse.Invalid -> Refused(parsed.problems.map { "${MixFiles.MIX}: $it" })
                    is MixParse.Parsed -> parsed.mix.incoherence().takeIf { it.isNotEmpty() }?.let(::Refused)
                        ?: restore(parsed.mix, files, process, facts)
                }
                FileResult.NotFound -> AllReal
                else -> Refused(listOf("${MixFiles.MIX} could not be read: $text"))
            }

        private fun restore(mix: Mix, files: Files, process: MixProcess, facts: MixFacts): LaunchMix {
            val databases = mix.takeIf { it.isMocked(MockedSystem.DATABASES) }?.let {
                (files.locate(FileArea.SHARED, MixFiles.DATABASES) as? FileResult.Ok)?.value
                    ?: return Refused(listOf("the mocked databases' folder ${MixFiles.DATABASES} has no location"))
            }
            val device = MockDevice(
                ownDeviceId = facts.freshDeviceId(),
                osDrivenUpload = facts.osDrivenUpload,
                databaseDirectory = databases,
                // A mocked download leaves its bytes where the app adopts them from — the real disk, unless it is mocked.
                temporaryFiles = if (mix.isMocked(MockedSystem.FILES)) null else TemporaryFiles.on(files),
                // A real photo library hands the mocked queue its own resources, which it must take.
                acceptsAnyUploadHandle = !mix.isMocked(MockedSystem.LIBRARY),
            )
            device.declaredVersion.value = facts.appVersion
            val problems = mix.mocked.mapNotNull { system ->
                when (val state = files.read(FileArea.SHARED, MixFiles.state(system))) {
                    is FileResult.Ok -> runCatchingCancellable { MockState.restore(device, system, state.value.decodeToString()) }
                        .exceptionOrNull()?.let { "${MixFiles.state(system)} does not restore: ${it.message}" }
                    FileResult.NotFound -> null
                    else -> "${MixFiles.state(system)} could not be read: $state"
                }
            }
            return if (problems.isEmpty()) Mixed(mix, device, files, process) else Refused(problems)
        }
    }
}

/**
 * The upload extension's entry port under a launch mix: [inner] itself, unless this launch cannot compose the cycle —
 * a [LaunchMix.Refused] mix, or the extension's OWN process invoked while the mix mocks its registration (a registration
 * an earlier all-real run left behind; the operating system still honours it). Then the invocation is answered
 * `completed` and nothing is composed: nothing the mix says is mocked can be reached from here.
 */
fun mixedExtensionHost(inner: ExtensionHost, launch: LaunchMix): ExtensionHost {
    val refuses = launch is LaunchMix.Refused ||
        (launch is LaunchMix.Mixed && launch.process == MixProcess.EXTENSION && launch.mix.isMocked(MockedSystem.EXTENSION_REGISTRY))
    return if (refuses) UncomposedExtensionHost(inner) else inner
}

/** An extension host whose invocations are answered without a composition — see [mixedExtensionHost]. */
private class UncomposedExtensionHost(private val inner: ExtensionHost) : ExtensionHost {
    override fun listen(handlers: ExtensionHandlers) = inner.listen(
        ExtensionHandlers(onProcess = { CycleResult.COMPLETED }, onTerminate = {}),
    )
}
