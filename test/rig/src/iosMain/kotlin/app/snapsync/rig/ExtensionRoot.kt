package app.snapsync.rig

import app.snapsync.levers.ExtensionAnswer
import app.snapsync.levers.ExtensionHostLevers
import app.snapsync.levers.UploadJobLevers
import app.snapsync.logging.FileLogSink
import app.snapsync.logging.IosThreadLabel
import app.snapsync.logging.PublicNSLogSink
import app.snapsync.logging.extensionLogDestination
import app.snapsync.services.logs.SinkLogWriter
import co.touchlab.kermit.Logger
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Driving the **upload extension's** composition root from the control channel — the `/os/photokit-ext/…`
 * group.
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
 * and the extension process has no main lane at all (`docs/architecture.md`, the dispatcher-lane law).
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
 * Run one extension cycle and answer with what it produced. The OS's job sets are the typed `upload-jobs.process`'s to
 * hand in (`uploadJobService`); a body here is refused, naming it.
 */
private suspend fun invokeExtensionCycle(process: () -> Int, body: String?, log: Logger): String {
    if (!body.isNullOrBlank()) {
        return """{"refused":"job sets are handed in through the typed upload-jobs.process, not a request body"}""" + "\n"
    }
    beginStringCycle()
    return endStringCycle(runExtensionCycle(process, log))
}

/** Before a string-entry cycle: on a simulator, a cycle with no finished jobs begun on the played queue. */
internal expect suspend fun beginStringCycle()

/** A string-entry cycle's answer: the raw result, and what the target can report of the queue. */
internal expect suspend fun endStringCycle(raw: Int): String

/**
 * One `process()` of the extension root, on the extension root's lane, its log lines in the extension's log.
 *
 * Not gated on any mechanism: both uploaders may write the one App-Group ledger, so this cycle is just another
 * cycle over it, and the extension root's own admission (a full grant, read in its entry gate) decides what it
 * may do — exactly as it does when the OS invokes it (decision record `changes/both-uploaders-active`, D8).
 *
 * Kermit's writer list is process-global and BOTH composition roots set it in their `init`, so touching the
 * extension root redirects the app's own log into `ext-debug.log` and would silence the app's log for the rest of the
 * process. Snapshot and restore around the call. The cycle's own lines still land in `ext-debug.log`, which is where
 * they belong — `LogTailService` already serves that file as the extension process's log.
 */
internal suspend fun runExtensionCycle(process: () -> Int, log: Logger = Logger.withTag("rig")): Int =
    withContext(extensionLane) {
        val saved = Logger.config.logWriterList
        val raw = try {
            // Point Kermit at the EXTENSION's log for the duration, then hand the app's writers back.
            //
            // Restoring alone is not enough, and the difference was measured. The extension root installs its
            // writers in its `init`, which runs **once** — so with only a restore, the first invoked cycle
            // wrote `ext-debug.log` and every cycle after it wrote the app's `debug.log`, because by then the
            // object was already initialised and nothing re-pointed the writers. Setting them per call makes every
            // invoked cycle land where an extension's cycle lands.
            Logger.setLogWriters(
                SinkLogWriter(listOf(PublicNSLogSink(), FileLogSink(extensionLogDestination().path)), IosThreadLabel),
            )
            process()
        } finally {
            Logger.setLogWriters(saved)
        }
        log.i { "photokit-ext cycle finished: raw=$raw" }
        raw
    }

/**
 * The `extension-host` service: the same invocation as `processRawValue` with no job sets handed in, on the extension
 * root's lane — and its end, on the same lane. The simulator's scripted job sets are `upload-jobs.process`'s.
 */
internal fun extensionHost(
    process: () -> Int,
    terminate: () -> Unit,
    log: Logger = Logger.withTag("rig"),
): ExtensionHostLevers = object : ExtensionHostLevers {
    override suspend fun process(): ExtensionAnswer {
        beginStringCycle()
        val raw = runExtensionCycle(process, log)
        endStringCycle(raw)
        return when (raw) {
            PROCESSING_RAW -> ExtensionAnswer.PROCESSING
            COMPLETED_RAW -> ExtensionAnswer.COMPLETED
            else -> ExtensionAnswer.FAILED
        }
    }

    override suspend fun terminate() = withContext(extensionLane) { terminate() }
}

/** `PHBackgroundResourceUploadProcessingResult`'s raw values the answer distinguishes. */
private const val PROCESSING_RAW = 1
private const val COMPLETED_RAW = 2

/**
 * The upload-job queue this target's channel plays (`UploadJobLevers`): on a simulator the queue is substituted and the
 * caller plays the OS — handing each `process()` its job sets ([process] is the extension root's), performing a job's
 * bytes, planting the registration record — unless the launch mocks the queue or the registration, whose own levers
 * play them; on a device the OS holds the queue itself, and every verb is refused.
 */
internal expect fun uploadJobService(world: MockWorld, process: () -> Int): HostService<UploadJobLevers>

/**
 * How a `PHBackgroundResourceUploadProcessingResult` raw value reads back — the inverse of the tested
 * `ProcessingAnswer.processingResultRawValue()` mapping, for the caller's benefit only.
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
