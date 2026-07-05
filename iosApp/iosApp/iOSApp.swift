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

// SwiftUI App lifecycle is scene-based, which satisfies the iOS 27 SDK's mandatory UIScene
// adoption; the delegate adaptor adds only the background-URLSession relaunch hook above.
@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                // A snapsync:// config deeplink (scanned by the Camera app) opens here, cold or
                // warm. Stay a thin pass-through: hand the raw URL string to Kotlin, which decodes,
                // validates, and persists it. No parsing in Swift.
                .onOpenURL { url in
                    SnapSyncRoot.shared.onOpenUrl(url: url.absoluteString)
                }
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
