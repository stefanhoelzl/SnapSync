import SwiftUI
import SnapSyncForgeKit
import UIKit

// The forge binary's Swift shell, and there is deliberately almost nothing in it.
//
// It calls exactly one Kotlin entry point and holds no state, no delegate callbacks, no background
// task registration and no push registration — because this binary has none of the code that would
// answer them. It does not link `SnapSyncKit`, so `SnapSyncRoot` is not in this process at all: a
// forge build cannot boot the live stack, contact a backend, attest, or read a photo library, because
// there is nothing here that could.
//
// That is what replaced `ForgeShell`, which implemented ~15 `Shell` members whose entire job was to
// receive real OS callbacks and do nothing with them — every one of which had to keep doing nothing
// correctly, forever, in a binary that shipped to users.
//
// Like every other Swift shell here it is a pure transcriber: no `if`, no `guard`, no `switch`
// (`SwiftShellGuardTest` holds those at zero outside its pinned exceptions).
@main
struct ForgeApp: App {
    var body: some Scene {
        WindowGroup {
            ForgeView().ignoresSafeArea(.all)
        }
    }
}

// Bridges the Compose view controller into SwiftUI.
//
// Unconditionally, unlike the app's `ContentView`: that one gates composition on the first activation
// because iOS connects UI scenes in the BACKGROUND, and a silent-push wake would otherwise stand up a
// Compose runtime in a process that cannot draw. Nothing wakes this binary in the background — it has
// no push entitlement, no background modes and no BGTask registration — so the gate would guard
// against a state this target cannot reach.
struct ForgeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        ForgeViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
