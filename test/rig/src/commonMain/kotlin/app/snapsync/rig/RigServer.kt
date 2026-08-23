package app.snapsync.rig

import app.snapsync.compose.AppCore
import app.snapsync.ports.DeviceLogSource
import app.snapsync.presentation.StatusContainerHost
import co.touchlab.kermit.Logger
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

/**
 * The **loopback address, and the only bind address this module may name** (capability
 * `architecture-guards`, "A dev/test control channel binds the loopback address only").
 *
 * The channel forces OS callbacks and exposes event state, and it runs on a phone attached to whatever
 * network it happens to be on. Widening this is a one-token edit that reads as fixing a connectivity
 * problem and looks nothing like a security decision — which is why a guard asserts this module names no
 * other address, rather than leaving it to review.
 */
internal const val LOOPBACK = "127.0.0.1"

/**
 * The default device port. **Device-only**: one instance of the app runs per device, and the host side
 * picks its own local port freely via `usbmux forward`, so a fixed value needs no discovery step. All
 * simulators on a host SHARE the host's loopback, so a simulator host MUST override it per instance
 * (`SNAPSYNC_RIG_PORT`, read in the hook — where it costs production nothing, because the file reading it
 * does not exist in a non-rig build).
 */
const val DEFAULT_RIG_PORT: Int = 18099

private val json = Json { encodeDefaults = true; prettyPrint = true }

/**
 * The dev/test **control channel**: an HTTP server running inside the app so an agent can force
 * OS-callback entry points and read live state, over `pymobiledevice3 usbmux forward` + `curl`.
 *
 * Linked into `:app:ios` ONLY under `-Psnapsync.rig=true`; a production build contains none of this.
 * Dev infrastructure, non-gating, no spec — the same posture as `:test:harness-driver`, and honest for
 * the same reason it states: every surface here is a mechanical projection of a contract that already
 * exists elsewhere, so there is no second way-to-drive that can rot or lie.
 *
 * ## The core arrives as a THUNK, never a value
 * [core] and [host] are thunks on purpose. `SnapSyncRoot.app` and `.host` are `by lazy` deliberately —
 * *"nothing resolves the device identity or opens a protected store earlier than before (the
 * locked-background-launch property)"* — and touching `host` calls `installPermissionSubscriptions()`,
 * which `ios-app-shell` has a scenario forbidding on a cold background wake. So binding the socket must
 * force **nothing**: a rig build's launch behaves exactly like production, and the graph is forced by the
 * first request, which forces precisely what a real entry point would.
 *
 * ## Its own lane
 * The server runs on a dedicated single thread — never the composition lane, never main. A blocked
 * request must not be able to eat the lane the core serializes its work on. Entry points are then invoked
 * on [RigHooks.mainLane], because that is the thread Swift calls them from.
 *
 * ## No request timeout
 * Nothing here bounds a request below the receipts' own deadlines (the download backstop's is 120 s), or
 * a transport timeout would become indistinguishable from a receipt that expired — the same
 * absence-collapse this design guards against everywhere else, reintroduced where nobody would look.
 */
