## Context

`2026-07-16-migrate-to-universal-links` shipped with every automated check green — the decoder tested on
two targets, the AASA served and accepted by Apple's CDN (404→200), the entitlement verified in the
installed binary, an architecture guard holding the domain across four files — and the feature did not
work at all. The single seam none of that covers is the Swift shell, which the project declares
wiring-only and untestable.

The failure mode is what makes this worth a design doc rather than a one-line fix. It is **silent and
looks like success**: iOS matches the AASA and foregrounds the app, so the link appears to work. It
merely discards the URL. On an unjoined device the create screen it lands on is the correct resting
state, so there is nothing to notice. It was found only by opening `debug.log` on a device and observing
that `onOpenUrl` had never been called.

### What was measured, on an SE2 (iOS 26.5.2), 2026-07-16

Each row is a build installed on the device and scanned with the stock Camera app; the verdict is the
presence of an `onOpenUrl` entry in `Documents/debug.log`, and cold is distinguished from warm by whether
that entry shares a timestamp with `=== app process start ===`.

| Delivery hook | Cold (app not running) | Warm (running/suspended) |
|---|---|---|
| `.onOpenURL` — **what shipped** | ✗ | ✗ |
| `.onContinueUserActivity(NSUserActivityTypeBrowsingWeb)` | ✗ | ✓ |
| `AppDelegate.application(_:continue:restorationHandler:)` | ✗ — **never called at all** | ✗ |
| **`UIWindowSceneDelegate`: `willConnectTo` + `continue`** | **✓** | **✓** |

Apple's documentation explains every row. *Supporting universal links in your app*: with Scenes, a
not-running app receives the link at `scene(_:willConnectTo:options:)` and a running one at
`scene(_:continue:)`. A SwiftUI `WindowGroup` is a scene. And in a SwiftUI app **only**
`didFinishLaunchingWithOptions` and `applicationWillTerminate` are called on the app delegate — so the
`application(_:continue:)` attempt could never have fired.

**Cold launch is the case that matters.** A stranger tapping an invite never has SnapSync running, and
bootstrapping strangers is the entire purpose of the migration.

## Goals / Non-Goals

**Goals:**

- An opened event link reaches `SnapSyncRoot.onOpenUrl(_:)` on **cold launch and warm delivery**, with
  its fragment intact.
- The contract states that **outcome**, not a mechanism — so a future platform surprise is a correction,
  not a spec that mandates a bug.
- A regression guard, so the next reader cannot delete the scene delegate as legacy cruft without CI
  objecting.
- Record what was measured, so nobody re-derives it on a device for two hours.

**Non-Goals:**

- **Making the Swift shell testable.** See the rejected simulator test. The seam stays device-verified.
- Changing the AASA, entitlement, codec, payload, or backend — all proven correct and untouched.
- Reverting the migration. Considered; rejected — the fix is proven, shipping it is about as fast, and a
  revert would restore `snapsync://` only to retire it again days later.

## Decisions

### 1. A `UIWindowSceneDelegate` handling both halves, installed from the app delegate

```swift
final class SnapSyncSceneDelegate: NSObject, UIWindowSceneDelegate {
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession,
               options connectionOptions: UIScene.ConnectionOptions) {   // COLD
        connectionOptions.userActivities.forEach(forwardIfEventLink)
    }
    func scene(_ scene: UIScene, continue userActivity: NSUserActivity) { // WARM
        forwardIfEventLink(userActivity)
    }
}
```

installed via `application(_:configurationForConnecting:options:)` setting `config.delegateClass`. The
two callbacks are **not alternatives** — they are the cold and warm halves, and a fix with only one is a
half-fix that tests green on whichever half you happen to try. That is not hypothetical: it is exactly
what happened here, twice.

*Alternatives considered.* `.onOpenURL` — what shipped; the `application(_:open:options:)` path a custom
scheme uses; never fires for a universal link (and note that multiple respected sources recommend it for
this exact purpose, which is why it looked right). `.onContinueUserActivity` — warm only; on a cold
launch the activity is delivered before the view attaches and SwiftUI does not replay it.
`application(_:continue:restorationHandler:)` — never called in a SwiftUI app. The Info.plist
`UISceneDelegateClassName` route — equivalent, but the app-delegate route takes priority when both exist,
and it keeps the wiring in one file.

*Risk considered and measured.* A custom scene delegate could replace SwiftUI's and break `WindowGroup`
(a black screen — worse than the bug). It does not: verified on device.

### 2. The requirement states the outcome; the mechanism is this decision

The shipped requirement read *"SHALL forward an incoming event-link URL — via SwiftUI `onOpenURL`,
handling both cold-launch and warm delivery"*. That is the root cause, and it is worth naming precisely:
it **pins a mechanism** and **asserts a property the mechanism cannot deliver**. A conforming
implementation is broken. Nothing in the tree could contradict it, because the layer that would is
untestable by rule — so the false claim was unfalsifiable by construction.

