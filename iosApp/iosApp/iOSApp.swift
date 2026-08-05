import SwiftUI
import SnapSyncKit
import UIKit
import BackgroundTasks

// The app delegate is a PURE TRANSCRIBER (spec `module-architecture`, "Shells are wiring only";
// migration step 12): every OS callback forwards its raw, ObjC-visible input WHOLE to Kotlin, which
// holds every decision in tested code. No `if`/`guard`/`switch` lives in this file — the pin table in
// SwiftShellGuardTest holds that at zero. The hooks:
//   1. `didFinishLaunchingWithOptions` — registers the two BGTask handlers (Apple requires
//      registration before launch finishes; the identifiers MUST be in Info.plist
//      BGTaskSchedulerPermittedIdentifiers), asks for an APNs token, and calls SnapSyncRoot.onLaunch,
//      which installs the Kotlin-side NSNotificationCenter lifecycle observers
//      (didBecomeActive/willResignActive — the scenePhase `if` that used to live in the App body is
//      a decision, so it moved to Kotlin with the OS notifications as its input).
//   2. `handleEventsForBackgroundURLSession` — the OS relaunches the app to finish background photo
//      downloads; SnapSyncRoot adopts the session, stages + imports, and invokes the handler.
//   3. remote notifications — the OS-delivered APNs token is forwarded as hex (an encoding, not a
//      decision); an incoming silent push forwards its `userInfo` dictionary WHOLE — the `eventId`
//      extraction is Kotlin's tested payload codec (capability `push-registration`).
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // APNs token → delivered async to the callbacks below; silent pushes need no user prompt.
        application.registerForRemoteNotifications()

        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: "app.snapsync.download.backstop",
            using: nil
        ) { task in
            // Kotlin holds this task until the drain finishes; no expiry ⇒ the OS kills us instead.
            task.expirationHandler = { task.setTaskCompleted(success: false) }
            SnapSyncRoot.shared.runDownloadBackstop {
                task.setTaskCompleted(success: true)
            }
        }
        // The app-driven (iOS 18–26.0) upload heartbeat: tops up the background URLSession queue and
        // catches new captures while the app is closed. No-op on ≥26.1 (the PhotoKit extension runs).
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: "app.snapsync.upload.heartbeat",
            using: nil
        ) { task in
            task.expirationHandler = { task.setTaskCompleted(success: false) }
            SnapSyncRoot.shared.runUploadHeartbeat {
                task.setTaskCompleted(success: true)
            }
        }
        // Kotlin observes the foreground/background lifecycle itself (NSNotificationCenter); this
        // call installs those observers before the scene ever becomes active.
        SnapSyncRoot.shared.onLaunch()
        return true
    }

    // Route scene callbacks to SnapSyncSceneDelegate — the ONLY way this app can receive an event link
    // (capability `event-link`). A SwiftUI `WindowGroup` IS a scene, so per Apple ("Supporting universal
    // links in your app") the system delivers the link's NSUserActivity to the SCENE delegate, and in a
    // SwiftUI app only `didFinishLaunchingWithOptions` and `applicationWillTerminate` are called on THIS
    // delegate — so an `application(_:continue:restorationHandler:)` here would never fire. It was tried
    // (2026-07-16) and never ran once.
    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let config = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        config.delegateClass = SnapSyncSceneDelegate.self
        return config
    }

    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        SnapSyncRoot.shared.handleBackgroundUrlSession(identifier: identifier, completionHandler: completionHandler)
    }

    // The OS delivered the APNs device token — forward it as lowercase hex to Kotlin, which registers it
    // with the backend. The byte→hex map is an encoding, not a decision; nothing branches here.
    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        let hex = deviceToken.map { String(format: "%02x", $0) }.joined()
        SnapSyncRoot.shared.onPushToken(hex: hex)
    }

    // Registration failed (e.g. no network / no APNs entitlement in this build). Forward it to Kotlin
    // rather than NSLog it: os_log redacts an INTERPOLATED format string wholesale, so the old line
    // reached neither idevicesyslog nor debug.log — a device that silently never receives a push, with
    // nothing anywhere saying why. Rendering the error to a string is an encoding, not a decision.
    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        SnapSyncRoot.shared.onPushTokenFailure(description: error.localizedDescription)
    }

    // A silent (content-available) remote notification arrived. Forward the payload WHOLE plus the OS
    // completion handler; Kotlin's tested codec extracts the `eventId` (a push with none fans out to
    // no arm) and the handler is always released. No parsing, no decision here.
    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        SnapSyncRoot.shared.onSilentPush(userInfo: userInfo) {
            completionHandler(.newData)
        }
    }
}

