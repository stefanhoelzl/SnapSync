import SwiftUI
import SnapSyncKit
import UIKit

// The app delegate exists solely to receive `handleEventsForBackgroundURLSession` — the OS relaunches
// the app to finish the manifest uploads the extension started on the shared background URLSession.
// Pass-through only: Kotlin (`SnapSyncRoot`) adopts the session and invokes the handler when done.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        SnapSyncRoot.shared.handleBackgroundUrlSession(identifier: identifier, completionHandler: completionHandler)
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
