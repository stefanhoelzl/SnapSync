@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.rig

import app.snapsync.jvm.JvmApp
import app.snapsync.jvm.JvmMocks
import app.snapsync.mock.BuildInfoMock
import app.snapsync.mock.DeclaredVersion
import app.snapsync.mock.UploadNetwork
import app.snapsync.model.InviteLinkHints
import app.snapsync.model.StoreKind
import app.snapsync.model.StoreLink
import app.snapsync.model.uploadersCarried
import app.snapsync.services.logs.LogTailService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The control channel's **JVM host** (`docs/testing.md`, "One control protocol, served by two hosts"): the unchanged
 * [RigServer], over the app the JVM root composes (`:app:jvm`'s [JvmApp] — the same `snapSyncHost` the iOS root
 * calls) over the device as mocks ([JvmMocks]).
 *
 * A hook, not a second server. What this host adds is only what the iOS rig adds on its side: the composition lane,
 * the OS driven through the mocks' operator faces ([MockEntryDriver] for `/os`), the `/device` levers and reads —
 * each a mock's operator face, bar the few named in [JvmRigCommands] — and this host's classification of the shared
 * vocabulary.
 *
 * The app is composed on a **serial, non-UI** lane, the structure the device shell uses; its entry points are invoked
 * on that lane, as Swift invokes them on main.
 */
