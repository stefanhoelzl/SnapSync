@file:OptIn(ExperimentalStdlibApi::class, ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.snapsync.ios

import app.snapsync.config.bakedUploadBase
import app.snapsync.ios.urlsession.transferSessionBinding
import app.snapsync.files.IosFiles
import app.snapsync.services.logs.LogTailService
import app.snapsync.logging.documentsDirectory
import app.snapsync.rig.RigHooks
import app.snapsync.rig.RigServer
import app.snapsync.ios.upload.UploadExtensionRoot
import app.snapsync.rig.rigCompletion
import app.snapsync.rig.RigDevControls
import app.snapsync.rig.RigUi
import app.snapsync.rig.appTriggers
import app.snapsync.contracts.EntryDriver
import app.snapsync.scene.IosUi
import app.snapsync.rig.extensionTriggerGroup
import app.snapsync.rig.TriggerGroup
import app.snapsync.rig.deviceCommands
import app.snapsync.contract.extension.extensionContractEntries
import app.snapsync.rig.noMembershipRefusal
import app.snapsync.keychain.contract.deviceContracts
import app.snapsync.contract.appDeviceContracts
import app.snapsync.contract.simulatorAppContracts
import app.snapsync.contract.extension.recordLanded
import app.snapsync.rig.galleryReader
import app.snapsync.model.uploadersCarried
import app.snapsync.rig.osExtensionEnabled
import app.snapsync.rig.rigPort
import app.snapsync.rig.userCommands
import app.snapsync.rig.excludedUserCommands
import app.snapsync.rig.rigPortFilePath
import app.snapsync.rig.iosRefusals
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSDate
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.Foundation.NSUserActivity
import platform.Foundation.NSUserActivityTypeBrowsingWeb

/**
 * A rig build's **adapter set** — the control channel's entire footprint inside `:app:ios`, and it lives in
 * `:test:rig`'s tree, not the shell's (`docs/testing.md`, "The control channel").
 *
 * `app/ios/build.gradle.kts` compiles this directory into `:app:ios`'s `iosMain` — in place of `src/prod`, which
 * answers the same [platformAdapters] with the production set — and adds the `:test:rig` dependency, ONLY under
 * `-Psnapsync.rig=true`. A production build contains none of it: not a stub, not an inert branch, not a flag.
 *
 * The set differs from production in exactly two adapters: the UI is decorated ([RigUi] — the channel's `/user` verbs
 * reach the core as the intents a tap produces, through the same handlers) and the development controls are the
 * channel's ([RigDevControls] — the uploader switch, invite-link hints honoured, the reset). Building it starts the
 * channel's server. The `/os` verbs deliver through the platform's own adapters ([IosEntryDriver]).
 *
 * Being compiled INTO `:app:ios` is what lets this file reach the root's adapters without widening anything to
 * `public` — and it means this directory is in the shell gate's scanned roots (`appShellSources`), so this file may
 * hold **no decisions**. Every default, cast, fallback and rendering lives in `:test:rig`, where it is ordinary code.
 *
 * ## Nothing is forced here
 * `SnapSyncRoot.app` and `.host` are passed as **thunks**: this is called while the root composes its graph, so it
 * captures lambdas and binds a socket, and the first request that needs the graph finds it composed.
 */
internal fun platformAdapters(ui: IosUi): PlatformAdapters {
    val rigUi = RigUi(ui)
    val controls = RigDevControls()
    startRig(rigUi, controls)
    return PlatformAdapters(devControls = controls, ui = rigUi)
}

private fun startRig(rigUi: RigUi, controls: RigDevControls) = RigServer(
    core = { SnapSyncRoot.app },
    host = { SnapSyncRoot.host },
    hooks = iosHooks(rigUi, controls),
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
private fun iosHooks(rigUi: RigUi, controls: RigDevControls) = RigHooks(
    bootedAt = NSDate().description,
    // Which uploaders this OS carries — a build constant. What each may do now varies with the grant.
    uploadTier = uploadersCarried(SnapSyncRoot.osSupportsOsDrivenUpload),
    uploadBase = bakedUploadBase(),
    // A compile-time fact, read rather than derived: the adapter that CHOSE the binding is the one that
    // reports it, so the rig cannot disagree with the transport about what this build does.
    transferBinding = transferSessionBinding,
    // Swift calls entry points from the main thread; so does the rig. A trigger invoked on another lane
    // would not be the call the OS makes, which is the whole reason triggers are entry points.
    mainLane = Dispatchers.Main,
    deviceLog = LogTailService(IosFiles()),
    // Grouped by composition root: the `/os/<root>/<member>` segment names whose entry point a caller
    // invokes. `app` is `SnapSyncRoot`'s. A second group joins it when the channel reaches a second root.
    triggerGroups = mapOf(
        // Swift calls this root's entry points from the main thread, so the rig does too.
        "app" to TriggerGroup(lane = Dispatchers.Main, wired = appTriggers(IosEntryDriver), excluded = excludedTriggers()),
        "photokit-ext" to extensionTriggerGroup(
            // THUNKS, never method references. `UploadExtensionRoot` is an `object` whose `init` calls
            // `Logger.setLogWriters(…)`, and a bound method reference FORCES that object where it is
            // written — here, at boot. Measured 2026-08-26: with `::processRawValue` the extension's boot
            // banner appeared at hook-construction time and `SnapSyncRoot`'s own `onLaunch` lines then
            // landed in `ext-debug.log`, because the app's log destination had already been replaced
            // before `SnapSyncRoot` initialised. `debug.log` was never created at all.
            //
            // A lambda body is not evaluated until it is called, so the root is forced by the first
            // request that needs it — which is the same rule this file already follows for
            // `SnapSyncRoot.app` and `.host`, and for the same reason.
            process = { UploadExtensionRoot.processRawValue() },
            terminate = { UploadExtensionRoot.onTerminate() },
            excluded = excludedExtensionTriggers(),
        ),
    ),
    // The `/user` maps and the `/device` verbs are built in `:test:rig`, not here. Same reason every
    // default and cast already lives there: this file is compiled INTO `:app:ios` and is scanned by the
    // shell gate, which permits no decisions — and a command map's bodies are full of them.
    userCommands = userCommands(dispatch = rigUi::dispatch, state = { SnapSyncRoot.host.container.stateFlow.value }),
    excludedUserCommands = excludedUserCommands(),
    deviceCommands = deviceCommands(
        core = { SnapSyncRoot.app },
        controls = controls,
        photoAccess = SnapSyncRoot.permission,
        osSupportsOsDrivenUpload = SnapSyncRoot.osSupportsOsDrivenUpload,
        handleReport = SnapSyncRoot.process.processAccount::handle,
    ),
    readGallery = galleryReader(core = { SnapSyncRoot.app }),
    osExtensionEnabled = osExtensionEnabled(registry = { SnapSyncRoot.extensionRegistry }),
    // The path decision (and its `null` case) lives in `:test:rig`; this side supplies only the write,
    // which has no branch to make. `Documents/` rather than the App Group deliberately: a simulator host
    // reads it with `xcrun simctl get_app_container <dev> app.snapsync data`, and the device tooling
    // already pulls from the same place — neither needs an entitlement to get at it.
    publishBoundPort = { bound -> writeTextFile(rigPortFilePath(documentsDirectory()), bound.toString()) },
    contracts = deviceContracts() + appDeviceContracts(refusal = noMembershipRefusal { SnapSyncRoot.host }) +
        extensionContractEntries(
            membershipRefusal = noMembershipRefusal { SnapSyncRoot.host },
            registry = { SnapSyncRoot.extensionRegistry },
        ) +
        simulatorAppContracts(),
    // What this host refuses of the shared vocabulary, built in `:test:rig` (this file may hold no decisions).
    refusals = iosRefusals(),
    recordLanded = ::recordLanded,
)

/**
 * Write [text] to [path], or nowhere when there is no path.
 *
 * Errors are dropped deliberately: the rig must never be able to break the app under test, and a caller
 * that finds no port file is already in exactly the state this file exists to make visible.
 */
private fun writeTextFile(path: String?, text: String) {
    NSString.create(string = text).writeToFile(path.orEmpty(), atomically = true, encoding = NSUTF8StringEncoding, error = null)
}

/**
 * WIRED entry points. No deadline is reported beside a receipted trigger: no clock of the app's own releases a
 * handler (capability `sync-status`), so the measured hold is the only number there is.
 */
/**
 * The iOS operating system, driven: each `/os` verb delivers through the same adapter method the Swift shell's
 * callback reaches, on the same (main) lane, so a rig-driven delivery is indistinguishable in `debug.log` from an
 * OS-driven one.
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

    override fun backgroundTask(identifier: String, done: () -> Unit) =
        SnapSyncRoot.wakeAdapter.onTaskLaunched(identifier, rigCompletion(done))

    override fun backgroundTransfers(identifier: String, done: () -> Unit) =
        SnapSyncRoot.onBackgroundTransfers(identifier, done)
}

/**
 * EXCLUDED entry points of the upload extension's root — **none**, and the empty map is the statement.
 *
 * Both of that root's `@PlatformEntry` members are driveable: `processRawValue` runs a cycle and answers
 * with the tri-state result the OS reads, and `onTerminate` records what the root knows about its in-flight
 * work. Neither is a record of whether the PLATFORM called it, which is what makes the app root's scene
 * observers undriveable.
 *
 * The function exists rather than being omitted so every group states its exclusions in a named place: a group
 * with none would be a group whose omissions could not be read, and "nothing is excluded" and "nothing could be
 * looked at" are not the same answer. (A guard once derived these and compared them with the `@PlatformEntry`
 * population; it was retired in `74302d2b`, so the comparison is a reviewer's.)
 */
private fun excludedExtensionTriggers(): Map<String, String> = emptyMap()

/**
 * EXCLUDED entry points, each with the consequence that makes the omission safe rather than an oversight.
 * Wired + excluded is meant to equal the root's `@PlatformEntry` population, exactly — kept by review, since
 * the guard that asserted it was retired (`74302d2b`).
 */
private fun excludedTriggers(): Map<String, String> = mapOf(
    "onLaunch" to
        "composes the graph, which the app's own launch already did: a second call is a no-op, so there is " +
        "nothing for the channel to drive. Reset is a relaunch.",
    "onLaunchActivity" to
        "the COLD universal-link delivery, which no in-process call can recreate. Note this is a " +
        "delivery gap, not a join gap: its warm twin onSceneContinueActivity IS wired and reaches the " +
        "same decode -> gate -> join path, so joining is driveable and only the cold hand-off is not.",
    "onSceneActive" to
        "a launch-shape query, not a trigger: it returns the resolved scene mode and nothing " +
        "downstream changes on a second call. /health reports the composition facts directly.",
    // The six scene-delegate OBSERVERS share one consequence, stated per entry so each carries its
    // own reason rather than pointing at a group. They record and do nothing else: firing one from
    // here writes a log line that says the RIG called it, which is the opposite of the fact they
    // exist to capture — whether the PLATFORM did. Their subject is UIKit's behaviour on an OS we
    // cannot drive, so the only instrument that reads them is a device dump.
    "onSceneWillConnect" to
        "records the connecting scene's activity count and nothing else; a rig call would log a " +
        "count the rig chose. What it answers — did iOS connect a scene carrying no activity — is " +
        "only answerable by iOS.",
    "onSceneDidFailToContinueActivity" to
        "records a continuation UIKit tried and abandoned, with the platform's own error. A rig call " +
        "would manufacture both the attempt and the error text, which is the entire content.",
    "onSwiftUiOpenUrl" to
        "SwiftUI's delivery path. Its destination is shell.onOpenUrl, which the wired " +
        "onSceneContinueActivity trigger already reaches, so joining stays driveable — and firing it " +
        "here would exercise the Kotlin door, never the question this path exists for, which is whether " +
        "the PLATFORM reaches the modifier on a given OS.",
    "onSceneWillContinueActivity" to
        "records that UIKit is starting a continuation. Invoking it here would assert exactly the " +
        "fact under investigation instead of observing it.",
    "onSceneWillEnterForeground" to
        "records that the scene DELEGATE was called, as distinct from the application-wide " +
        "notification onForeground observes. A rig call cannot distinguish the two, which is its " +
        "whole purpose.",
    "onSceneDidBecomeActive" to
        "the same delegate-liveness record as onSceneWillEnterForeground, at the active edge.",
    "onSceneDidDisconnect" to
        "records a scene teardown the rig cannot perform; forcing the line would misreport the " +
        "process state a later cold delivery is read against.",
    "onSceneOpenUrlContexts" to
        "records URLs arriving on the retired custom-scheme path, which no shipped build declares. " +
        "A rig call would manufacture the very arrival whose absence is the expected reading.",
)

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