The requirement now says *what must be true* (the link reaches the decoder, cold and warm, fragment
intact). Which callback carries it lives here, with the evidence. If iOS 28 moves it again, that is a
decision revised — not a contract that was lying.

This generalises: a spec should pin a mechanism only where the mechanism **is** the contract (the
`event-link` fragment rule is load-bearing and belongs in a `SHALL`) — not where it is an incidental
platform detail nobody measured.

### 3. Guard the Swift shell's structure, but do not pretend to test its behaviour

`:test:architecture` reads Kotlin, entitlements, xcconfig, Info.plist, and `backend/src/config.ts` —
**no Swift**. The one file that broke is the one file no guard inspects. That is the "wiring-only,
untestable" rule expressed as a build config, and it is why this shipped.

The guard is honestly a **regression guard, not a discovery guard**: it could not have caught the
original (nobody knew `willConnectTo` existed to assert). Its value is the counterfactual — someone reads
`SnapSyncSceneDelegate`, thinks *"SwiftUI has `.onOpenURL`, this UIKit thing is cruft"*, deletes it, and
every invite dies with green CI. That is the same species as
`architecture-guards`' own founding story: *"a one-line edit that reads as a security improvement and
disables background sync entirely — silently, and only on locked devices."*

So the **failure message is the artifact**, more than the assertion: it is the only place that reader
meets the evidence before deleting. And the guard's files must be added to the test task's `inputs`, or
it silently never re-runs — a trap this repo has already hit once and documents (*"a guard that goes
stale is a guard that fails open"*).

### 4. Rejected: a simulator universal-link test in CI

This is the one that would have caught the original, so its rejection needs to be on the record or it
will be re-proposed.

The infrastructure exists — `screenshots.yml` already boots a simulator, installs the app, and launches
it on `macos-26`. And the obvious blocker turned out not to be one: CI builds the simulator app with
`CODE_SIGNING_ALLOWED=NO`, which yields **no entitlements at all** (`codesign -d --entitlements` is
empty; the binary is merely `adhoc, linker-signed`) — but an app **can** be ad-hoc signed *with*
entitlements and no certificate, which was verified:

```
codesign -f -s - --entitlements sim.entitlements SnapSync.app
codesign -d --entitlements :-  →  com.apple.developer.associated-domains => ["applinks:snapsync.stho.net"]
```

It is rejected on different grounds:

- Apple's **TN3155: Debugging universal links** — the authoritative guide to exactly this problem —
  **never mentions the simulator**. Its recommended test is a long-press in Notes, on a device, with
  Developer Mode on.
- `simctl openurl` is reported to open **Safari instead of the app** on iOS 15+/Xcode 15+.
- Device and simulator link-handling are documented to diverge.

A flaky, undocumented, divergent test — guarding a **silent** failure — is worse than none: it goes green
while the device is broken, which is the disease, not the cure. The seam stays device-verified, and the
text guard (§3) covers the realistic threat.

## Risks / Trade-offs

- **The Swift seam is still untested behaviourally** → accepted and now explicit. Device verification is
  the only faithful check (Apple's own technote agrees). The guard pins structure; the comment carries
  the evidence.
- **The guard is brittle to renames** → intentional. Renaming `SnapSyncSceneDelegate` fails CI and drags
  the reader to the comment explaining why the class exists. Same trade the entitlements guard makes.
- **`main` is broken until this merges** → accepted (fix forward). The exposure is what it already is; a
  revert would restore `snapsync://` only to retire it again, re-doing the portal/profile/AASA work.
- **An AASA change needs an app reinstall** → devices cache the CDN copy ~weekly with no invalidation
  (TN3155). Not triggered here (the AASA is unchanged), but it constrains any future path change.

## Migration Plan

The AASA, entitlement, portal capability, and backend are already live and correct — this ships Swift
only. Merge, and the TestFlight build carries the fix.

**Verify on a device, because nothing else can**: install, kill the app, scan a real event QR (cold), then
scan again (warm). Read `Documents/debug.log`: an `onOpenUrl` entry sharing a timestamp with
`=== app process start ===` is a cold delivery; one with no preceding process start is warm. Both must
appear, exactly once each. *Do not* infer cold from the screen alone — an 8-second gap between launch and
`onOpenUrl` is a second scan delivering warm, which is how the earlier "cold works" conclusion was wrong.

**Rollback.** Reverting returns to a silently-broken link, not to a working one — there is no safer prior
state short of reverting the whole migration.

## Open Questions

- **Does `swcutil` via `sysdiagnose` give a headless "are links approved?" check?** TN3155 says
  `swcutil_show.txt` reports `Site/Fmwk Approval: approved`, and
  `pymobiledevice3 developer core-device sysdiagnose` exists. Unverified. It would replace the `swcd`
  syslog idea, which was tried and does not work (23,525 lines captured across an install, zero AASA
  activity).
- **Is any other `:app:ios` behaviour asserted by a spec but never measured?** This bug was a false
  mechanism claim in a requirement about the one untestable layer. It is unlikely to be the only one.
