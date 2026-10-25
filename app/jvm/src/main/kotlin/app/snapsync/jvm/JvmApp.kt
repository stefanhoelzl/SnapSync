package app.snapsync.jvm

import app.snapsync.compose.AppDevicePorts
import app.snapsync.compose.ExtensionDevicePorts
import app.snapsync.compose.NoProcessMetrics
import app.snapsync.compose.ProcessPorts
import app.snapsync.compose.snapSyncExtension
import app.snapsync.dates.JvmDateFormatting
import app.snapsync.host.ComposedApp
import app.snapsync.host.snapSyncHost
import app.snapsync.identity.NoPlatformDeviceId
import app.snapsync.ports.CrashReporter
import app.snapsync.ports.Crypto
import app.snapsync.ports.Files
import app.snapsync.ports.LogSink
import app.snapsync.ports.ProcessMetrics
import app.snapsync.ports.ThreadLabel
import app.snapsync.presentation.CutoffFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin

/**
 * **The JVM root** (`docs/testing.md`, "The JVM root"): the app composed on the JVM exactly as `SnapSyncRoot` composes
 * it on the phone — the shared host composition (`snapSyncHost`, whose first act is the process's) over ports and
 * nothing else — and the upload extension composed beside it exactly as its own root composes it
 * (`snapSyncExtension`), over the adapters [adapters] builds from [durable] for each launch.
 *
 * [durable] is whatever survives a process: the caller's choice, usually [JvmMocks]. [relaunch] is process death and a
 * cold launch — every collector and in-flight launch of the running app ends, and a new app is composed over a fresh
 * set of adapters over the same [durable]. Nothing is forced at construction that the phone does not force at process
 * start: the core, its status host and the cycle are built on first use.
 *
 * What differs from the phone, stated:
 * - **One JVM, two processes.** The app and the upload extension are both composed here, each over its own process
 *   ports (the extension's files reach only the shared area; its crash channel is one nobody observes), and each
 *   builds its own services over the one device's storage — as the two processes do over the App Group.
 */
