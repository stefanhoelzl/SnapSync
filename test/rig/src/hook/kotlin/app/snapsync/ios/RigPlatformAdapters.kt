@file:OptIn(
    ExperimentalStdlibApi::class,
    ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
    CompositionOnly::class,
)

package app.snapsync.ios

import app.snapsync.background.IosWake
import app.snapsync.compose.AppDevicePorts
import app.snapsync.compose.pushHandlers
import app.snapsync.config.bakedUploadBase
import app.snapsync.contract.AppPush
import app.snapsync.contract.appDeviceContracts
import app.snapsync.contract.contractTappableWake
import app.snapsync.contract.extension.extensionContractEntries
import app.snapsync.contract.extension.recordLanded
import app.snapsync.contract.resumeContractRuns
import app.snapsync.contract.simulatorAppContracts
import app.snapsync.contracts.EntryDriver
import app.snapsync.files.IosFiles
import app.snapsync.inbound.CompositionOnly
import app.snapsync.ios.upload.UploadExtensionRoot
import app.snapsync.ios.urlsession.transferSessionBinding
import app.snapsync.keychain.contract.deviceContracts
import app.snapsync.logging.documentsDirectory
import app.snapsync.model.uploadersCarried
import app.snapsync.rig.RigHooks
import app.snapsync.rig.RigLaunch
import app.snapsync.rig.RigServer
import app.snapsync.rig.iosHostServices
import app.snapsync.rig.iosRefusals
import app.snapsync.rig.noMembershipRefusal
import app.snapsync.rig.osExtensionEnabled
import app.snapsync.rig.rigCompletion
import app.snapsync.rig.rigLaunch
import app.snapsync.rig.rigPort
import app.snapsync.rig.rigPortFilePath
import app.snapsync.rig.rigServices
import app.snapsync.rig.startWhenReady
import app.snapsync.services.logs.LogTailService
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserActivity
import platform.Foundation.NSUserActivityTypeBrowsingWeb
import platform.Foundation.create
import platform.Foundation.writeToFile

/**
 * A rig build's **adapter set** — the control channel's entire footprint inside `:app:ios`, and it lives in
 * `:test:rig`'s tree, not the shell's (`docs/testing.md`, "The control channel").
 *
 * `app/ios/build.gradle.kts` compiles this directory into `:app:ios`'s `iosMain` — in place of `src/prod`, which
 * answers the same [platformAdapters] with the production set — and adds the `:test:rig` dependency, ONLY under
 * `-Psnapsync.rig=true`. A production build contains none of it: not a stub, not an inert branch, not a flag.
 *
 * The set differs from production in the launch-time adapters (`docs/testing.md`, "Launch-time adapters"): the ports the
 * app composes over are [real] with every system the adapters file mocks swapped for its mock ([rigLaunch], which reads it),
 * and an adapter choice the process refuses composes nothing. Besides, the UI is decorated ([RigUi] — the channel's `screen.tap`
 * reach the core as the intents a tap produces, through the same handlers) and the development controls are the
 * channel's ([RigDevControls] — the uploader switch, invite-link hints honoured, the reset). The `os` service delivers
 * through the platform's own adapters ([IosEntryDriver]) — or a mocked system's operator face.
 *
 * Being compiled INTO `:app:ios` is what lets this file reach the root's adapters without widening anything to
 * `public` — and it means this directory is in the shell gate's scanned roots (`appShellSources`), so this file may
 * hold **no decisions**. Every default, cast, fallback and rendering lives in `:test:rig`, where it is ordinary code.
 *
 * ## Nothing is forced here
 * This is called while `SnapSyncRoot` initializes — before its process services exist — so it builds only the launch
 * and leaves the channel's server to start once the root is up ([startWhenReady]); `SnapSyncRoot.app` and `.host` are
 * passed as **thunks**, and the first request that needs the graph finds it composed.
 */
