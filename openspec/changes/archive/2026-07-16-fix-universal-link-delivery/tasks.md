## 1. The fix (Swift shell)

- [x] 1.1 Add `SnapSyncSceneDelegate: NSObject, UIWindowSceneDelegate` to `iosApp/iosApp/iOSApp.swift` implementing **both** halves: `scene(_:willConnectTo:options:)` forwarding every `connectionOptions.userActivities` entry (cold), and `scene(_:continue:)` (warm). Both filter on `activityType == NSUserActivityTypeBrowsingWeb` and forward `userActivity.webpageURL!.absoluteString` — never a trimmed URL; the payload is in the fragment.
- [x] 1.2 Install it from the app delegate: `application(_:configurationForConnecting:options:)` returning a `UISceneConfiguration` with `delegateClass = SnapSyncSceneDelegate.self`. Without this the delegate is inert.
- [x] 1.3 Remove `.onOpenURL` from the `WindowGroup`. It never fires for a universal link; leaving it is dead code that reads as the mechanism and invites the next person to delete the delegate instead.
- [x] 1.4 Comment the delegate with the evidence — that `.onOpenURL`, `.onContinueUserActivity`, and `application(_:continue:restorationHandler:)` were each measured on device and are each insufficient, and that the failure is silent. This comment is load-bearing: it is what a future "simplification" has to get past.

## 2. The guard

- [x] 2.1 Add `iosApp/**/*.swift` to the `inputs.files` fileTree in `test/architecture/build.gradle.kts`. **Do this first and verify it** — without it the guard reports UP-TO-DATE and never re-runs when the Swift changes ("a guard that goes stale is a guard that fails open", already documented in that file).
- [x] 2.2 Add the guard asserting the shell installs a scene delegate via `configurationForConnecting`, implements `willConnectTo` **and** `scene(_:continue:)`, and forwards to `onOpenUrl`. Model on `DataProtectionEntitlementTest`, vacuity check included.
- [x] 2.3 Write the failure message to carry the evidence (§1.4), not just "assertion failed" — it is the only thing between the next reader and re-introducing the bug.
- [x] 2.4 **Prove the guard fails on drift**: delete the scene delegate locally, run `./gradlew :test:architecture:test` *without* `--rerun-tasks`, confirm it FAILS, then restore. A guard never seen red is not known to work — this exact check caught the stale-inputs hole in the `event-link` domain guard.

## 3. Specs and docs

- [x] 3.1 `openspec/specs/ios-app-shell/spec.md` — the *iOS application shell* requirement is replaced by the delta (outcome, not mechanism). Applied at archive.
- [x] 3.2 `CLAUDE.md`, ssh-mac section: the **re-sign silently replaces `associated-domains` with the profile's `*` wildcard** — it resolves entitlements out of the profile, so the app is entitled to any domain and claims none, and universal links then silently fail. Narrow it with PlistBuddy before signing (`plutil` cannot: it reads the dotted key as a key path).
- [x] 3.3 `CLAUDE.md`, event-link verification: **`swcutil` via `sysdiagnose`** is the headless "are links approved?" check (`Site/Fmwk Approval: approved`, Apple TN3155); `pymobiledevice3 developer core-device sysdiagnose` fetches it. Note `swcd` is **not** visible in `idevicesyslog` (measured: 23,525 lines across an install, zero AASA activity).
- [x] 3.4 `CLAUDE.md`: **changing the AASA requires reinstalling the app** — devices cache the CDN copy ~weekly and there is no invalidation (TN3155).
- [x] 3.5 `CLAUDE.md`, diagnostics: reinforce that `NSLog` is redacted (`<private>`) and therefore useless for diagnostics — the file logger is the only channel. Cost an entire wasted device cycle today despite already being documented.

## 4. Verify (device only — nothing else is faithful)

- [x] 4.1 `./gradlew build` and `compileIosMainKotlinMetadata`.
- [x] 4.2 Build a dev IPA via ssh-mac and install. Re-sign carefully per §3.2 — a wildcard `associated-domains` yields an app that claims no domain and silently fails.
- [x] 4.3 **Cold**: SIGKILL the app (SIGTERM is ignored), *verify it is dead*, scan a real event QR. Expect the join gate (a bogus event id → "invalid invite" — that outcome proves link + fragment + decode together).
- [x] 4.4 **Warm**: scan again with the app running. Expect the same.
- [x] 4.5 Confirm in `Documents/debug.log`, not on the screen: a cold delivery is an `onOpenUrl` sharing a timestamp with `=== app process start ===`; a warm one has no preceding process start. **Both, exactly once each.** A gap of several seconds between launch and `onOpenUrl` means a *second* scan delivered warm — that misread is how "cold works" was concluded wrongly the first time.
- [x] 4.6 Confirm `WindowGroup` still renders (a custom scene delegate could replace SwiftUI's and black-screen the app — measured fine, but re-check after any change here).

> **§4 was performed during the 2026-07-16 device session, on this exact Swift** (verified byte-identical). Cold: `app process start` and `onOpenUrl` share the 14:12:22 timestamp. Warm: `onOpenUrl` at 14:12:29 with no preceding process start. One delivery each; `WindowGroup` rendered normally (no black screen). Both scans showed the join gate's invalid-invite state, which is the bogus event id resolving 404 — proving link, fragment, and decode together.
