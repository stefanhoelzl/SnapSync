package app.snapsync.rig

import app.snapsync.model.CompositionMode
import app.snapsync.ports.DeviceLogSource
import kotlin.coroutines.CoroutineContext

/**
 * What the host shell hands the rig: launch-time **facts** as data, platform **verbs** as lambdas.
 *
 * This is the seam that keeps the rig portable **at the map, not at the call** (design D5): the rig core
 * names no platform API, while each trigger invokes the host's real entry point with full fidelity. A
 * second platform brings its own hook; the server, the routes and the state projection are unchanged.
 *
 * Everything here is either a plain value or a lambda, deliberately. The hook file that constructs it is
 * compiled into `:app:ios` and therefore scanned by the shell gate, which permits **no decisions** — so
 * every default, fallback, cast and rendering lives on this side of the seam, where it is ordinary code
 * in an ungated module. That split is not incidental: it is what keeps the shell's decision-free
 * guarantee true of the rig's footprint too.
 */
class RigHooks(
    /** Wall-clock instant the eager initializer ran, or `null` if the hook never reached it. */
    private val bootedAt: String?,
    /** The resolved `CompositionMode`, rendered by the shell (a launch-time constant, not a read-model). */
    private val compositionMode: String,
    /** The resolved `UploadTier`. Decides which triggers are even meaningful on this build. */
    private val uploadTier: String,
    /** The baked `BackgroundUploadURLBase` — the oracle for "which backend is this build pointed at". */
    private val uploadBase: String,
    /**
     * The lane platform entry points are invoked on. Swift calls them from the **main** thread, so the rig
     * does too — a trigger that ran on a different lane would not be the same call the OS makes, which is
     * the whole reason triggers are entry points rather than `flow/` classes.
     */
    val mainLane: CoroutineContext,
    /** The device-log port, supplied by the shell because `AppCore` does not expose its ports. */
    val deviceLog: DeviceLogSource,
    /** Wired triggers, by name. Coverage against the `@PlatformEntry` population is gated, not curated. */
    val triggers: Map<String, RigTrigger>,
    /** Entry points deliberately NOT wired, each with the reason that makes the omission safe. */
    val excludedTriggers: Map<String, String>,
) {

    /**
     * `/health`'s body.
     *
     * [bootedAt] is reported rather than only logged because of an ordering fact: `SnapSyncRoot`'s init is
     * what installs the Kermit file writer, and the hook deliberately does not touch `SnapSyncRoot` at boot
     * (so a rig build's launch ordering stays identical to production's). Measured on device the writer
     * happened to be installed already — but that ordering is not guaranteed, and a boot observable only in
     * a log that might not have been receiving yet is not observable.
     */
    internal fun health(boundPort: Int): String = buildString {
        append("rig=up port=").append(boundPort).append('\n')
        append("bootedAt=").append(bootedAt ?: "never").append('\n')
        append("compositionMode=").append(compositionMode).append('\n')
        append("uploadTier=").append(uploadTier).append('\n')
        append("uploadBase=").append(uploadBase).append('\n')
    }
}

/**
 * A wired platform entry point.
 *
 * The split is **derived from the platform contract, not chosen by us**: an entry the OS hands a
 * completion handler is one the OS waits on, and the rig — playing the OS — supplies that handler and
 * waits too. An entry the OS does not wait on is answered immediately, because waiting there would make
 * the rig-driven path differ from production in timing and interleaving, which is exactly the fidelity
 * that made entry points the right vocabulary in the first place.
 */
sealed interface RigTrigger {

    /**
     * The platform hands this entry no completion handler. Invoked and answered `202`; the caller polls
     * `/state`. `onForeground` is in this group — likely the most-used trigger, and the one whose work
     * escapes into launched coroutines by design.
     */
    class Fire(val run: (arg: String?) -> Unit) : RigTrigger

    /**
     * The platform hands this entry an OS completion handler, and the app already wraps it in `OsReceipt`.
     * The rig supplies [run]'s `done` lambda, so it does not *detect* completion — it **receives** it, on
     * the same channel the OS does. [deadlineMs] is the receipt's own bound, reported alongside the
     * measured hold so the caller has both numbers.
     *
     * The rig classifies nothing. `OsReceipt.release` carries no outcome — both the completion path and
     * the expiry path call it with no argument — so `settled` versus `deadline-expired` is not recoverable
     * here, and inferring it from `heldMs ≈ deadlineMs` would be a guess in an ambiguous band. The
     * authoritative answer is production's own: `OsReceipt` logs its expiry line on the expiry path and no
     * other, readable through `/logs` after the `[rig]` marker.
     */
    class Receipted(val deadlineMs: Long, val run: (arg: String?, done: () -> Unit) -> Unit) : RigTrigger
}

/**
 * The port to bind: `SNAPSYNC_RIG_PORT` when it parses, else [DEFAULT_RIG_PORT].
 *
 * Takes the raw environment value as `Any?` because that is what `NSProcessInfo`'s environment map yields,
 * and because the cast, the parse and the fallback are all decisions — which the shell gate forbids in the
 * hook file that calls this.
 *
 * An unparseable value falls back silently and deliberately: the channel is reachable either way, and
 * `/health` reports the port actually bound, so a typo surfaces as "not the port I asked for" on the first
 * request rather than as a failure to start.
 */
fun rigPort(raw: Any?): Int = (raw as? String)?.toIntOrNull() ?: DEFAULT_RIG_PORT

/**
 * The resolved upload tier's name, or `forge` when no live stack was composed.
 *
 * Lives here rather than in the hook for the usual reason: the `when` is a decision, and the hook file is
 * scanned by the shell gate. Reads the mode the shell already resolved — never a second resolution, which
 * could disagree with the one the app is actually running.
 */
fun tierName(mode: CompositionMode): String = when (mode) {
    is CompositionMode.Live -> mode.tier.name
    is CompositionMode.Forge -> "forge"
}
