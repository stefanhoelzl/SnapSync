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
//   * `.onOpenURL` — measured ✗ cold and ✗ warm in July's matrix, with SwiftUI's own scene delegate in
//     place. THIS SHIPPED as the sole hook, and every link silently died. It is wired again NOW, on the
//     WindowGroup below, because it is measured to deliver where the scene delegate does not — see the
//     note there, including why the difference between the two measurements is unexplained.
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
// THE iOS 18 GAP, NOW MEASURED (Bugsink SNAPSYNC-25 + SNAPSYNC-26, one 80-second window on an
// iPhone XS running 18.7.9, build 607). It is no longer an open question in the "unmeasured"
// sense — what remains open is only WHICH hook works there, not whether this one does:
//   * COLD works. `=== app process start ===` at 19:05:47.773 is followed 33 ms later by
//     `onLaunchActivity(type=NSUserActivityTypeBrowsingWeb url=present)` and a forwarded
//     `onOpenUrl`, fragment intact. So `willConnectTo` fires on 18, and OUR delegate is the
//     scene's delegate there — the runtime install below is not inert on 18.
//   * WARM does not. Three consecutive warm taps (19:04:40, 19:05:12, 19:05:31) each brought the
//     app to the front — `onForeground` fired every time, so iOS DID activate us from the link —
//     and not one produced an `onSceneContinueActivity` line. The third was while UNJOINED, so
//     this is the platform hook and nothing about join or switch logic.
// A universal link on iOS 18.7.9 therefore activates the app and never delivers the activity to
// `scene(_:continue:)`. Re-measure at the next iOS 18 point release; evidence is one device.
//
// WHAT IS STILL UNKNOWN is why, and the observation-only hooks below exist to narrow it on the
// next dump rather than by guessing: `willContinueUserActivityWithType` separates "UIKit never
// started a continuation" from "UIKit started one our delegate did not receive", and the scene
// lifecycle forwards prove the delegate is being talked to warm at all.
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
    //
    // The COUNT is reported BEFORE the loop, always, zero included. Until now the only Kotlin call
    // here sat INSIDE the forEach, so a scene connecting with an empty `userActivities` recorded
    // nothing at all — and "the delegate is installed and iOS handed it nothing" was byte-identical
    // to "the delegate was never installed". SwiftShellGuardTest's forwarding rule cannot catch
    // that: the call is lexically present, it merely never runs. SNAPSYNC-25 is what that
    // ambiguity costs (spec `module-architecture`, "Absence is never silent").
    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        SnapSyncRoot.shared.onSceneWillConnect(activities: Int32(connectionOptions.userActivities.count))
        connectionOptions.userActivities.forEach { SnapSyncRoot.shared.onLaunchActivity(activity: $0) }
    }

    // WARM (1 of 2): the app was already running or suspended. Its own Kotlin entry name — the log
    // must say WHICH hook the platform invoked, or the iOS-18 question stays exactly as open as it is.
    func scene(_ scene: UIScene, continue userActivity: NSUserActivity) {
        SnapSyncRoot.shared.onSceneContinueActivity(activity: userActivity)
    }

    // OBSERVATION ONLY. UIKit offers this BEFORE `scene(_:continue:)` and hands over only the
    // activity TYPE, never the activity — so it can deliver no URL and cannot fix anything. Its
    // entire value is diagnostic: on the next iOS 18 dump, this line present with no
    // `onSceneContinueActivity` after it means UIKit started a continuation our delegate did not
    // receive; this line absent means UIKit never started one.
    func scene(_ scene: UIScene, willContinueUserActivityWithType userActivityType: String) {
        SnapSyncRoot.shared.onSceneWillContinueActivity(activityType: userActivityType)
    }

    // The third of UISceneDelegate's continuation trio, and the only one that NAMES a failure: UIKit
    // calls it when it attempted a continuation and could not finish. Measured NEVER to fire on iOS
    // 18.7.9 (builds 683/687) — UIKit announces via `willContinueUserActivityWithType` and then abandons
    // the work without using this path at all, which is why the failure was diagnosable only as a
    // silence for a whole day. It stays wired so the next such silence is not: absent AND never-called
    // are the same observation only while the hook does not exist.
    func scene(
        _ scene: UIScene,
        didFailToContinueUserActivityWithType userActivityType: String,
        error: Error
    ) {
        SnapSyncRoot.shared.onSceneDidFailToContinueActivity(
            activityType: userActivityType,
            description: error.localizedDescription
        )
    }

    // OBSERVATION ONLY: the scene lifecycle, so a warm link activation is visible as SOMETHING even
    // when no continuation arrives. Without these, a warm delivery that fails looks exactly like a
    // link iOS never routed to us — which is the pair SNAPSYNC-25 could not tell apart. Distinct
    // from Kotlin's own UIApplicationDidBecomeActive observer (`onForeground`): these fire on the
    // DELEGATE, so they answer "is our delegate live warm?", which the application-wide
    // notification cannot.
    func sceneWillEnterForeground(_ scene: UIScene) {
        SnapSyncRoot.shared.onSceneWillEnterForeground()
    }

    func sceneDidBecomeActive(_ scene: UIScene) {
        SnapSyncRoot.shared.onSceneDidBecomeActive()
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        SnapSyncRoot.shared.onSceneDidDisconnect()
    }

    // OBSERVATION ONLY: the custom-scheme delivery path. The `snapsync` scheme is RETIRED and the
    // Info.plist declares no CFBundleURLTypes, so nothing should ever arrive here — which is
    // precisely the point of forwarding it. Should iOS 18 turn out to route a universal link
    // through this hook, the log says so instead of the URL vanishing.
    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        SnapSyncRoot.shared.onSceneOpenUrlContexts(urls: URLContexts.map { $0.url.absoluteString })
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
                // SwiftUI's delivery path — the one that carries a link opened while the app is ALREADY
                // RUNNING on iOS 18.7.9, where the scene delegate's continuation never arrives.
                //
                // This file argued the opposite for six weeks: "`.onOpenURL` never fires for a universal
                // link, cold or warm. THIS SHIPPED, and every invite silently died." That measurement was
                // real — and so is this one. WHY THEY DIFFER IS UNEXPLAINED, and is deliberately not
                // guessed at here.
                //
                // The tempting story is that our custom scene delegate displaces SwiftUI's and starves
                // this modifier. Our own record contradicts it: July's ✗/✗ was measured with SwiftUI's
                // OWN delegate in place, and the modifier fires today with a custom one installed —
                // the reverse of what starvation predicts. Four mechanisms have now been proposed for
                // this defect and abandoned (`willContinueUserActivityWithType`, warm-vs-cold, the
                // link's source, and starvation); the fix depends on none of them.
                //
                // NEITHER PATH IS RELIABLE ALONE, both measured: the scene delegate's continuation never
                // fires on 18.7.9 while running (builds 681/683 — from Notes, WhatsApp and Safari's
                // banner alike), and this modifier fired for only 2 of 4 deliveries on 26.6 (build 687).
                // The union delivered in every configuration tested, so both are declared and the
                // duplicates they produce are absorbed by the gate, which acts on a repeated link once
                // (capability `event-link`). That is why "delivery exactly once" is no longer a property
                // we hope the hooks have.
                //
                // Reported independently with our exact signature — SwiftUI + custom scene delegate,
                // `willContinue` fires, `continue` does not, cold fine, "works in a barebones project" —
                // in Apple Developer Forums 758864 and 746362, where DTS answers that `scene(_:continue:)`
                // is a UIKit-app path and a SwiftUI app receives the link here. A barebones project works
                // because it has no custom scene delegate to starve SwiftUI's.
                .onOpenURL { url in
                    SnapSyncRoot.shared.onSwiftUiOpenUrl(url: url.absoluteString)
                }
        }
    }
}
