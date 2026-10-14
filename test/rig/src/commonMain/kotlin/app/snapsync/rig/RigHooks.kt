package app.snapsync.rig

import app.snapsync.levers.OsExtensionView
import app.snapsync.services.logs.LogTailService

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
     * the forge moved to its own binary (since deleted) that mode had one case, so the two were the same fact twice.
     */
    private val uploadTier: String,
    /** The baked `uploadBase` — the oracle for "which backend is this build pointed at". */
    private val uploadBase: String,
    /**
     * Which `URLSession` binding this binary compiled — `"background"` or `"default"` — fixed by the
     * compilation target.
     *
     * Reported rather than inferred, because the two are told apart by *what does not happen*: under
     * `"default"` transfers move bytes but die with the process, and a background-events wake expires
     * instead of draining. A caller that had to deduce that from a stall or a log substring would deduce
     * it wrongly, and would then have no way to say which of its assertions its host could actually
     * support.
     */
    private val transferBinding: String,
    /** The device logs' tail, supplied by the shell because `AppCore` does not expose its ports. */
    val deviceLog: suspend (process: LogTailService.Process, maxBytes: Int) -> String?,
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
    /**
     * Told when the bind fails, once its `Error` line has been logged — after which [publishBoundPort] is never called.
     * A host that waits for the port (the JVM host) fails at once with the cause instead of waiting out its bound; a
     * device host has the `Error` line.
     */
    val reportBindFailure: (Throwable) -> Unit = {},
    /**
     * The shared vocabulary entries ([RigVocabulary]) this host REFUSES, each with its reason. Everything this
     * host wires is honoured; everything it refuses is listed here; `GET /device` answers both, and names any
     * entry that is neither (`docs/testing.md`, "One control protocol, served by two hosts").
     */
    val refusals: Map<String, String> = emptyMap(),
    /**
     * Why the OS's extension-registration answer is `null` on this host — reported as `notApplicableReason`.
     * Host-supplied because the reason is the host's: on iOS a selector the OS lacks, on the JVM an OS the
     * world does not model.
     */
    private val osExtensionNotApplicable: String =
        "isUploadJobExtensionEnabled is a 26.1 selector; this OS has none, so the extension " +
            "could never be registered here and `false` would misreport that as `not registered`",
    /**
     * Keeps what a `PUT` landed on the upload receiver — the fixture route and the content type it arrived with — where
     * the process running the upload-job contract can read it: the upload extension, which cannot read this app's
     * memory, reads it from the App Group. A verb for the same reason as [publishBoundPort].
     */
    val recordLanded: (route: String, contentType: String?) -> Unit = { _, _ -> },
    /**
     * This launch's adapter choice, in one line — `/health` reports it (`docs/testing.md`, "Launch-time adapters"): `all real`
     * for an ordinary rig build, the mocked systems otherwise.
     */
    private val adapters: String = "all real",
    /**
     * Why this launch composed NOTHING — an adapter choice the process refused to compose over — or `null` for a launch that
     * composed. When set, the host's own services refuse with it every method that would reach the composed app, and
     * only `/health`, `/device` and what reads or rewrites the launch itself are served (the logs, the disk, the adapter
     * choice): nothing is composed, so nothing real can be reached by a run that believed itself mocked.
     */
    val uncomposed: String? = null,
    /**
     * The typed services (`LeverDispatch.kt`) — every declared one, each with the wrapper the kRPC route registers and its
     * refusals on this host — built from these hooks once they exist: a host's own services read the app through them.
     */
    private val servicesOf: (RigHooks) -> List<ServedService> = { emptyList() },
    /** The world this host serves its callers' acts in (`docs/testing.md`, "How a test waits"). */
    val world: HostWorld = HostWorld.ON_A_DEVICE,
) {
    /** Every typed service this host serves ([servicesOf]). */
    val services: List<ServedService> by lazy { servicesOf(this) }

    /**
     * `/health`'s body.
     *
     * [bootedAt] is reported rather than only logged because of an ordering fact: `SnapSyncRoot`'s init is
     * what installs the Kermit file writer, and the hook deliberately does not touch `SnapSyncRoot` at boot
     * (so a rig build's launch ordering stays identical to production's). Measured on device the writer
     * happened to be installed already — but that ordering is not guaranteed, and a boot observable only in
     * a log that might not have been receiving yet is not observable.
     */
    internal fun health(boundPort: Int?): String = buildString {
        append("rig=up port=").append(boundPort ?: "binding").append('\n')
        append("bootedAt=").append(bootedAt ?: "never").append('\n')
        append("adapters=").append(uncomposed?.let { "REFUSED, nothing composed: $it" } ?: adapters).append('\n')
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
                osExtensionNotApplicable
            } else {
                null
            },
            // A `false` is only interpretable alongside the grant: measured, the OS answers `false` for a
            // live record while access is NOT_DETERMINED.
            grantDependent = enabled == false && grant != "GRANTED",
        )
    }

    /**
     * The build facts, reported by `host.state()` rather than `/health`.
     *
     * They moved because they answer different questions to different readers. `/health` answers "is the
     * channel up" and must be answerable before anything else is known to work; these answer "what is this
     * build" and belong beside the rest of the app's facts, where a caller reading state finds them without
     * a second request.
     */
    internal fun buildFacts(): Map<String, String> = mapOf(
        "uploadTier" to uploadTier,
        "uploadBase" to uploadBase,
        "transferBinding" to transferBinding,
    )
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
