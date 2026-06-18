import SwiftUI
import SnapSyncKit

// SwiftUI App lifecycle is scene-based, which satisfies the iOS 27 SDK's mandatory UIScene
// adoption without an AppDelegate.
@main
struct iOSApp: App {
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
    }
}
