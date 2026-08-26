@file:OptIn(ExperimentalStdlibApi::class, ExperimentalForeignApi::class)

package app.snapsync.rig.hook

import app.snapsync.config.bakedUploadBase
import app.snapsync.ios.SnapSyncRoot
import app.snapsync.ios.urlsession.transferSessionBinding
import app.snapsync.logging.IosDeviceLogSource
import app.snapsync.logging.documentsDirectory
import app.snapsync.ports.ReceiptDeadlines
import app.snapsync.rig.RigCommand
import app.snapsync.rig.RigHooks
import app.snapsync.rig.RigServer
import app.snapsync.rig.RigTrigger
import app.snapsync.rig.RigUserCommand
import app.snapsync.rig.UploadMechanismPin
import app.snapsync.rig.deviceCommands
import app.snapsync.rig.galleryReader
import app.snapsync.model.PermissionStatus
import app.snapsync.model.resolveUploadMechanism
import app.snapsync.rig.osExtensionEnabled
import app.snapsync.rig.rigPort
import app.snapsync.rig.userCommands
import app.snapsync.rig.excludedUserCommands
import app.snapsync.rig.rigPortFilePath
import kotlin.native.EagerInitialization
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSDate
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSURL
import platform.Foundation.writeToFile
import platform.Foundation.NSUserActivity
import platform.Foundation.NSUserActivityTypeBrowsingWeb

/**
 * The rig's **entire footprint inside `:app:ios`** — and it lives in `:test:rig`'s tree, not the shell's.
 *
 * `app/ios/build.gradle.kts` adds this directory to `:app:ios`'s `iosMain` source set, and the
 * `:test:rig` dependency, ONLY under `-Psnapsync.rig=true`. Without the property it adds neither, so a
 * production build contains no rig source at all: not a stub, not an inert branch, nothing to read as an
 * exemption. `SnapSyncRoot` itself is untouched apart from two fields widened `private` → `internal`
 * (module-wide, and NOT exported to the ObjC framework header — verified on device).
 *
 * Being compiled INTO `:app:ios` is what lets this file reach those fields without widening anything to
 * `public`. It also means this directory is listed in the shell gate's scanned roots (`appShellSources`)
 * rather than exempted from them — so this file may hold **no decisions**. Every default, cast, fallback
 * and rendering lives on the far side of [RigHooks] / [rigPort] / the `:test:rig` builders below, in `:test:rig`, where it is
 * ordinary ungated code. Keep it that way: if this file ever needs a branch, failing the build loudly is
 * the correct outcome. (It already caught two: an env-var parse and a fallback, on the first attempt.)
 *
 * ## Why an eager initializer rather than a call in `SnapSyncRoot`
 * So the shell gains no line at all. Measured on device (SE2, iOS 26.6): it fires, binds, and serves.
 *
 * ## Nothing is forced here, including `SnapSyncRoot` itself
 * `SnapSyncRoot.app` and `.host` are passed as **thunks**. Both are `by lazy`, and touching `host` installs
 * the permission-grant subscriptions, which `ios-app-shell` forbids on a cold background wake. This file
 * captures lambdas and binds a socket; the graph is forced by the first request that needs it, which forces
 * exactly what a real entry point would.
 */
/**
 * The upload-mechanism pin's **only** touch on production: point the composition root's override thunk at
 * the channel's holder, before anything forces the graph.
 *
 * A bare assignment, deliberately — this file is inside the shell gate's scanned roots and may hold no
 * decisions. Everything the pin does (parsing, clamping, reporting what actually resolves) is in
 * `:test:rig`, on the far side of the seam. `SnapSyncRoot.uploadMechanismOverrideSource` defaults to
 * `{ null }` and this line is its only assigner anywhere, so a build compiled without
 * `-Psnapsync.rig=true` — which contains none of this file — cannot carry a pin at all.
 */
@EagerInitialization
@Suppress("unused")
private val uploadMechanismPin: Unit = run {
    SnapSyncRoot.uploadMechanismOverrideSource = UploadMechanismPin::pinned
}

@EagerInitialization
@Suppress("unused")
private val rigBoot: Unit = startRig()

