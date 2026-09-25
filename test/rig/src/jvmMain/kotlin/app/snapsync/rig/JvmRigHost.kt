@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.rig

import app.snapsync.compose.EntryHooks
import app.snapsync.compose.extensionEntries
import app.snapsync.compose.platformEntries
import app.snapsync.model.InviteLinkHints
import app.snapsync.ports.DeviceLogSource
import app.snapsync.world.DenoBackend
import app.snapsync.world.MiniEdgeBackend
import app.snapsync.world.World
import app.snapsync.world.WorldBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The control channel's **JVM host** (`docs/testing.md`, "One control protocol, served by two
 * hosts"): the unchanged [RigServer], over a [World] whose `core` is the real `AppCore` from the same
 * `snapSyncApp` the iOS shell calls.
 *
 * A hook, not a second server — exactly the extension `RigHooks` was shaped for ("a second platform brings its own
 * hook; the server, the routes and the state projection are unchanged"). What this file adds is only what the iOS
 * shell adds on its side: the composition lane, the status host over the core's read-models, the inbound ports'
 * implementations the `/os` verbs invoke, and this host's classification of the shared vocabulary.
 *
 * The world is composed on a **serial, non-UI** lane, the structure the device shell uses and the full-stack
 * harness mirrors (`docs/testing.md`, "The harness composes the live core on the shipped lane
 * structure"); the app root's entry points are invoked on that lane, as Swift invokes them on main.
 */
class JvmRigHost private constructor(
    /**
     * The world behind the channel. `internal`, so this host's public surface names no world type: a protocol
     * client reaches the world only through the protocol (`docs/architecture.md`).
     */
    internal val world: World,
    /** The loopback port the server actually bound. */
    val port: Int,
    private val server: RigServer,
    private val scope: CoroutineScope,
    private val lane: kotlinx.coroutines.CloseableCoroutineDispatcher,
) : AutoCloseable {

    /** Stop serving and tear the world down. The backend process, when there is one, is the JVM's, not this host's. */
    override fun close() {
        server.stop()
        scope.cancel()
        lane.close()
    }

    companion object {
        /** A generous bound on binding: a failure to bind is a stated error, never a hang. */
        private val BIND_TIMEOUT = 30.seconds

        /**
         * Compose a world over the backend named [backend] — `mini` (the mini-edge) or `deno` (the real `api/`,
         * through `:test:edge`) — and serve it on loopback [port]. `0` asks the OS for a free port, which is what
         * a test wants, since hosts share the machine's loopback. Returns once the server has bound.
         */
        suspend fun start(backend: String = "mini", port: Int = 0): JvmRigHost = start(backendNamed(backend), port)

        internal fun backendNamed(name: String): WorldBackend = when (name) {
            "mini" -> MiniEdgeBackend()
            "deno" -> DenoBackend()
            else -> error("the JVM rig host's backend must be mini|deno, was '$name'")
        }

        @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
        internal suspend fun start(backend: WorldBackend, port: Int): JvmRigHost {
            val lane = newSingleThreadContext("rig-jvm-composition")
            val scope = CoroutineScope(SupervisorJob() + lane)
            val world = withContext(lane) { compose(scope, backend) }
            val screen = Screen(scope, world)
            screen.show()
            val bound = CompletableDeferred<Int>()
            val server = RigServer(
                core = { world.core },
                // Read per request, never captured: a relaunch replaces the world's app, and its host with it.
                host = { world.statusHost },
                hooks = jvmHooks(world, lane, screen, publishBoundPort = { bound.complete(it) }),
                port = port,
            )
            server.start()
            val actual = try {
                withTimeout(BIND_TIMEOUT) { bound.await() }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                server.stop()
                scope.cancel()
                lane.close()
                throw IllegalStateException(
                    "the JVM rig host did not bind loopback:$port within $BIND_TIMEOUT — see the `[rig]` log line",
                    timeout,
                )
            }
            return JvmRigHost(world, actual, server, scope, lane)
        }

        private fun compose(scope: CoroutineScope, backend: WorldBackend): World {
            // Attesting over the mini-edge, as a device attests; not over the real backend, whose local serve
            // attaches a dev fallback credential and models no attestation exchange.
            // Invite-link hints honoured, as the rig's boot hook sets them on a device: this host IS the control
            // channel, whose callers join headlessly with `autoJoin` (capability `join-event`).
            val world = World(
                scope,
                backend = backend,
                attests = backend is MiniEdgeBackend,
                inviteLinkHints = InviteLinkHints.Honoured,
            )
            // A minted event opens THIS host's join gate, as the iOS shell routes it — so `/user/create` is followed
            // by `/user/confirmJoin`, the same two steps a person and the app host take.
            world.onEventMinted = { eventId -> world.statusHost.onEventCreated(eventId) }
            // Host assembly, by the shared host composition, exactly as the iOS shell performs it.
            world.statusHost
            return world
        }

        private fun jvmHooks(
            world: World,
            lane: kotlinx.coroutines.CoroutineDispatcher,
            screen: Screen,
            publishBoundPort: (Int) -> Unit,
        ): RigHooks {
            val entries = platformEntries(
                core = { world.core },
                hooks = EntryHooks(
                    markActive = {},
                    openUrl = { url -> world.statusHost.onOpenUrl(url) },
                    assembleHost = {},
                    deliverPushToken = { hex -> world.pushTokens.deliver(hex) },
                    // The iOS identifiers, so a test passes the same argument to either host.
                    uploadHeartbeatTaskId = UPLOAD_HEARTBEAT_TASK,
                    uploadTransferChannel = UPLOAD_TRANSFER_CHANNEL,
                ),
            )
            val extension = extensionEntries(ports = { world.uploadPorts }, cycle = { world.cycle })
            return RigHooks(
                bootedAt = Clock.System.now().toString(),
                uploadTier = "world",
                uploadBase = world.host,
                transferBinding = "world",
                mainLane = lane,
                deviceLog = worldLog(world),
                triggerGroups = mapOf(
                    "app" to TriggerGroup(lane = lane, wired = appTriggers(entries), excluded = emptyMap()),
                    "photokit-ext" to TriggerGroup(
                        // The extension process has no main lane: its root runs on the OS-invoked thread.
                        lane = Dispatchers.Default,
                        wired = mapOf(
                            "processRawValue" to RigTrigger.Answering { _, body ->
                                if (body != null) {
                                    """{"refused":"the world's upload-job queue is the world's own; job sets cannot be handed in","queue":"world"}""" + "\n"
                                } else {
                                    val result = extension.process()
                                    """{"result":"${result.toString().lowercase()}","queue":"world",""" +
                                        """"created":${world.platform.created.size}}""" + "\n"
                                }
                            },
                            "onTerminate" to RigTrigger.Fire { extension.onTerminate() },
                        ),
                        excluded = emptyMap(),
                    ),
                ),
                userCommands = userCommands { world.statusHost },
                excludedUserCommands = excludedUserCommands(),
                deviceCommands = worldDeviceCommands(world, afterRelaunch = screen::show),
                readGallery = worldGalleryReader(world),
                osExtensionEnabled = { null },
                publishBoundPort = publishBoundPort,
                contracts = emptyList(),
                refusals = jvmRefusals(),
                osExtensionNotApplicable =
                    "the world composes an operating system without the OS-driven upload mechanism, so there is " +
                        "no extension registration to report",
            )
        }

        private fun appTriggers(entries: app.snapsync.ports.PlatformEntries): Map<String, RigTrigger> = mapOf(
            "onForeground" to RigTrigger.Fire { entries.onForeground() },
            "onBackground" to RigTrigger.Fire { entries.onBackground() },
            "onPushToken" to RigTrigger.Fire { arg -> entries.onPushToken(arg.orEmpty()) },
            // The app host's warm universal link; its destination is the inbound port's open-URL entry, which is
            // what the iOS shell reaches after decoding the activity. Same argument: the link.
            "onSceneContinueActivity" to RigTrigger.Fire { arg -> entries.onOpenUrl(arg.orEmpty()) },
            "onSilentPush" to RigTrigger.Receipted { arg, done ->
                entries.onSilentPush(mapOf("eventId" to arg), done)
            },
            "onBackgroundTask" to
                RigTrigger.Receipted { arg, done ->
                    entries.onBackgroundTask(arg.orEmpty(), done)
                },
            "onBackgroundTransfers" to
                RigTrigger.Receipted { arg, done ->
                    entries.onBackgroundTransfers(arg.orEmpty(), done)
                },
        )

        /** The world's captured log as the `app` process's; the world runs no extension process of its own. */
        private fun worldLog(world: World) = object : DeviceLogSource {
            override suspend fun tail(process: DeviceLogSource.Process, maxBytes: Int): String? = when (process) {
                DeviceLogSource.Process.APP -> world.logs.lines.joinToString("\n").takeLast(maxBytes)
                DeviceLogSource.Process.EXTENSION -> null
            }
        }

        const val UPLOAD_HEARTBEAT_TASK = "app.snapsync.upload.heartbeat"
        const val UPLOAD_TRANSFER_CHANNEL = "app.snapsync.upload.session"
    }
}

/**
 * What the phone's UI does to its status host that nothing else here does: it OBSERVES it. The host's Orbit container
 * starts its reduction only once its state is collected (or an intent arrives), so a host nobody watches stays on its
 * initial frame. [show] collects the world's current host for as long as the world runs that app — at start, and again
 * after every relaunch, whose new host the dead app's collector never saw.
 */
internal class Screen(private val scope: CoroutineScope, private val world: World) {
    private var watching: kotlinx.coroutines.Job? = null

    fun show() {
        watching?.cancel()
        val host = world.statusHost
        watching = scope.launch { host.container.stateFlow.collect { } }
    }
}