class JvmApp<D>(
    /** The caller's scope, which owns every launch's work: the app runs under a child job of it. */
    private val scope: CoroutineScope,
    /** The lanes both processes run on — the caller's, as a device root names its own. */
    private val lanes: JvmLanes,
    /** What survives the app's process — handed to [adapters] at every launch. */
    val durable: D,
    /** One launch's adapters over [durable]. */
    private val adapters: (D) -> JvmAdapters,
) {
    private var appJob: Job = Job(scope.coroutineContext[Job])
    private var appScope: CoroutineScope = CoroutineScope(scope.coroutineContext + appJob)

    /** This launch's adapters. */
    var ports: JvmAdapters = adapters(durable)
        private set

    private var launch: Launch = Launch(ports)

    /**
     * The running app — its core, and its status host, assembled on first touch of [ComposedApp.host] as a foreground
     * launch assembles it.
     */
    val composed: ComposedApp get() = launch.composed

    /**
     * Process death and a cold launch: a new app over a fresh set of adapters over the same [durable]. Answers once the
     * dead launch has ended — a cancelled run still runs its `ensure` steps, and a re-arm the dead launch writes into
     * [durable] after the new one is up would read as the new launch's.
     */
    suspend fun relaunch() {
        appJob.cancelAndJoin()
        appJob = Job(scope.coroutineContext[Job])
        appScope = CoroutineScope(scope.coroutineContext + appJob)
        ports = adapters(durable)
        launch = Launch(ports)
    }

    /**
     * One launch's two processes over [ports] — every service built anew, as a process builds them, each through its
     * process's device bundle exactly as the phone's roots compose: the app's [AppDevicePorts], the extension's
     * [ExtensionDevicePorts].
     */
    private inner class Launch(private val ports: JvmAdapters) {

        private val app = AppDevicePorts(
            clock = lazyOf(ports.device.clock),
            // The device's primitives — the JDK's, recording the event key a launch seals with.
            crypto = lazyOf(ports.device.crypto),
            crashReporter = lazyOf(ports.device.crashReporter),
            files = lazyOf(ports.device.files),
            databases = lazyOf(ports.device.databases),
            preferences = lazyOf(ports.device.preferences),
            secureStore = lazyOf(ports.device.secureStore),
            platformDeviceId = lazyOf(NoPlatformDeviceId()),
            integrity = lazyOf(ports.device.integrity),
            processInfo = lazyOf(ports.device.processInfo),
            network = lazyOf(ports.device.network),
            deviceConditions = lazyOf(ports.device.deviceConditions),
            http = lazyOf(ports.systems.http),
            backgroundTime = lazyOf(ports.systems.backgroundTime),
            wake = lazyOf(ports.systems.wake),
            extensionRegistry = lazyOf(ports.systems.extensionRegistry),
            gallery = lazyOf(ports.systems.gallery),
            uploadSession = lazyOf(ports.systems.uploadSession),
            download = lazyOf(ports.systems.download),
            systemUi = lazyOf(ports.systems.systemUi),
            lifecycle = lazyOf(ports.entries.lifecycle),
            links = lazyOf(ports.entries.links),
            pushNotifications = lazyOf(ports.entries.pushNotifications),
            ui = lazyOf(ports.entries.ui),
            // The JDK's CLDR data, as a phone's root hands its platform's: the formatting keeps no state to mock.
            dateFormatting = lazyOf(JvmDateFormatting()),
        )

        /** The extension's own process: its files reach only the shared area; its crash channel is one nobody observes. */
        private val extensionDevice = ExtensionDevicePorts(
            clock = lazyOf(ports.device.clock),
            crypto = lazyOf(ports.device.crypto),
            crashReporter = lazyOf(ports.device.extensionCrashReporter),
            files = lazyOf(ports.device.extensionFiles),
            databases = lazyOf(ports.device.databases),
            preferences = lazyOf(ports.device.preferences),
            secureStore = lazyOf(ports.device.secureStore),
            platformDeviceId = lazyOf(NoPlatformDeviceId()),
            galleryReader = lazyOf(ports.systems.cycleGallery),
            uploadQueue = lazyOf(ports.systems.uploadQueue),
            http = lazyOf(ports.systems.http),
        )

        val composed: ComposedApp = snapSyncHost(
            appScope,
            lanes.composition,
            lanes.reduction,
            app.appPorts(
                processPorts(
                    app.crashReporter,
                    ports.device.processMetrics,
                    app.files,
                    app.clock,
                    app.crypto,
                    ports.device.logSinks,
                ),
                ports.entries.devControls,
                app.ui.value,
            ),
            cutoffFormatter(),
        )

        // The extension registers on the OS's extension host at its process's start, as its root does.
        init {
            snapSyncExtension(
                extensionDevice.extensionPorts(
                    // The extension's own log is not read back on this host; it touches no global writer list.
                    processPorts(
                        extensionDevice.crashReporter,
                        NoProcessMetrics,
                        extensionDevice.files,
                        extensionDevice.clock,
                        extensionDevice.crypto,
                        emptyList(),
                    ),
                    ports.entries.extensionHost,
                ),
                lane = lanes.extension,
                answerOn = lanes.extensionAnswers,
            )
        }

        private fun processPorts(
            crashReporter: Lazy<CrashReporter>,
            processMetrics: ProcessMetrics,
            files: Lazy<Files>,
            clock: Lazy<app.snapsync.ports.Clock>,
            crypto: Lazy<Crypto>,
            logSinks: List<LogSink>,
        ) = ProcessPorts(
            crashReporter = crashReporter.value,
            processMetrics = processMetrics,
            logSinks = logSinks,
            files = files.value,
            clock = clock.value,
            crypto = crypto.value,
            threadLabel = lanes.threadLabel(),
            entryEvents = ports.entries.entryEvents,
            build = ports.build.port(),
        )

        /** Both from the launch's clock, as a phone's root builds it: the zone read once, "now" on every ask. */
        private fun cutoffFormatter(): CutoffFormatter =
            CutoffFormatter(now = ports.device.clock::now, zone = ports.device.clock.timeZone())
    }
}

/**
 * The lanes a JVM launch runs on (`docs/architecture.md`, "Concurrency and failure") — the caller's to name, as a phone's
 * root names its own. None has a default: a default would hand the world harness a clock that never moves, or the control
 * channel's host a thread it cannot see.
 */
class JvmLanes(
    /** The composition lane the caller's scope runs on. */
    val composition: CoroutineDispatcher,
    /** The CPU lane presentation reduces on. */
    val reduction: CoroutineDispatcher,
    /** The upload extension's lane: its detached work and its expiry grace. */
    val extension: CoroutineDispatcher,
    /**
     * Where the extension's `process()` runs its work while the operating system's thread waits for the answer: `null`
     * on that thread, as on the phone.
     */
    val extensionAnswers: CoroutineDispatcher?,
    /** Each process's per-thread label, which the runtime writes each task's trigger into. */
    val threadLabel: () -> ThreadLabel,
)
