package app.snapsync.rig

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
    /**
     * The mechanism this OS resolves to under a full grant — the OS's tier, a boot-time fact, which is
     * what decides which triggers are even meaningful on this build. Which mechanism is *running* moves
     * with permission and is deliberately not reported here.
     *
     * There used to be a `compositionMode` beside this, rendering the sealed mode the shell resolved. With
     * forge moved to its own binary that mode has one case, so the two were the same fact twice.
     */
    private val uploadTier: String,
    /** The baked `uploadBase` — the oracle for "which backend is this build pointed at". */
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
    /**
     * Wired user commands, by name — the members of `StatusContainerHost`'s public command surface.
     * Coverage against that population is gated exactly as [triggers] is: the set is derivable from source,
     * so a hand-picked subset would be the rot the derivation exists to refuse.
     */
    val userCommands: Map<String, RigUserCommand>,
    /** User commands deliberately NOT wired, each with the reason that makes the omission safe. */
    val excludedUserCommands: Map<String, String>,
    /**
     * The `/device` write commands, by name.
     *
     * Hand-listed, and unlike the two maps above that is not a compromise: there is no population to derive
     * one from. These operations impersonate nobody — nothing in production ever seeds a photo library or
     * voids durable sync state — so the set exists only because a test rig exists.
     */
    val deviceCommands: Map<String, RigCommand>,
    /**
     * The gallery read. Answers the raw subtype census, and — given a cutoff — the selection policy's
     * verdict per asset. Separate from [deviceCommands] because it is a read, and separate from
     * `/device/state` because it can be expensive and, under a partial grant, can surface a system alert.
     */
    val readGallery: suspend (cutoff: String?, resources: Boolean, includesUpload: Boolean) -> String,
    /**
     * The OS's view of the upload-job extension registration, or `null` where the OS has no such selector.
     *
     * Supplied by the host rather than read here, because the read is only safe from a path that exists on
     * the OS-driven tier — `isUploadJobExtensionEnabled` is a 26.1 selector and the app deploys to min iOS
     * 18, so an unconditional call traps. The host is the only side that knows whether it composed such a
     * path.
     */
    val osExtensionEnabled: () -> Boolean?,
    /**
     * Publish the port this instance actually bound, so a caller can discover it instead of assuming it.
     *
     * Called **only after the bind succeeds**, which is the whole point: on a simulator the file's
     * ABSENCE is how a collision stops being silent. All simulators share the host's loopback, so two
     * instances left on [DEFAULT_RIG_PORT] do not merely collide — the second's bind fails while a
     * `curl` reaches the FIRST and answers plausibly, reporting the very port that was asked for. With
     * the file, the second instance simply never publishes one and the reader times out instead of
     * believing the wrong process.
     *
     * A verb rather than a path because `:test:rig` names no platform API (design D5): the host hook
     * supplies the writing.
     */
    val publishBoundPort: (Int) -> Unit,
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
    }

    /**
     * Render the OS's answer with the qualifiers that make it readable.
     *
     * The `when` lives here rather than in the hook file for the usual reason — the hook is compiled into
     * `:app:ios` and scanned by the shell gate, which permits no decisions — and here it is ordinary code.
     */
    internal fun readOsExtension(grant: String): OsExtensionView {
        val enabled = osExtensionEnabled()
        return OsExtensionView(
            enabled = enabled,
            notApplicableReason = if (enabled == null) {
                "isUploadJobExtensionEnabled is a 26.1 selector; this OS has none, so the extension " +
                    "could never be registered here and `false` would misreport that as `not registered`"
            } else {
                null
            },
            // A `false` is only interpretable alongside the grant: measured, the OS answers `false` for a
            // live record while access is NOT_DETERMINED.
            grantDependent = enabled == false && grant != "GRANTED",
        )
    }

    /**
     * The build facts, reported by `/device/state` rather than `/health`.
     *
     * They moved because they answer different questions to different readers. `/health` answers "is the
     * channel up" and must be answerable before anything else is known to work; these answer "what is this
     * build" and belong beside the rest of the app's facts, where a caller reading state finds them without
     * a second request.
     */
    internal fun buildFacts(): Map<String, String> = mapOf(
        "uploadTier" to uploadTier,
        "uploadBase" to uploadBase,
    )
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
     * `/device/state`. `onForeground` is in this group — likely the most-used trigger, and the one whose work
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
 * A wired **user command** — a member of the host's public command surface, the thing a finger reaches.
 *
 * Invoked on the main lane, like a trigger, because that is the lane a tap arrives on. Answered `202`
 * without waiting: these are Orbit intents, which return a `Job`, and the work they start is observed
 * through `/device/state` exactly as the screen observes it. Waiting here would mean inventing a
 * completion signal the UI itself does not have.
 */
class RigUserCommand(val run: (params: Map<String, String>) -> Unit)

/**
 * A `/device` **write**.
 *
 * Two properties distinguish it from a trigger, and both are forced rather than chosen. It **blocks until
 * the work is done and answers with the outcome** — that is the whole reason these moved off launch
 * variables, where a failure was a log line somebody had to go and find. And it runs on the caller's lane,
 * **never** [RigHooks.mainLane]: `performChangesAndWait` blocks, and the launch-time chain it replaces was
 * on `Dispatchers.Default` for exactly that reason.
 *
 * There is deliberately no serialization between commands. Each blocks, so a caller that awaits a response
 * before sending the next is serial by construction; a caller that deliberately fires two destructive
 * commands at its own device concurrently is not a failure mode worth machinery.
 */
class RigCommand(val run: suspend (params: Map<String, String>) -> CommandResult)

/**
 * What a `/device` command answers with.
 *
 * Carries the status rather than only a body, because the refusals here are the point. A wipe handed an
 * unrecognized scope must refuse — the operation cannot be undone, so guessing is not available — and a
 * refusal that arrived as `200` with an `error` field would be a refusal a script could miss, which is the
 * failure the launch-variable form already had.
 */
class CommandResult(val status: Int, val body: String) {
    companion object {
        fun ok(body: String) = CommandResult(status = 200, body = body)

        /** A refusal the caller can act on: what was wrong, and what would have been accepted. */
        fun badRequest(why: String) = CommandResult(status = 400, body = """{"error":"$why"}""")
    }
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
 * Where the bound port is published, given this process's documents directory — `null` when the OS
 * reported none, in which case nothing is written and the caller says so.
 *
 * The decision lives here rather than in the hook for the reason every other decision does: the hook
 * file is compiled into `:app:ios` and scanned by the shell gate, which permits none.
 */
fun rigPortFilePath(documentsDirectory: String?): String? =
    documentsDirectory?.let { "$it/$RIG_PORT_FILE_NAME" }

/**
 * The published-port file's name.
 *
 * Not a runtime-identity pin: no installed base holds it, and a build that renamed it would simply make
 * its own port undiscoverable on the next run — loudly, since the reader finds nothing.
 */
const val RIG_PORT_FILE_NAME: String = "rig.port"
