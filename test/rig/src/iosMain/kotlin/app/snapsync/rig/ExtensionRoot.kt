package app.snapsync.rig

import app.snapsync.logging.FileLogWriter
import app.snapsync.logging.PublicNSLogWriter
import app.snapsync.logging.extensionLogDestination
import co.touchlab.kermit.Logger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext

/**
 * Driving the **upload extension's** composition root from the control channel — the `/os/photokit-ext/…`
 * group (capability `ios-photokit-upload`).
 *
 * On a simulator the OS never invokes the upload extension, so its root is never entered and the shipping
 * tier's cycle cannot run there at all. The channel invokes that root directly instead. Everything the
 * cycle touches is real — the shared `uploadCore`, the entry gate, the ledger, PhotoKit discovery, the
 * selection policy, the backend — except the OS's upload-job subsystem, which that target substitutes
 * because reaching it there is fatal rather than merely unscheduled.
 *
 * It invokes the **real** root, not a copy of its wiring: nothing here rebuilds `UploadPorts`, so a
 * scenario driven from this channel exercises the composition the appex actually assembles, including its
 * boot banner, its process-lifetime singletons, its `runBlocking` and its pending→`PROCESSING` requeue.
 *
 * On a **device** this same group drives the **real** OS job queue, because the substitution is a property
 * of the compilation target rather than of the caller. Forcing a cycle on demand there — instead of waiting
 * for the OS to schedule one — is a capability the phone did not previously have.
 */

/**
 * The lane the extension root is invoked on: **its own single thread, never main.**
 *
 * `process()` is synchronous by the OS's contract and runs under `runBlocking` on the OS-invoked thread,
 * and the extension process has no main lane at all (spec `module-architecture`, the dispatcher-lane law).
 * Running that `runBlocking` on the live app's main thread would freeze the UI for the whole cycle and can
 * deadlock on anything the cycle needs from main. Single-threaded rather than a pool, because the core
 * relies on serial execution for mutual exclusion — the same reason the composition lane is one thread.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
private val extensionLane: CoroutineContext = newSingleThreadContext("snapsync-photokit-ext")

/**
 * The `/os/photokit-ext` group: the extension root's entry points, on the extension root's lane.
 *
 * [process] and [terminate] arrive as thunks over the REAL `UploadExtensionRoot` members, so this module
 * names neither the root nor `:app:ios:extension` — the same shape every other platform verb crosses this
 * seam in.
 */
fun extensionTriggerGroup(
    process: () -> Int,
    terminate: () -> Unit,
    excluded: Map<String, String>,
    log: Logger = Logger.withTag("rig"),
): TriggerGroup = TriggerGroup(
    lane = extensionLane,
    wired = mapOf(
        "processRawValue" to RigTrigger.Answering { _, body ->
            invokeExtensionCycle(process, body, log)
        },
        // The OS is terminating a cycle. Wired rather than excluded: unlike the app root's scene
        // observers — whose entire content is whether the PLATFORM called them — this entry records what
        // the root knows about its own in-flight work, and invoking it here exercises the same path.
        "onTerminate" to RigTrigger.Fire { terminate() },
    ),
    // Supplied by the hook, beside the app root's, so the whole exclusion inventory sits in one file — the
    // file a reviewer reads (no guard derives it any more; the one that did was retired in `74302d2b`).
    excluded = excluded,
)

/**
 * Run one extension cycle and answer with what it produced.
 *
 * Not gated on any mechanism: both uploaders may write the one App-Group ledger, so this cycle is just another
 * cycle over it, and the extension root's own admission (a full grant, read in its entry gate) decides what it
 * may do — exactly as it does when the OS invokes it (decision record `changes/both-uploaders-active`, D8).
 *
 * Kermit's writer list is process-global and BOTH composition roots set it in their `init`, so touching the
 * extension root redirects the app's own log into `ext-debug.log` and would silence
 * `/device/logs?process=app` for the rest of the process. Snapshot and restore around the call. The
 * cycle's own lines still land in `ext-debug.log`, which is where they belong — `IosDeviceLogSource`
 * already serves that file as the extension process's log.
 */
private suspend fun invokeExtensionCycle(
    process: () -> Int,
    body: String?,
    log: Logger,
): String {
    beginUploadJobCycle(body)?.let { refusal -> return refusal }
    val saved = Logger.config.logWriterList
    val raw = try {
        // Point Kermit at the EXTENSION's log for the duration, then hand the app's writers back.
        //
        // Restoring alone is not enough, and the difference was measured. The extension root installs its
        // writers in its `init`, which runs **once** — so with only a restore, the first invoked cycle
        // wrote `ext-debug.log` and every cycle after it wrote the app's `debug.log`, because by then the
        // object was already initialised and nothing re-pointed the writers. A caller reading
        // `/device/logs?process=extension` would have seen one cycle and concluded the rest never ran.
        //
        // Setting them per call makes every invoked cycle land where an extension's cycle lands, which is
        // what makes that log answerable at all on this host.
        Logger.setLogWriters(PublicNSLogWriter(), FileLogWriter(extensionLogDestination().path))
        process()
    } finally {
        Logger.setLogWriters(saved)
    }
    log.i { "photokit-ext cycle finished: raw=$raw" }
    return endUploadJobCycle(raw)
}

/**
 * The `/device` verbs this target's upload-job subsystem adds, if any.
 *
 * Empty on a device: the OS performs its own transfers there, so there is nothing for an operator to
 * perform on its behalf, and offering the verb would invite a caller to move bytes the system was
 * already moving.
 */
internal expect fun uploadJobDeviceCommands(): Map<String, RigCommand>

/**
 * The upload-job verbs this target REFUSES, with the reason — the complement of [uploadJobDeviceCommands] within
 * [RigVocabulary.appHostCommands], so `GET /device` classifies them on every iOS target.
 */
internal expect fun uploadJobRefusals(): Map<String, String>

/** The app host's refusals of the shared vocabulary (capability `testing-architecture`). */
fun iosRefusals(): Map<String, String> =
    RigVocabulary.worldLeverRefusals + RigVocabulary.appHostUnwiredRefusals + uploadJobRefusals()

/**
 * Hand this invocation's job sets to the target's upload-job subsystem, or answer with the reason this
 * target will not take them.
 *
 * Per-target because the subsystem is: on a simulator the queue is substituted and the caller plays the OS,
 * while on a device the OS holds the queue itself and there is nothing to hand in.
 */
internal expect suspend fun beginUploadJobCycle(body: String?): String?

/** Render this invocation's answer — the raw processing result, plus whatever the target can report. */
internal expect suspend fun endUploadJobCycle(raw: Int): String

/**
 * How a `PHBackgroundResourceUploadProcessingResult` raw value reads back — the inverse of the tested
 * `CycleResult.processingResultRawValue()` mapping, for the caller's benefit only.
 *
 * Rendered rather than re-derived: the raw value IS what the Swift shell forwards to the OS, so reporting
 * it verbatim beside a name keeps the answer honest if the two ever disagree.
 */
internal fun processingResultName(raw: Int): String = when (raw) {
    0 -> "failure"
    1 -> "processing"
    2 -> "completed"
    else -> "unknown($raw)"
}
