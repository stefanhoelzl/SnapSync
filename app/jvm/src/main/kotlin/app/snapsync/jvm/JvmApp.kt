package app.snapsync.jvm

import app.snapsync.compose.AppDevicePorts
import app.snapsync.compose.ExtensionDevicePorts
import app.snapsync.compose.NoEntryContext
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
import app.snapsync.presentation.CutoffFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

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
    /** The composition lane [scope] runs on — the caller's, as a device root names its own. */
    private val lane: CoroutineDispatcher,
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

    /** Process death and a cold launch: a new app over a fresh set of adapters over the same [durable]. */
    fun relaunch() {
        appJob.cancel()
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
            backend = lazyOf(ports.systems.backend),
            backgroundTime = lazyOf(ports.systems.backgroundTime),
            wake = lazyOf(ports.systems.wake),
            extensionRegistry = lazyOf(ports.systems.extensionRegistry),
            gallery = lazyOf(ports.systems.gallery),
            photoAccess = lazyOf(ports.systems.photoAccess),
            appUpload = lazyOf(ports.systems.appUpload),
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
            cycleUpload = lazyOf(ports.systems.cycleUpload),
            backend = lazyOf(ports.systems.backend),
        )

        val composed: ComposedApp = snapSyncHost(
            appScope,
            lane,
            app.appPorts(
                processPorts(app.crashReporter, app.files, app.clock, app.crypto, ports.device.logSinks),
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
                        extensionDevice.files,
                        extensionDevice.clock,
                        extensionDevice.crypto,
                        emptyList(),
                    ),
                    ports.entries.extensionHost,
                ),
            )
        }

        private fun processPorts(
            crashReporter: Lazy<CrashReporter>,
            files: Lazy<Files>,
            clock: Lazy<app.snapsync.ports.Clock>,
            crypto: Lazy<Crypto>,
            logSinks: List<LogSink>,
        ) = ProcessPorts(
            crashReporter = crashReporter.value,
            processMetrics = NoProcessMetrics,
            logSinks = logSinks,
            files = files.value,
            clock = clock.value,
            crypto = crypto.value,
            entryContext = NoEntryContext,
            build = ports.build.port(),
        )

        /** Both from the launch's clock, as a phone's root builds it: the zone read once, "now" on every ask. */
        private fun cutoffFormatter(): CutoffFormatter =
            CutoffFormatter(now = ports.device.clock::now, zone = ports.device.clock.timeZone())
    }
}
