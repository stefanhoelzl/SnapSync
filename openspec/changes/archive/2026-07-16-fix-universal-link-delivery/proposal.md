## Why

**`main` silently drops every event link.** `2026-07-16-migrate-to-universal-links` shipped an app that
receives the link via SwiftUI's `.onOpenURL` — which never fires for a Universal Link. iOS matches the
AASA and foregrounds the app, so the link *looks* like it worked; the URL is discarded and the user lands
on whatever screen was already there. On an unjoined device that is the create screen, which is its
correct resting state, so nothing looks wrong at all.

This is a **regression on the primary invite channel**: a Camera-scanned QR previously opened the app and
provisioned via `snapsync://`. It now opens the app and does nothing. `main` auto-promotes to public
TestFlight (`ios-testflight-delivery`).

The cause is documented, not mysterious. Apple, *Supporting universal links in your app*:

> If your app has opted into **Scenes**, and your app is not running, the system delivers the universal
> link to the `scene(_:willConnectTo:options:)` delegate method after launch, and to `scene(_:continue:)`
> when the universal link is tapped while your app is running or suspended in memory.

A SwiftUI `WindowGroup` **is** a scene. The link arrives as an `NSUserActivity` at the **scene** delegate
— and this app had none.

The deeper defect is in the contract, not the code: `ios-app-shell`'s *iOS application shell* requirement
**mandates the broken mechanism** — "SHALL forward an incoming event-link URL — via SwiftUI `onOpenURL`,
handling both cold-launch and warm delivery". It pins a *mechanism* and asserts a *property that
mechanism cannot deliver*. A conforming implementation is broken, and nothing in the tree can contradict
it, because `:app:ios` is untestable by rule.

## What Changes

- The Swift shell gains **`SnapSyncSceneDelegate`** (a `UIWindowSceneDelegate`), installed via the app
  delegate's `application(_:configurationForConnecting:options:)`. It implements **both** halves:
  `scene(_:willConnectTo:options:)` (cold — the app was not running) and `scene(_:continue:)` (warm).
  `.onOpenURL` is removed; it never fired.
- **The requirement is restated as an outcome, not a mechanism.** *iOS application shell* SHALL require
  that an opened event link reaches `SnapSyncRoot.onOpenUrl(_:)` **on both a cold launch and a warm
  delivery**, with its fragment intact. Which callback carries it is a **decision** (design.md), where
  being wrong is a correction rather than a contradiction of the contract of record.
- **`architecture-guards` gains a guard** over the Swift shell — the first: it pins that the scene
  delegate exists, is installed via `configurationForConnecting`, handles **both** `willConnectTo` and
  `continue`, and forwards to `onOpenUrl`. `:test:architecture` reads no Swift today, which is why the
  one file that broke is the one file no guard inspects.
- **Runbook corrections** in `CLAUDE.md`, each of which cost time today:
  - the **ssh-mac re-sign silently replaces `associated-domains` with the profile's `*` wildcard** (it
    resolves entitlements *out of the profile*), producing an app entitled to any domain and claiming
    none — universal links then silently do not work;
  - **`swcutil` via `sysdiagnose`** is the headless device check for whether links are approved
    (`Site/Fmwk Approval: approved`, per Apple's TN3155);
  - **changing the AASA requires reinstalling the app** — devices cache the CDN copy for ~a week and
    there is no invalidation;
  - `swcd` is **not** observable via `idevicesyslog` (closes an open question in the migrate change's
    design.md).

## Capabilities

### New Capabilities
_None._

### Modified Capabilities
- `ios-app-shell`: *iOS application shell* — the event link SHALL reach `onOpenUrl` on **cold launch and
  warm delivery**, fragment intact, via the scene delegate. Replaces the false `onOpenURL` mandate.
- `architecture-guards`: **ADDED** — a guard that the Swift shell installs a scene delegate handling both
  the cold and warm halves. Extends the guards to Swift for the first time.

## Impact

**Code.** `iosApp/iosApp/iOSApp.swift` (+`SnapSyncSceneDelegate`, +`configurationForConnecting`,
−`.onOpenURL`); `test/architecture` (+the guard, +`iosApp/**/*.swift` in the task inputs, or the guard
never re-runs when its subject changes).

**Verification.** Device-only, and unavoidably so: Apple's own universal-link debugging technote is
entirely device-centric and never mentions the simulator. Proven on the SE2 on 2026-07-16 — cold launch
and warm delivery, one delivery each, `WindowGroup` intact.

**No impact.** The AASA, the entitlement, the portal capability, the backend routes, the codec, and the
payload are all unchanged and all already proven correct (`app-site-association.cdn-apple.com` returns
200; the fragment arrives intact). Only the Swift delivery seam was wrong.

**Rejected: a simulator test in CI.** It would have caught the original, and the infrastructure exists
(`screenshots.yml` boots a simulator and installs the app). Rejected on evidence — see design.md.
