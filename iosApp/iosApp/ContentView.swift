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

// A Compose scene is composed ONLY once the app has been active (capability `ios-app-shell`).
//
// iOS connects UI scenes in the BACKGROUND, so a process woken by a silent push or a BGTask builds this
// view too. Without the gate below it stands up a full Compose runtime and Metal renderer in a process
// that cannot draw, holds it for hours while the OS reclaims its GPU resources (which Apple's contract
// says it will), and then presents it — the shape behind two production reports of a blank /
// coloured-square status screen. `MainViewController()` therefore returns a bare placeholder until the
// app has been active and the live scene afterwards; WHICH one is Kotlin's tested decision
// (`resolveScene`), never this file's.
//
// `generation` is what makes SwiftUI ask again. The value is Kotlin's — 0 before any activation, 1
// after — and it changes exactly ONCE per process, so `.id(…)` rebuilds the representable at the first
// activation and never again. A rebuild on every foreground would throw away screen-local Compose state
// (an open settings surface, a half-typed report, a scroll position) on every ordinary app switch.
//
// Keyed on the APP-level didBecomeActive notification rather than a scene-level callback, deliberately:
// it is the signal that fires however the app is opened, INCLUDING a headless
// `pymobiledevice3 developer dvt launch`, which foregrounds the process WITHOUT connecting a scene
// session. Measured 2026-08-06 — an earlier revision of this change moved window ownership into the
// scene delegate and keyed on `sceneDidBecomeActive`; real launches worked, but a dvt-launched app never
// received that callback and showed a BLACK SCREEN, silently breaking the headless screenshot loop this
// project's on-device workflow depends on.
//
// The whole gate is a MITIGATION for a Compose Multiplatform defect (CMP-5978 — freeing and rebuilding
// GPU resources across backgrounding is the renderer's job), not an architectural preference. Delete it
// when that is fixed upstream.
struct ContentView: View {
    @State private var generation: Int32 = 0

    var body: some View {
        ComposeView()
            .id(generation)
            .ignoresSafeArea(.all)
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
                generation = SnapSyncRoot.shared.onSceneActive()
            }
    }
}