// THE event-link entry point (capability `event-link`). iOS delivers a Universal Link as an
// NSUserActivity of type NSUserActivityTypeBrowsingWeb, and because a SwiftUI `WindowGroup` IS a scene,
// it arrives HERE — at the scene delegate — and nowhere else. Apple, "Supporting universal links in
// your app": *if your app has opted into Scenes, and your app is not running, the system delivers the
// universal link to `scene(_:willConnectTo:options:)` after launch, and to `scene(_:continue:)` when the
// link is tapped while your app is running or suspended in memory.*
//
// BOTH callbacks are required, and they are NOT alternatives — they are the cold and warm halves:
//   * willConnectTo → the app was NOT running. This is the case that matters: a stranger tapping an
//     invite never has SnapSync running.
//   * continue      → the app was running or suspended.
//
// Everything else was tried on device (2026-07-16). Read this table before deleting anything — each
// row is the reason some OTHER hook exists, and the two halves must both survive:
//   * `.onOpenURL` — the `application(_:open:options:)` path the retired `snapsync://` scheme used.
//     Never fires for a universal link, cold or warm. THIS SHIPPED, and every link silently died.
//   * `AppDelegate.application(_:continue:restorationHandler:)` — never called at all: a SwiftUI app
//     gets only `didFinishLaunchingWithOptions` and `applicationWillTerminate` on its app delegate.
//   * `.onContinueUserActivity` — measured **warm YES / cold NO** in July, and it is TEMPTING to add
//     it here as a second warm path. It was tried (2026-08-04) and it DOES NOT WORK, for a structural
//     reason worth understanding before trying again:
//
//     A scene has exactly ONE delegate, and `configurationForConnecting` below makes it ours. That
//     means SwiftUI's own scene delegate is never instantiated for this scene — and SwiftUI's
//     `.onContinueUserActivity` is fed by that machinery. Measured on device: 8 warm deliveries, 8
//     hits on `scene(_:continue:)`, ZERO on the modifier. The July row measured it with SwiftUI's
//     delegate in place, because no custom one existed yet; the rows are mutually exclusive
//     configurations, not features that compose. We cannot drop the scene delegate to make room,
//     because `willConnectTo` is the only COLD path.
//
// THE OPEN QUESTION. On iOS 18.7.9 a warm-opened link reached NOTHING: the app came to the front and
// the join gate never opened, so switching events required a force-quit (Bugsink SNAPSYNC-3, four
// days of debug.log with not one warm delivery). Whether iOS 18 calls `scene(_:continue:)` at all is
// STILL UNMEASURED — there is no iOS 18 device here, and a simulator cannot stand in (on an iOS 26.5
// simulator, where the device shows 8/8, the app received zero: simulators do not route universal
// links). The next thing to try is `scene(_:willContinueUserActivityWithType:)`, which UIKit offers
// BEFORE `scene(_:continue:)` — see the change's Open Questions.
//
// Why this cost a whole device session to find: the failure is SILENT and looks like success. iOS still
// matches the AASA and still foregrounds the app, so the link "works" — it just drops the URL. On an
// unjoined device the create screen it lands on is the correct resting state, so nothing looks wrong.
// No automated test can catch it: the decoder, the AASA, and the entitlement are all provably fine, and
// this seam is the one layer the project cannot test.
//
// Stay a pass-through: hand Kotlin every delivered activity WHOLE (migration step 12 — the
// browsing-web filter and the URL read are Kotlin's tested `model/` codec, which passes the raw
// `absoluteString` through, fragment included — the entire payload rides in the FRAGMENT, so
// dropping it drops the event id).
final class SnapSyncSceneDelegate: NSObject, UIWindowSceneDelegate {
    // COLD: the link that launched us arrives in the connection options.
    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        connectionOptions.userActivities.forEach { SnapSyncRoot.shared.onLaunchActivity(activity: $0) }
    }

    // WARM (1 of 2): the app was already running or suspended. Its own Kotlin entry name — the log
    // must say WHICH hook the platform invoked, or the iOS-18 question stays exactly as open as it is.
    func scene(_ scene: UIScene, continue userActivity: NSUserActivity) {
        SnapSyncRoot.shared.onSceneContinueActivity(activity: userActivity)
    }
}

// SwiftUI App lifecycle is scene-based, which satisfies the iOS 27 SDK's mandatory UIScene
// adoption; the delegate adaptor adds the launch/background hooks above. The scene-phase `onChange`
// that used to live here is gone (migration step 12): Kotlin observes
// UIApplicationDidBecomeActive/WillResignActive itself via NSNotificationCenter (installed by
// `SnapSyncRoot.onLaunch`), so the foreground/background split is no longer a Swift decision.
@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
                // NOTE: the event link does NOT arrive here, and `.onContinueUserActivity` CANNOT be
                // added to make it — see the measured note on the scene delegate above.
        }
    }
}
