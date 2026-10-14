@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.rig

import app.snapsync.jvm.JvmApp
import app.snapsync.jvm.JvmMocks
import app.snapsync.levers.ExtensionAnswer
import app.snapsync.levers.ExtensionHostLevers
import app.snapsync.mock.BuildInfoMock
import app.snapsync.mock.DeclaredVersion
import app.snapsync.model.ProcessingAnswer
import app.snapsync.model.StoreKind
import app.snapsync.model.StoreLink
import app.snapsync.model.uploadersCarried
import app.snapsync.rig.time.LoopTime
import app.snapsync.services.logs.LogTailService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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
) : AutoCloseable {

    /**
     * Every lane rule the app broke while this host ran (`docs/testing.md`, "How a test waits") — work that reached it
     * from a thread the loop does not own. A test fails on any.
     */
    val violations: List<String> get() = rig.threads.violations.toList()

    /** Stop serving and tear the app down. The backend process, when there is one, is the JVM's, not this host's. */
    override fun close() {
        server.stop()
        scope.cancel()
        rig.threads.close()
    }

    companion object {
        /** A generous bound on binding: a failure to bind is a stated error, never a hang. */
        private val BIND_TIMEOUT = 30.seconds

        /**
         * Compose the app over the backend named [backend] — `mock` (the in-memory backend mock) or `deno` (the real
         * `api/`, through `:test:edge`) — and serve it on loopback [port]. `0` asks the OS for a free port, which is what
         * a test wants. Returns once the server has bound.
         *
         * [time] is where the app's timers get their time: [LoopTime.MOCKED] from the clock mock, which only an advance
         * moves — starting at [startAt] — or [LoopTime.REAL], the wall clock, for a caller that still waits by polling.
         * How the lanes take turns is the `snapsync.lanes` system property's ([Interleaving.parse]).
         */
        suspend fun start(
            backend: String = "mock",
            port: Int = 0,
            time: LoopTime = LoopTime.REAL,
            startAt: Instant? = null,
        ): JvmRigHost = start(RigBackend.named(backend), port, time, startAt)

        internal suspend fun start(backend: RigBackend, port: Int, time: LoopTime, startAt: Instant?): JvmRigHost {
            val threads = HostThreads.open(time, startAt, backend)
            val scope = CoroutineScope(SupervisorJob() + threads.composition)
            val rig = threads.loop.outside("composing the app") {
                withContext(threads.composition) { compose(scope, backend, threads) }
            }
            val bound = CompletableDeferred<Int>()
            val server = RigServer(
                hooks = jvmHooks(rig, publishBoundPort = {
                    bound.complete(it)
                }, reportBindFailure = { bound.completeExceptionally(BindFailed(port, it)) }),
                port = port,
            )
            server.start()
            val actual = try {
                withTimeout(BIND_TIMEOUT) { bound.await() }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                JvmRigHost(rig, port, server, scope).close()
                throw IllegalStateException(
                    "the JVM rig host did not bind loopback:$port within $BIND_TIMEOUT — see the `[rig]` log line",
                    timeout,
                )
            } catch (failed: BindFailed) {
                JvmRigHost(rig, port, server, scope).close()
                throw failed
            }
            return JvmRigHost(rig, actual, server, scope)
        }

        /**
         * The bind's failure, as the start reports it. Its own type because the engine reports a refused bind as a
         * cancellation of its job, which the start must not mistake for its caller cancelling it.
         */
        private class BindFailed(port: Int, cause: Throwable) :
            IllegalStateException("the JVM rig host failed to bind loopback:$port — see the `[rig]` log line", cause)

        private fun compose(scope: CoroutineScope, backend: RigBackend, threads: HostThreads): JvmRig {
            val mocks = threads.mocks
            val version = DeclaredVersion(SERVED_VERSION)
            val log = RecordedLog()
            val build = BuildInfoMock(
                uploadHost = backend.base,
                declaredVersion = version,
                dsn = DSN,
                store = StoreLink(APP_STORE_URL, StoreKind.APP_STORE),
                pushEnvironment = "sandbox",
            )
            val app = JvmApp(scope, threads.lanes, mocks) { device ->
                device.adapters(
                    build,
                    http = threads.counted(backend.port(device)),
                    logSinks = listOf(log),
                    integrity = backend.integrity(device),
                )
            }
            val network = threads.network ?: mocks.backend.byteRoute
            val rig = JvmRig(
                app,
                backend,
                version,
                log,
                threads,
                scope,
                BackendReach(
                    backend.base,
                    backend.name,
                    backend.port(mocks),
                    network,
                    version,
                    backend::attester,
                ) { device ->
                    heldToken(mocks.keychain.port(), device.takeIf { it == mocks.ownDeviceId })
                },
                PlayedOs(mocks).also { os -> os.watch { threads.events.tell(it) } },
            )
            rig.showScreen()
            return rig
        }

        private fun jvmHooks(rig: JvmRig, publishBoundPort: (Int) -> Unit, reportBindFailure: (Throwable) -> Unit): RigHooks {
            val mocks = rig.mocks
            return RigHooks(
                bootedAt = Clock.System.now().toString(),
                servicesOf = { hooks ->
                    rig.world.servedServices(
                        rig.host,
                        rig.threads.acts,
                        reconcileUploads = { rig.app.composed.core.uploadTransitions.onOverrideChanged() },
                    ) + rig.world.servedHostServices(jvmHostServices(rig, hooks), rig.threads.acts)
                },
                uploadTier = uploadersCarried(osSupportsOsDrivenUpload = false),
                uploadBase = rig.backend.base,
                transferBinding = "mock",
                mainLane = rig.lane,
                world = HostWorld(servedWorld(JvmWorld(rig.threads), refusal = null), rig.threads.acts),
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
                            "onExpiry" to RigTrigger.Fire { arg -> if (arg == "next") rig.os.expireNextNow() else rig.os.expireNow() }
                            ),
                        excluded = emptyMap(),
                    ),
                    "photokit-ext" to TriggerGroup(
                        // The extension process has no main lane: its root runs on the OS-invoked thread, which the
                        // trigger hands it to from the act's own lane.
                        lane = rig.threads.operator,
                        wired = mapOf(
                            "processRawValue" to RigTrigger.Answering { _, body ->
                                if (body != null) {
                                    """{"refused":"the mocked upload-job queue is the host's own; job sets cannot be handed in","queue":"mock"}""" + "\n"
                                } else {
                                    // On the operating system's own thread, which the answer blocks: work the loop
                                    // waits on, while the cycle itself runs on the extension's lane.
                                    val result = rig.threads.loop.outside("the extension's process()") {
                                        mocks.extensionHost.process(rig.threads.osThread)
                                    }
                                    """{"result":"${result.toString().lowercase()}","queue":"mock",""" +
                                        """"created":${mocks.uploadQueue.view().created.size}}""" + "\n"
                                }
                            },
                            "onTerminate" to RigTrigger.Fire { mocks.extensionHost.terminate() },
                        ),
                        excluded = emptyMap(),
                    ),
                ),
                deviceCommands = jvmDeviceCommands(rig),
                osExtensionEnabled = { null },
                publishBoundPort = publishBoundPort,
                reportBindFailure = reportBindFailure,
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

        /**
         * How long a missing network must last before this host's app tells the member — the network tests in
         * `:test:integration` and `:test:control` wait a few multiples of it.
         */
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
    /** The host's loop and every lane both processes run on (`docs/testing.md`, "How a test waits"). */
    val threads: HostThreads,
    /** The host's scope, for work a verb starts and does not wait for. */
    val scope: CoroutineScope,
    val reach: BackendReach,
    /** The operating system's side of the completion handlers it hands the app, and its expiry. */
    val os: PlayedOs,
) {
    val mocks: JvmMocks get() = app.durable

    /** The composition lane: what the app's entry points are invoked on, as Swift invokes them on main. */
    val lane: kotlinx.coroutines.CoroutineDispatcher get() = threads.composition

    /** What the operator levers act on: every mock of the device (`MockLevers.kt`). */
    val world: MockWorld by lazy { jvmWorld(this, os) }

    /**
     * The services this host implements itself: the operating system's deliveries, through the mocks on the composition
     * lane, and the extension's invocations, on the operating system's own thread.
     */
    val host: HostServices by lazy {
        HostServices(entries = PlayedEntries(MockEntryDriver(mocks, os), lane), extensionHost = JvmExtensionHost(this))
    }

    /**
     * What the phone's UI does that nothing else here does: it builds a live screen, whose `onLive` assembles the status
     * host and starts showing it every state — and the host's Orbit container starts its reduction only once its state
     * is collected. Done at start, and again after every relaunch, whose new app no screen has been built for.
     */
    fun showScreen() {
        app.composed.host
        mocks.screen.live()
    }
}

/**
 * The `extension-host` service on the JVM host: the operating system invokes the extension's `process()` on a thread of
 * its own ([HostThreads.osThread]), which the answer blocks — work the loop waits on, while the cycle itself runs on the
 * extension's lane.
 */
internal class JvmExtensionHost(private val rig: JvmRig) : ExtensionHostLevers {
    override suspend fun process(): ExtensionAnswer {
        val answer = rig.threads.loop.outside("the extension's process()") {
            rig.mocks.extensionHost.process(rig.threads.osThread)
        }
        return when (answer) {
            ProcessingAnswer.COMPLETED -> ExtensionAnswer.COMPLETED
            ProcessingAnswer.PROCESSING -> ExtensionAnswer.PROCESSING
            ProcessingAnswer.FAILED -> ExtensionAnswer.FAILED
        }
    }

    override suspend fun terminate() = rig.mocks.extensionHost.terminate()
}