internal fun platformAdapters(real: AppDevicePorts): PlatformAdapters {
    // Before the composition's first relaunch is routed: a contract run spanning launches picks up where it was.
    resumeContractRuns()
    val launch = rigLaunch(real)
    startWhenReady { startRig(launch) }
    return PlatformAdapters(
        devControls = launch.controls,
        ui = lazyOf(launch.ui),
        ports = launch.ports,
        entryEvents = launch.world.device.entryEvents.port(),
        launch = launch::launch,
        bootLines = launch.bootLines,
    )
}

/** The root's wake in a rig build: production's, whose launches a device's contract run can take over for a clause. */
internal fun rootWake(log: Logger): IosWake = contractTappableWake(log)

private fun startRig(launch: RigLaunch) = RigServer(
    hooks = iosHooks(launch),
    port = rigPort(NSProcessInfo.processInfo.environment["SNAPSYNC_RIG_PORT"]),
).start()

/**
 * The platform verbs, bound to this shell's **real** entry points — the same members the Swift shell calls,
 * invoked on the same (main) lane, so a rig-driven trigger is indistinguishable in `debug.log` from an
 * OS-driven one.
 *
 * `SNAPSYNC_RIG_PORT` is read above rather than through `LaunchDirectives`: unlike every `SNAPSYNC_*`
 * launch trigger, this variable is observable by **no shipped code** — the file reading it does not exist in
 * a production build — so it is inert by construction rather than by a runtime check, and the one typed
 * surface every production launch parses stays free of rig configuration.
 */
private fun iosHooks(launch: RigLaunch) = RigHooks(
    bootedAt = NSDate().description,
    // Which uploaders this OS carries — a build constant. What each may do now varies with the grant.
    uploadTier = { uploadersCarried(SnapSyncRoot.osSupportsOsDrivenUpload) },
    uploadBase = bakedUploadBase(),
    // A compile-time fact, read rather than derived: the adapter that CHOSE the binding is the one that
    // reports it, so the rig cannot disagree with the transport about what this build does.
    transferBinding = transferSessionBinding,
    deviceLog = LogTailService(IosFiles())::tail,
    // The host's own services are built in `:test:rig`, not here. Same reason every default and cast already lives
    // there: this file is compiled INTO `:app:ios` and is scanned by the shell gate, which permits no decisions.
    // The typed services, over the kRPC route — each refused, with its reason, where the adapter choice cannot serve it —
    // and this host's own, each method classified by the host: the gallery, the contracts, the played upload-job queue.
    servicesOf = rigServices(
        launch,
        core = { SnapSyncRoot.app },
        host = { SnapSyncRoot.host },
        photoAccess = SnapSyncRoot.permission,
        handleReport = SnapSyncRoot.process.processAccount::handle,
        // A thunk, never a method reference: see `hostServices` below.
        processExtension = { UploadExtensionRoot.processRawValue() },
        osServices = hostServices(launch),
        contracts = deviceContracts() + appDeviceContracts(refusal = noMembershipRefusal { SnapSyncRoot.host }) +
            extensionContractEntries(
                membershipRefusal = noMembershipRefusal { SnapSyncRoot.host },
                registry = { SnapSyncRoot.extensionRegistry },
            ) +
            simulatorAppContracts(
                AppPush(
                    adapter = SnapSyncRoot.pushNotifications,
                    composed = { SnapSyncRoot.ports.pushNotifications.value },
                    // The rig plays the composition here: the app's own handlers, handed back to its adapter.
                    appHandlers = { SnapSyncRoot.app.pushHandlers() },
                ),
            ),
    ),
    // The registration the app runs over — the adapter choice's, where it mocks it.
    osExtensionEnabled = osExtensionEnabled(registry = { SnapSyncRoot.ports.extensionRegistry.value }),
    // The path decision (and its `null` case) lives in `:test:rig`; this side supplies only the write,
    // which has no branch to make. `Documents/` rather than the App Group deliberately: a simulator host
    // reads it with `xcrun simctl get_app_container <dev> app.snapsync data`, and the device tooling
    // already pulls from the same place — neither needs an entitlement to get at it.
    publishBoundPort = { bound -> writeTextFile(rigPortFilePath(documentsDirectory()), bound.toString()) },
    // What this host refuses of the shared vocabulary, built in `:test:rig` (this file may hold no decisions).
    refusals = iosRefusals(launch, hostServices(launch)),
    recordLanded = ::recordLanded,
    adapters = launch.description,
    uncomposed = launch.uncomposed,
    world = launch.hostWorld,
)

