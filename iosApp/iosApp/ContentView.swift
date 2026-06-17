import SwiftUI
import SnapSyncKit

// Bridges the Kotlin/Compose entry point (app.snapsync.ios.MainViewController, exposed to Swift as
// MainViewControllerKt.MainViewController) into SwiftUI.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all)
    }
}
