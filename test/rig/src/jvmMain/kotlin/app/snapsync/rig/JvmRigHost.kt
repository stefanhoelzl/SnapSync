@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.rig

import app.snapsync.contracts.EntryDriver
import app.snapsync.compose.extensionEntries
import app.snapsync.model.InviteLinkHints
import app.snapsync.model.WakeId
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
            val screen = Screen(world)
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
            val extension = extensionEntries(ports = { world.uploadPorts }, cycle = { world.cycle })
            return RigHooks(
                bootedAt = Clock.System.now().toString(),
                uploadTier = "world",
                uploadBase = world.host,
                transferBinding = "world",
                mainLane = lane,
                deviceLog = worldLog(world),
                triggerGroups = mapOf(
                    "app" to TriggerGroup(lane = lane, wired = appTriggers(WorldEntryDriver(world)), excluded = emptyMap()),
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
                userCommands = userCommands(dispatch = { world.ui.tap(it) }, state = { world.statusHost.container.stateFlow.value }),
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
 * What the phone's UI does that nothing else here does: it builds a live screen, whose `onLive` assembles the status
 * host and starts showing it every state — and the host's Orbit container starts its reduction only once its state is
 * collected, so a host nobody watches stays on its initial frame. [show] builds one at start, and again after every
 * relaunch, whose new app no screen has been built for.
 */
internal class Screen(private val world: World) {
    fun show() = world.ui.live()
}

/**
 * The world's operating system, driven (`docs/testing.md`, "The control channel"): each delivery through the world's
 * entry doubles, as the iOS adapters deliver them. The heartbeat's task wakes the world's `Wake`; any other task
 * identifier is answered at once, unknown. The app uploader's session hands its events back through the world's
 * upload session, every other identifier through the download session — the iOS adapter's routing.
 */
internal class WorldEntryDriver(private val world: World) : EntryDriver {
    override fun foreground() = world.lifecycle.foreground()

    override fun background() = world.lifecycle.background()

    override fun pushToken(hex: String) = world.pushNotifications.deliverToken(hex)

    override fun pushTokenFailure(description: String) = world.pushNotifications.deliverTokenFailure(description)

    override fun silentPush(eventId: String?, done: () -> Unit) =
        world.pushNotifications.deliverMessage(mapOf("eventId" to eventId), rigCompletion(done))

    override fun continueLink(url: String) = world.links.open(url)

    override fun backgroundTask(identifier: String, done: () -> Unit) {
        if (identifier == JvmRigHost.UPLOAD_HEARTBEAT_TASK) world.wake.fire(WakeId.Heartbeat, rigCompletion(done)) else done()
    }

    override fun backgroundTransfers(identifier: String, done: () -> Unit) {
        if (identifier == JvmRigHost.UPLOAD_TRANSFER_CHANNEL) {
            world.appUpload.handBack(rigCompletion(done))
        } else {
            world.download.handBack(rigCompletion(done))
        }
    }
}
