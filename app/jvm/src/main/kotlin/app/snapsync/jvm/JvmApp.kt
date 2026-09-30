package app.snapsync.jvm

import app.snapsync.compose.AppCore
import app.snapsync.compose.AppPorts
import app.snapsync.compose.ComposedExtension
import app.snapsync.compose.ExtensionPorts
import app.snapsync.compose.NoEntryContext
import app.snapsync.compose.NoProcessMetrics
import app.snapsync.compose.ProcessPorts
import app.snapsync.compose.ProcessServices
import app.snapsync.compose.snapSyncExtension
import app.snapsync.host.ComposedApp
import app.snapsync.host.snapSyncHost
import app.snapsync.identity.NoPlatformDeviceId
import app.snapsync.ports.CrashReporter
import app.snapsync.ports.Files
import app.snapsync.ports.LogSink
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.StatusContainerHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.time.Clock

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
 * - **The screen's "now" is the wall clock**, while the core reads the launch's [JvmDevice.clock], so a status screen
 *   renders dates a person would see.
 */
class JvmApp<D>(
    /** The caller's scope, which owns every launch's work: the app runs under a child job of it. */
    private val scope: CoroutineScope,
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

    /** The running app — its core and, on first touch of [host], its status host. */
    val composed: ComposedApp get() = launch.composed

    /** The running app's core. */
    val core: AppCore get() = launch.composed.core

    /** The running app's status host, assembled on first touch as a foreground launch assembles it. */
    val host: StatusContainerHost get() = launch.composed.host

    /** This launch's app-process services. */
    val process: ProcessServices get() = launch.composed.process

    /** The upload extension, composed beside the app. */
    val extension: ComposedExtension get() = launch.extension

    /** Process death and a cold launch: a new app over a fresh set of adapters over the same [durable]. */
    fun relaunch() {
        appJob.cancel()
        appJob = Job(scope.coroutineContext[Job])
        appScope = CoroutineScope(scope.coroutineContext + appJob)
        ports = adapters(durable)
        launch = Launch(ports)
    }

    /** One launch's two processes over [ports] — every service built anew, as a process builds them. */
    private inner class Launch(private val ports: JvmAdapters) {

        val composed: ComposedApp = snapSyncHost(appScope, appPorts(), cutoffFormatter())

        /** The extension registers on the OS's extension host at its process's start, as its root does. */
        val extension: ComposedExtension = snapSyncExtension(extensionPorts())

        private fun processPorts(crashReporter: CrashReporter, files: Files, logSinks: List<LogSink>) = ProcessPorts(
            crashReporter = crashReporter,
            processMetrics = NoProcessMetrics,
            logSinks = logSinks,
            files = files,
            clock = ports.device.clock,
            entryContext = NoEntryContext,
            build = ports.build.port(),
        )

        private fun appPorts(): AppPorts = AppPorts(
            process = processPorts(ports.device.crashReporter, ports.device.files, ports.device.logSinks),
            databases = ports.device.databases,
            preferences = ports.device.preferences,
            secureStore = ports.device.secureStore,
            platformDeviceId = NoPlatformDeviceId(),
            photoAccess = ports.systems.photoAccess,
            gallery = ports.systems.gallery,
            systemUi = ports.systems.systemUi,
            download = ports.systems.download,
            backend = ports.systems.backend,
            integrity = ports.device.integrity,
            appUpload = ports.systems.appUpload,
            backgroundTime = ports.systems.backgroundTime,
            wake = ports.systems.wake,
            extensionRegistry = ports.systems.extensionRegistry,
            devControls = ports.entries.devControls,
            pushNotifications = ports.entries.pushNotifications,
            lifecycle = ports.entries.lifecycle,
            links = ports.entries.links,
            ui = ports.entries.ui,
            processInfo = ports.device.processInfo,
        )

        private fun extensionPorts(): ExtensionPorts = ExtensionPorts(
            // The extension's own log is not read back on this host; it touches no global writer list.
            process = processPorts(ports.device.extensionCrashReporter, ports.device.extensionFiles, emptyList()),
            databases = ports.device.databases,
            preferences = ports.device.preferences,
            secureStore = ports.device.secureStore,
            platformDeviceId = NoPlatformDeviceId(),
            gallery = ports.systems.cycleGallery,
            upload = ports.systems.cycleUpload,
            backend = ports.systems.backend,
            host = ports.entries.extensionHost,
        )

        /** The zone is read once from the launch's clock; "now" is the wall clock (see the class's deviations). */
        private fun cutoffFormatter(): CutoffFormatter =
            CutoffFormatter(now = Clock.System::now, zone = ports.device.clock.timeZone())
    }
}