private fun startRig() = RigServer(
    core = { SnapSyncRoot.app },
    host = { SnapSyncRoot.host },
    hooks = iosHooks(),
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
private fun iosHooks() = RigHooks(
    bootedAt = NSDate().description,
    // The tier this OS is on, which is what this field has always reported. Which mechanism is RUNNING
    // is now a runtime fact that changes with permission, so it is not a boot-time value.
    uploadTier = resolveUploadMechanism(
        backgroundUploadSupported = SnapSyncRoot.osSupportsOsDrivenUpload,
        permission = PermissionStatus.GRANTED,
    ).diagnosticName,
    uploadBase = bakedUploadBase(),
    // A compile-time fact, read rather than derived: the adapter that CHOSE the binding is the one that
    // reports it, so the rig cannot disagree with the transport about what this build does.
    transferBinding = transferSessionBinding,
    // Swift calls entry points from the main thread; so does the rig. A trigger invoked on another lane
    // would not be the call the OS makes, which is the whole reason triggers are entry points.
    mainLane = Dispatchers.Main,
    deviceLog = IosDeviceLogSource(),
    triggers = triggers(),
    excludedTriggers = excludedTriggers(),
    // The `/user` maps and the `/device` verbs are built in `:test:rig`, not here. Same reason every
    // default and cast already lives there: this file is compiled INTO `:app:ios` and is scanned by the
    // shell gate, which permits no decisions — and a command map's bodies are full of them.
    userCommands = userCommands { SnapSyncRoot.host },
    excludedUserCommands = excludedUserCommands(),
    deviceCommands = deviceCommands(
        core = { SnapSyncRoot.app },
        photoAccess = SnapSyncRoot.permission,
        osSupportsOsDrivenUpload = SnapSyncRoot.osSupportsOsDrivenUpload,
    ),
    readGallery = galleryReader(core = { SnapSyncRoot.app }),
    osExtensionEnabled = osExtensionEnabled(osSupportsOsDrivenUpload = SnapSyncRoot.osSupportsOsDrivenUpload),
    // The path decision (and its `null` case) lives in `:test:rig`; this side supplies only the write,
    // which has no branch to make. `Documents/` rather than the App Group deliberately: a simulator host
    // reads it with `xcrun simctl get_app_container <dev> app.snapsync data`, and the device tooling
    // already pulls from the same place — neither needs an entitlement to get at it.
    publishBoundPort = { bound -> writeTextFile(rigPortFilePath(documentsDirectory()), bound.toString()) },
)

/**
 * Write [text] to [path], or nowhere when there is no path.
 *
 * Errors are dropped deliberately: the rig must never be able to break the app under test, and a caller
 * that finds no port file is already in exactly the state this file exists to make visible.
 */
private fun writeTextFile(path: String?, text: String) {
    (text as NSString).writeToFile(path.orEmpty(), atomically = true, encoding = NSUTF8StringEncoding, error = null)
}

/**
 * WIRED entry points. Deadlines come from [ReceiptDeadlines] rather than literals, so the number the rig
 * reports is the number the receipt actually enforces.
 */
private fun triggers(): Map<String, RigTrigger> = mapOf(
    // ── The platform hands these no completion handler: it does not wait, so neither do we ──────────
    "onForeground" to RigTrigger.Fire { SnapSyncRoot.onForeground() },
    "onBackground" to RigTrigger.Fire { SnapSyncRoot.onBackground() },
    "onPushToken" to RigTrigger.Fire { arg -> SnapSyncRoot.onPushToken(arg.orEmpty()) },
    "onPushTokenFailure" to RigTrigger.Fire { arg -> SnapSyncRoot.onPushTokenFailure(arg.orEmpty()) },
    // The WARM universal link — the SNAPSYNC-6 path, otherwise reachable only by scanning a QR by hand.
    // iOS delivers exactly this object shape to `scene(_:continue:)`.
    "onSceneContinueActivity" to RigTrigger.Fire { arg ->
        SnapSyncRoot.onSceneContinueActivity(browsingWebActivity(arg.orEmpty()))
    },

    // ── The platform hands these an OS completion handler, already wrapped in `OsReceipt`. The rig
    //    supplies that handler, so it RECEIVES completion on the same channel the OS does. ───────────
    "onSilentPush" to RigTrigger.Receipted(ReceiptDeadlines.SILENT_PUSH.inWholeMilliseconds) { arg, done ->
        SnapSyncRoot.onSilentPush(mapOf("eventId" to arg), done)
    },
    "runDownloadBackstop" to
        RigTrigger.Receipted(ReceiptDeadlines.BACKGROUND_TASK.inWholeMilliseconds) { _, done ->
            SnapSyncRoot.runDownloadBackstop(done)
        },
    "runUploadHeartbeat" to
        RigTrigger.Receipted(ReceiptDeadlines.BACKGROUND_TASK.inWholeMilliseconds) { _, done ->
            SnapSyncRoot.runUploadHeartbeat(done)
        },
    // Exercises the session-identifier ROUTING — one of only two pinned complexity suppressions in
    // `SnapSyncRoot`, and untestable by any other means.
    "handleBackgroundUrlSession" to
        RigTrigger.Receipted(ReceiptDeadlines.BACKGROUND_EVENTS.inWholeMilliseconds) { arg, done ->
            SnapSyncRoot.handleBackgroundUrlSession(arg.orEmpty(), done)
        },
)

/**
 * EXCLUDED entry points, each with the consequence that makes the omission safe rather than an oversight.
 * The coverage guard asserts wired + excluded equals the derived `@PlatformEntry` population, exactly.
 */
private fun excludedTriggers(): Map<String, String> = mapOf(
    "onLaunch" to
        "registers NSNotificationCenter observers documented as never removed — re-invoking " +
        "double-registers them and corrupts the process under test. Reset is a relaunch.",
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