/**
 * The typed services this host implements itself (`os`, `extension-host`): the platform's own adapters, and the
 * extension root's members.
 *
 * THUNKS, never method references. `UploadExtensionRoot` is an `object` whose `init` calls `Logger.setLogWriters(…)`,
 * and a bound method reference FORCES that object where it is written — here, at boot. Measured 2026-08-26: with
 * `::processRawValue` the extension's boot banner appeared at hook-construction time and `SnapSyncRoot`'s own `onLaunch`
 * lines then landed in `ext-debug.log`, because the app's log destination had already been replaced before
 * `SnapSyncRoot` initialised. A lambda body is not evaluated until it is called, so the root is forced by the first
 * request that needs it — the same rule this file follows for `SnapSyncRoot.app` and `.host`.
 */
private fun hostServices(launch: RigLaunch) = iosHostServices(
    launch,
    IosEntryDriver,
    process = { UploadExtensionRoot.processRawValue() },
    terminate = { UploadExtensionRoot.onTerminate() },
)

/**
 * Write [text] to [path], or nowhere when there is no path.
 *
 * Errors are dropped deliberately: the rig must never be able to break the app under test, and a caller
 * that finds no port file is already in exactly the state this file exists to make visible.
 */
private fun writeTextFile(path: String?, text: String) {
    NSString.create(
        string = text,
    ).writeToFile(path.orEmpty(), atomically = true, encoding = NSUTF8StringEncoding, error = null)
}

/**
 * The iOS operating system, driven: each `os` delivery goes through the same adapter method the Swift shell's callback
 * reaches, on the same (main) lane, so a rig-driven delivery is indistinguishable in `debug.log` from an OS-driven one.
 *
 * Not every `@PlatformEntry` member has a delivery: the launch (`onLaunch`, which the app's own launch already ran), the
 * cold Universal Link (`onLaunchActivity`, which no in-process call can recreate — its warm twin `continueLink` reaches
 * the same join path), the launch-shape query `onSceneActive`, and the scene-delegate observers, which record whether the
 * PLATFORM called them — a rig call would manufacture the very fact they exist to capture.
 */
private object IosEntryDriver : EntryDriver {
    override fun foreground() = SnapSyncRoot.lifecycle.deliverForeground()

    override fun background() = SnapSyncRoot.lifecycle.deliverBackground()

    override fun pushToken(hex: String) = SnapSyncRoot.pushNotifications.deliverToken(hex)

    override fun pushTokenFailure(description: String) = SnapSyncRoot.pushNotifications.deliverTokenFailure(description)

    override fun silentPush(eventId: String?, done: () -> Unit) =
        SnapSyncRoot.pushNotifications.deliverMessage(mapOf("eventId" to eventId), done)

    override fun continueLink(url: String) =
        SnapSyncRoot.links.deliverUserActivity("onSceneContinueActivity", browsingWebActivity(url))

    override fun backgroundTask(identifier: String, done: () -> Unit) {
        // The channel plays no expiry of its own on this host: the run it starts is the operating system's to expire.
        SnapSyncRoot.wakeAdapter.onTaskLaunched(identifier, rigCompletion(done))
    }

    override fun backgroundTransfers(identifier: String, done: () -> Unit) =
        SnapSyncRoot.onBackgroundTransfers(identifier, done)
}

/**
 * The `NSUserActivity` iOS delivers to `scene(_:continue:)` for a universal link.
 *
 * Written as plain statements rather than `.apply { }` on purpose: a scope function's lambda counts as a
 * decision to the shell gate, which this file is scanned by.
 */
private fun browsingWebActivity(url: String): NSUserActivity {
    val activity = NSUserActivity(NSUserActivityTypeBrowsingWeb)
    activity.webpageURL = NSURL(string = url)
    return activity
}
