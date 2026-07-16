import SwiftUI
import SnapSyncKit
import UIKit
import BackgroundTasks

// The app delegate is a thin pass-through to Kotlin for OS lifecycle hooks:
//   1. `handleEventsForBackgroundURLSession` — the OS relaunches the app to finish background photo
//      downloads; SnapSyncRoot adopts the session, stages + imports, and invokes the handler.
//   2. the `BGProcessingTask` import-tail backstop — registered at launch (Apple requires registration
//      before launch finishes; the identifier MUST be in Info.plist BGTaskSchedulerPermittedIdentifiers).
//      Its handler drains staged-but-unimported downloads.
//   3. remote notifications — register at launch; forward the OS-delivered APNs token (as hex) and any
//      incoming silent push to Kotlin (capability `push-registration`). No decisions in Swift.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Ask the OS for an APNs device token; it is delivered async to the two callbacks below. Silent
        // pushes need no user-permission prompt, so no UNUserNotificationCenter authorization request.
        application.registerForRemoteNotifications()

        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: "app.snapsync.download.backstop",
            using: nil
        ) { task in
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
    // with the backend. No parsing/decisions in Swift beyond the byte→hex encoding.
    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        let hex = deviceToken.map { String(format: "%02x", $0) }.joined()
        SnapSyncRoot.shared.onPushToken(hex: hex)
    }

    // Registration failed (e.g. no network / no APNs entitlement in this build) — log and carry on.
    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        NSLog("registerForRemoteNotifications failed: \(error.localizedDescription)")
    }

    // A silent (content-available) remote notification arrived. Pull the payload's `eventId` and hand it
    // plus the OS completion handler to Kotlin, which reconciles downloads for the active event and calls
    // the handler only after the union read + enqueue finish (so iOS keeps us alive through it). No
    // decision here — a missing eventId just completes with no data. (No parsing/logic in Swift.)
    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        guard let eventId = userInfo["eventId"] as? String else {
            completionHandler(.noData)
            return
        }
        SnapSyncRoot.shared.onSilentPush(eventId: eventId) {
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
// Everything else was tried on device (2026-07-16) and does NOT work, however much the internet
// recommends it:
//   * `.onOpenURL` — the `application(_:open:options:)` path, which is what the retired `snapsync://`
//     custom scheme used. Never fires for a universal link. THIS SHIPPED, and every link silently died.
//   * `.onContinueUserActivity` — warm only; on a cold launch the activity is delivered before the view
//     attaches, and SwiftUI does not replay it.
//   * `AppDelegate.application(_:continue:restorationHandler:)` — never called at all: a SwiftUI app
//     gets only `didFinishLaunchingWithOptions` and `applicationWillTerminate` on its app delegate.
//
// Why this cost a whole device session to find: the failure is SILENT and looks like success. iOS still
// matches the AASA and still foregrounds the app, so the link "works" — it just drops the URL. On an
// unjoined device the create screen it lands on is the correct resting state, so nothing looks wrong.
// No automated test can catch it: the decoder, the AASA, and the entitlement are all provably fine, and
// this seam is the one layer the project cannot test.
//
// Stay a pass-through: hand Kotlin the raw `absoluteString`, never a trimmed URL — the entire payload
// rides in the FRAGMENT, so dropping it drops the event id.
final class SnapSyncSceneDelegate: NSObject, UIWindowSceneDelegate {
    // COLD: the link that launched us arrives in the connection options.
    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        connectionOptions.userActivities.forEach(forwardIfEventLink)
    }

    // WARM: the app was already running or suspended.
    func scene(_ scene: UIScene, continue userActivity: NSUserActivity) {
        forwardIfEventLink(userActivity)
    }

    private func forwardIfEventLink(_ userActivity: NSUserActivity) {
        guard userActivity.activityType == NSUserActivityTypeBrowsingWeb,
              let url = userActivity.webpageURL else { return }
        SnapSyncRoot.shared.onOpenUrl(url: url.absoluteString)
    }
}

// SwiftUI App lifecycle is scene-based, which satisfies the iOS 27 SDK's mandatory UIScene
// adoption; the delegate adaptor adds only the background-URLSession relaunch hook above.
@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                // NOTE: the event link does NOT arrive here. It is delivered as an NSUserActivity to
                // `AppDelegate.application(_:continue:restorationHandler:)` — see the long note there
                // before reaching for `.onOpenURL`/`.onContinueUserActivity`; both were tried on device
                // and neither is sufficient.
        }
        // Forward the scene's foreground transitions to Kotlin, which gates the observed-completions
        // poll that keeps upload progress live while the screen is shown. Pass-through only.
        .onChange(of: scenePhase) { _, newPhase in
            if newPhase == .active {
                SnapSyncRoot.shared.onForeground()
            } else {
                SnapSyncRoot.shared.onBackground()
            }
        }
    }
}
