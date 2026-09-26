package app.snapsync.ios

import app.snapsync.model.PlatformEntry
import platform.UIKit.UIViewController

/**
 * The Compose door Swift's `ContentView` pulls (`makeUIViewController`): the UI adapter answers it — a placeholder
 * while the process has never been active, else a live screen (`:adapter:ios:ui`'s `IosUi`). Wiring only: the scene
 * rule and the screen are the adapter's.
 */
@PlatformEntry
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController = SnapSyncRoot.ui.viewController()