class RigServer(
    private val core: () -> AppCore,
    private val host: () -> StatusContainerHost,
    private val hooks: RigHooks,
    private val port: Int = DEFAULT_RIG_PORT,
    private val log: Logger = Logger.withTag("rig"),
) {

    private val lane = newFixedThreadPoolContext(nThreads = 1, name = "snapsync-rig")
    private val scope = CoroutineScope(SupervisorJob() + lane)

    /**
     * Bind and serve. Returns immediately; the server runs on [lane] for the life of the process.
     *
     * A bind failure is logged at `Error` and **swallowed**: the rig must never be able to break the app
     * under test. But it must not be silent either — a refused connection on the host side is ambiguous
     * between "app not running", "port forward not set up" and "rig failed to bind", and this line, which
     * is pullable without the rig, is the only thing that separates them.
     */
    fun start() {
        scope.launch {
            try {
                log.i { "listening on $LOOPBACK:$port" }
                embeddedServer(CIO, port = port, host = LOOPBACK) { routes() }.start(wait = true)
            } catch (t: Throwable) {
                log.e(t) {
                    "bind $LOOPBACK:$port FAILED — the rig is NOT listening. A previous instance of the " +
                        "app is probably still alive holding the port; SIGKILL it " +
                        "(`dvt process-id-for-bundle-id app.snapsync`, then `dvt signal <pid> 9`). " +
                        "The app itself is unaffected."
                }
            }
        }
    }

    private fun Application.routes() {
        // Namespaced by WHO is on the other side of the call: `/os` is what the platform invokes, `/user`
        // is what a finger reaches, `/device` is the machine under test. That is not taxonomy for its own
        // sake — it decides how each namespace can be held honest. `/os` and `/user` have populations
        // sitting in source (`@PlatformEntry` members; the host's public command surface), so a guard
        // derives them and a hand-picked list would rot. `/device` has no population to derive from.
        routing {
            get("/health") { call.traced { call.respondText(hooks.health(port)) } }

            post("/os/{name}") { call.traced { call.respondTrigger() } }
            post("/user/{name}") { call.traced { call.respondUserCommand() } }

            get("/device/state") { call.traced { call.respondState() } }
            get("/device/logs") { call.traced { call.respondLogs() } }
            get("/device/gallery") { call.traced { call.respondGallery() } }
            post("/device/{name...}") { call.traced { call.respondDeviceCommand() } }
        }
    }

    /**
     * Every request writes a `[rig]` enter/exit pair through Kermit, exactly as every `@PlatformEntry`
     * member does.
     *
     * Two payoffs. Attribution: a rig-driven trigger is otherwise indistinguishable in `debug.log` from an
     * OS-driven one — which is the point of driving the real entry point, and also the ambiguity the
     * entry-point guard exists to remove on the OS side. And a **log cursor**: "what happened since the
     * trigger" is everything after the marker, so no caller needs rig-side offset state to read `/logs`.
     */
    private suspend fun ApplicationCall.traced(block: suspend () -> Unit) {
        log.i { "→ ${request.uri}" }
        val mark = TimeSource.Monotonic.markNow()
        try {
            block()
        } catch (t: Throwable) {
            log.w(t) { "request failed: ${request.uri}" }
            respondText("rig error: ${t.message ?: t}\n", status = HttpStatusCode.InternalServerError)
        } finally {
            log.i { "← ${request.uri} (${mark.elapsedNow().inWholeMilliseconds}ms)" }
        }
    }

    private suspend fun ApplicationCall.respondState() =
        respondText(json.encodeToString(RigState.serializer(), readState(core(), host(), hooks)))

    /**
     * `/logs?process=app|extension&bytes=N` — a pass-through to [DeviceLogSource.tail].
     *
     * The port answers `null` for "no such log" and "could not read it" alike, having decided those are
     * identical downstream. They are **not** identical here: an empty `200` would read as "the log is
     * empty", so the absence is reported as a stated reason with a non-2xx status instead. Re-collapsing
     * an absence the port was careful to keep distinct is the failure this codebase keeps paying for.
     */
    private suspend fun ApplicationCall.respondLogs() {
        val which = request.queryParameters["process"] ?: "app"
        val bytes = request.queryParameters["bytes"]?.toIntOrNull() ?: DEFAULT_LOG_BYTES
        val process = LOG_PROCESSES[which]
            ?: return respondText(
                "unknown process '$which' — expected one of ${LOG_PROCESSES.keys.joinToString("|")}\n",
                status = HttpStatusCode.BadRequest,
            )
        val tail = hooks.deviceLog.tail(process, bytes)
            ?: return respondText(
                "no log for process=$which: it does not exist on this device, or it could not be read\n",
                status = HttpStatusCode.NotFound,
            )
        respondText(tail)
    }

    /**
     * The ROUTE segment, read from the request PATH and never from `parameters`.
     *
     * `call.parameters` merges the path and the query, so a command carrying a query argument of the same
     * name shadows its own route. Measured on device: `POST /user/create?name=RigCheck2` answered
     * `404 unknown user command 'RigCheck2'` — the route had resolved to the value of the query parameter
     * rather than the path segment, so the command could not be reached at all.
     *
     * Reading the path is not merely the fix for `name`; it is the shape that cannot recur. A route
     * placeholder and a query argument live in different namespaces, and any code that reads them from one
     * merged bag will collide again the next time a command needs an argument called after its own
     * placeholder.
     */
    private fun ApplicationCall.routeName(prefix: String): String =
        request.path().removePrefix(prefix).trim('/')

    /**
     * `POST /user/{name}?…` — invoke a real user command on the main lane.
     *
     * Answered `202` without waiting, because the command IS an Orbit intent: it returns a `Job`, and its
     * effect is observed through `/device/state`, which is the same way the screen observes it. Inventing a
     * completion signal here would be inventing one the UI does not have.
     */
    private suspend fun ApplicationCall.respondUserCommand() {
        val name = routeName("/user")
        val command = hooks.userCommands[name]
            ?: return respondText(
                excludedOrUnknown(name, hooks.excludedUserCommands, "user command"),
                status = HttpStatusCode.NotFound,
            )
        val params = request.queryParameters.entries().associate { it.key to it.value.first() }
        withContext(hooks.mainLane) { command.run(params) }
        respondText(
            "{\"command\":\"$name\",\"accepted\":true,\"waited\":false," +
                "\"note\":\"a user command is an intent, exactly as a tap is — poll /device/state\"}\n",
            status = HttpStatusCode.Accepted,
        )
    }

    /**
     * `POST /device/{name}?…` — run a device command to completion and answer with what it did.
     *
     * NOT on [RigHooks.mainLane]: these block (a photo-library change block waits on the system's own
     * confirmation, which can sit unanswered for minutes), and the launch-time chain they replace ran on
     * `Dispatchers.Default` for that reason. Blocking here is the point — a launch variable that failed was
     * a log line to go and find, and a command that answers is the whole reason these moved.
     */
    private suspend fun ApplicationCall.respondDeviceCommand() {
        val name = routeName("/device")
        val command = hooks.deviceCommands[name]
            ?: return respondText(
                excludedOrUnknown(name, emptyMap(), "device command"),
                status = HttpStatusCode.NotFound,
            )
        val params = request.queryParameters.entries().associate { it.key to it.value.first() }
        val result = command.run(params)
        respondText(result.body, status = HttpStatusCode.fromValue(result.status))
    }

    /**
     * `GET /device/gallery?cutoff=…&resources=…` — the library, read through the app's own policy.
     *
     * Its own route rather than a field of `/device/state`, for two reasons that both bite. Enumerating is
     * expensive where reading state is not, and a caller polling state must not pay for it. And under a
     * partial grant a `PHAsset` fetch can surface iOS's own limited-access alert — a `GET` that puts a modal
     * on the device is surprising enough that it should at least be a `GET` the caller asked for by name.
     */
    private suspend fun ApplicationCall.respondGallery() {
        val cutoff = request.queryParameters["cutoff"]
        val resources = request.queryParameters["resources"].toBoolean()
        respondText(hooks.readGallery(cutoff, resources))
    }

    /**
     * `POST /trigger/{name}?arg=…` — invoke the real entry point on the main lane, and answer with
     * whatever the platform gives back.
     */
    private suspend fun ApplicationCall.respondTrigger() {
        val name = routeName("/os")
        val arg = request.queryParameters["arg"]
        val trigger = hooks.triggers[name]
            ?: return respondText(
                excludedOrUnknown(name, hooks.excludedTriggers, "entry point"),
                status = HttpStatusCode.NotFound,
            )
        when (trigger) {
            is RigTrigger.Fire -> {
                withContext(hooks.mainLane) { trigger.run(arg) }
                respondText(
                    "{\"trigger\":\"$name\",\"accepted\":true,\"waited\":false," +
                        "\"note\":\"the platform hands this entry no completion handler, so neither does " +
                        "the rig — poll /state\"}\n",
                    status = HttpStatusCode.Accepted,
                )
            }
            is RigTrigger.Receipted -> {
                val released = CompletableDeferred<Unit>()
                val mark = TimeSource.Monotonic.markNow()
                // `complete` is safe from any thread and is idempotent, so the OS handler's
                // at-most-once guarantee (`OsReceipt.releaseOnce`) needs no help here — and a second
                // call is tolerated rather than thrown, since throwing inside an OS handler lambda has
                // no good owner.
                withContext(hooks.mainLane) { trigger.run(arg) { released.complete(Unit) } }
                released.await()
                val held = mark.elapsedNow().inWholeMilliseconds
                respondText(
                    "{\"trigger\":\"$name\",\"heldMs\":$held,\"deadlineMs\":${trigger.deadlineMs}," +
                        "\"note\":\"heldMs and deadlineMs are measured facts; whether the receipt was " +
                        "released on completion or on its deadline is answered by the OsReceipt expiry " +
                        "line in /logs after this request's [rig] marker\"}\n",
                )
            }
        }
    }

    /**
     * The 404 body, which is the surface that replaced the inventory routes.
     *
     * Dropping `GET /triggers` cost nothing, because enumeration was never the part that carried
     * information — the **reason** an excluded member is excluded was, and it is returned right here, to
     * the caller who asked for that member. An inventory would have been a third copy of a list that the
     * runbook already holds and a guard already pins to source, and the only copy nothing could hold.
     */
    private fun excludedOrUnknown(name: String, excluded: Map<String, String>, kind: String): String =
        excluded[name]
            ?.let { "$kind '$name' is deliberately NOT wired: $it\n" }
            ?: "unknown $kind '$name' — see the `rig-channel` skill for what is driveable\n"

    private companion object {
        /** ~200 KB: comfortably more than a single cycle's lines, well under the port's 10 MB roll cap. */
        const val DEFAULT_LOG_BYTES = 200_000
        val LOG_PROCESSES = mapOf(
            "app" to DeviceLogSource.Process.APP,
            "extension" to DeviceLogSource.Process.EXTENSION,
        )
    }
}
