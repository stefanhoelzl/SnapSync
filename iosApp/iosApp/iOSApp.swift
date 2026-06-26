import SwiftUI
import SnapSyncKit

// SwiftUI App lifecycle is scene-based, which satisfies the iOS 27 SDK's mandatory UIScene
// adoption without an AppDelegate.
@main
struct iOSApp: App {
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
        // SPIKE (remove later): on every foreground, ask Kotlin to enumerate the system's upload
        // jobs from the APP process and log the counts. Pass-through only — no logic in Swift.
        .onChange(of: scenePhase) { _, newPhase in
            if newPhase == .active {
                SnapSyncRoot.shared.probeUploadJobs()
            }
        }
    }
}