class JvmRigHost private constructor(
    /** The composed app and the device it runs on. `internal`: a protocol client reaches it only through the protocol. */
    internal val rig: JvmRig,
    /** The loopback port the server actually bound. */
    val port: Int,
    private val server: RigServer,
    private val scope: CoroutineScope,
    private val lane: kotlinx.coroutines.CloseableCoroutineDispatcher,
) : AutoCloseable {

    /** Stop serving and tear the app down. The backend process, when there is one, is the JVM's, not this host's. */
    override fun close() {
        server.stop()
        scope.cancel()
        lane.close()
    }

    companion object {
        /** A generous bound on binding: a failure to bind is a stated error, never a hang. */
        private val BIND_TIMEOUT = 30.seconds

        /**
         * Compose the app over the backend named [backend] — `mock` (the in-memory backend mock) or `deno` (the real
         * `api/`, through `:test:edge`) — and serve it on loopback [port]. `0` asks the OS for a free port, which is what
         * a test wants. Returns once the server has bound.
         */
        suspend fun start(backend: String = "mock", port: Int = 0): JvmRigHost = start(RigBackend.named(backend), port)

        @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
        internal suspend fun start(backend: RigBackend, port: Int): JvmRigHost {
            val lane = newSingleThreadContext("rig-jvm-composition")
            val scope = CoroutineScope(SupervisorJob() + lane)
            val rig = withContext(lane) { compose(scope, backend, lane) }
            val bound = CompletableDeferred<Int>()
            val server = RigServer(
                core = { rig.app.core },
                // Read per request, never captured: a relaunch replaces the app, and its host with it.
                host = { rig.app.host },
                hooks = jvmHooks(rig, publishBoundPort = { bound.complete(it) }),
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
            return JvmRigHost(rig, actual, server, scope, lane)
        }

        private fun compose(scope: CoroutineScope, backend: RigBackend, lane: kotlinx.coroutines.CoroutineDispatcher): JvmRig {
            // Invite-link hints honoured, as the rig's development controls answer them on a device: this host IS the
            // control channel, whose callers join headlessly with `autoJoin` (capability `join-event`).
            val mocks = JvmMocks(inviteLinkHints = InviteLinkHints.Honoured, network = backend.network)
            val version = DeclaredVersion(SERVED_VERSION)
            val log = RecordedLog()
            val build = BuildInfoMock(
                uploadHost = backend.base,
                declaredVersion = version,
                dsn = DSN,
                store = StoreLink(APP_STORE_URL, StoreKind.APP_STORE),
                apnsEnvironment = "sandbox",
            )
            val app = JvmApp(scope, mocks) { device ->
                device.adapters(build, attests = backend.attests, backend = backend.port(device, version), logSinks = listOf(log))
            }
            val network = backend.network ?: UploadNetwork { url, headers, _ -> mocks.backend.operator.receive(url, headers) }
            val rig = JvmRig(
                app, backend, version, log, lane, scope,
                BackendReach(backend.base, backend.name, backend.port(mocks, version), network, version),
                PlayedOs(mocks),
            )
            rig.showScreen()
            return rig
        }

        private fun jvmHooks(rig: JvmRig, publishBoundPort: (Int) -> Unit): RigHooks {
            val mocks = rig.mocks
            return RigHooks(
                bootedAt = Clock.System.now().toString(),
                uploadTier = uploadersCarried(osSupportsOsDrivenUpload = false),
                uploadBase = rig.backend.base,
                transferBinding = "mock",
                mainLane = rig.lane,
                deviceLog = { process, maxBytes ->
                    when (process) {
                        LogTailService.Process.APP -> rig.log.lines.joinToString("\n").takeLast(maxBytes)
                        LogTailService.Process.EXTENSION -> null
                    }
                },
                triggerGroups = mapOf(
                    "app" to TriggerGroup(
                        lane = rig.lane,
                        wired = appTriggers(MockEntryDriver(mocks, rig.os)) + (
                            "onExpiry" to RigTrigger.Fire { arg -> if (arg == "next") rig.os.expireNext() else rig.os.expire() }
                            ),
                        excluded = emptyMap(),
                    ),
                    "photokit-ext" to TriggerGroup(
                        // The extension process has no main lane: its root runs on the OS-invoked thread.
                        lane = Dispatchers.Default,
                        wired = mapOf(
                            "processRawValue" to RigTrigger.Answering { _, body ->
                                if (body != null) {
                                    """{"refused":"the mocked upload-job queue is the host's own; job sets cannot be handed in","queue":"mock"}""" + "\n"
                                } else {
                                    val result = mocks.extensionHost.operator.process()
                                    """{"result":"${result.toString().lowercase()}","queue":"mock",""" +
                                        """"created":${mocks.uploadQueue.operator.created.size}}""" + "\n"
                                }
                            },
                            "onTerminate" to RigTrigger.Fire { mocks.extensionHost.operator.terminate() },
                        ),
                        excluded = emptyMap(),
                    ),
                ),
                userCommands = userCommands(
                    dispatch = { mocks.screen.operator.tap(it) },
                    state = { rig.app.host.container.stateFlow.value },
                ),
                excludedUserCommands = excludedUserCommands(),
                deviceCommands = jvmDeviceCommands(rig),
                readGallery = rig.world.mockGalleryReader(),
                osExtensionEnabled = { null },
                publishBoundPort = publishBoundPort,
                contracts = emptyList(),
                refusals = jvmRefusals(rig),
                osRecord = rig.os::record,
                osExtensionNotApplicable =
                    "the JVM root composes an operating system without the OS-driven upload mechanism, so there is " +
                        "no extension registration to report",
            )
        }

        /** The version this host's build declares until a caller plays another — high, so the gate serves it. */
        private const val SERVED_VERSION = "99.0"

        /** Where the host's build reports: it plays a distributed build, which reports (to the reporter mock). */
        private const val DSN = "in-memory://jvm-rig"

        /** The App Store page the host's build names — the update-required screen's one remedy. */
        private const val APP_STORE_URL = "https://apps.apple.com/app/id0000000000"
    }
}

/**
 * What the JVM host drives: the composed [app], the device it runs on ([mocks], the app's durable state), the backend
 * [reach] and the build's declared [version].
 */
internal class JvmRig(
    val app: JvmApp<JvmMocks>,
    val backend: RigBackend,
    val version: DeclaredVersion,
    val log: RecordedLog,
    val lane: kotlinx.coroutines.CoroutineDispatcher,
    /** The host's scope, for work a verb starts and does not wait for. */
    val scope: CoroutineScope,
    val reach: BackendReach,
    /** The operating system's side of the completion handlers it hands the app, and its expiry. */
    val os: PlayedOs,
) {
    val mocks: JvmMocks get() = app.durable

    /** What the operator levers act on: every mock of the device (`MockLevers.kt`). */
    val world: MockWorld by lazy { jvmWorld(this, os) }

    /**
     * What the phone's UI does that nothing else here does: it builds a live screen, whose `onLive` assembles the status
     * host and starts showing it every state — and the host's Orbit container starts its reduction only once its state
     * is collected. Done at start, and again after every relaunch, whose new app no screen has been built for.
     */
    fun showScreen() {
        app.host
        mocks.screen.operator.live()
    }
}
