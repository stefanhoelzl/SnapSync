## Why

On iOS 18.7.9, a universal link opened while SnapSync is **already running** never reaches the app.
UIKit calls `scene(_:willContinueUserActivityWithType:)` — announcing a continuation — and then calls
neither `scene(_:continue:)` nor `scene(_:didFailToContinueUserActivityWithType:error:)`. It abandons
the work without using its own failure path, so the app foregrounds and nothing happens. Reproduced
from three independent sources (Notes — Apple's own TN3155 test target — WhatsApp, and Safari's smart
banner); a Camera QR scan delivers normally, and cold launch delivers from any source. The
discriminator is **whether the app is already running**, not the link's source and not warm-vs-cold as
this was first framed. Reported by a member as Bugsink `SNAPSYNC-25`: *"clicked another invite, did not
get the switch dialog"*, and every guest who receives an invite while the app is resident hits it.

The cause is ours, not Apple's. A scene has exactly **one** delegate; installing our own for the cold
path means SwiftUI's is never created, and SwiftUI's machinery is what feeds `.onOpenURL`. On iOS 26
that costs nothing because our delegate's warm path works there. On iOS 18 the SwiftUI path is the
only warm path, and we had disabled it. The same signature is reported independently in Apple Developer
Forums 758864 and 746362, where DTS answers that `scene(_:continue:)` is a UIKit-app path and a SwiftUI
app receives the link at `.onOpenURL` — and where a barebones project works precisely because it has no
custom scene delegate.

## What Changes

- **Restore SwiftUI's `.onOpenURL`** on the `WindowGroup` as a delivery path, alongside the scene
  delegate. Measured on build 687: warm delivery on iOS 18.7.9 is restored from Notes and WhatsApp, and
  the join surface renders.
- **Make event-link delivery idempotent.** Restoring `.onOpenURL` makes the platform deliver the same
  link **twice** — measured on iOS 18.7.9 cold (`onLaunchActivity` then `onSwiftUiOpenUrl`, ~130 ms
  apart) and on iOS 26.6 both warm (8 ms apart) and cold (105 ms apart). The join gate SHALL ignore a
  repeat of the same link until it is consumed, so any number of platform hooks is safe on any iOS
  version. This satisfies `ios-app-shell`'s existing "delivery SHALL be exactly once per opened link"
  as an invariant we own rather than a property of the platform we hope for.
- **Remove the app-delegate continuation trio** (`application(_:willContinueUserActivityWithType:)`,
  `application(_:continue:restorationHandler:)`, `application(_:didFailToContinueUserActivityWithType:error:)`).
  Build 683 measured all three at zero hits, confirming the 2026-07-16 "never called in a SwiftUI app"
  finding for iOS 18 as well — in the one state it had never been measured, a scene continuation UIKit
  announced and abandoned.
- **Correct three claims that are false in the contract of record.** `architecture-guards`' evidence
  bullet *"iOS 18.7.9 does NOT call `scene(_:continue:)`"* and `ios-app-shell`'s *"currently UNMET on
  iOS 18.7.9"* are both statements about the platform, disproved on that exact OS build; and
  `iOSApp.swift`'s *"`.onOpenURL` never fires for a universal link"* was true for a configuration that
  no longer exists. Every replacement claim SHALL be scoped to the configuration and build measured.
- **Admit the SwiftUI path into the delivery-seam guard.** `architecture-guards`' requirement and
  `EventLinkDeliveryTest` currently pin the scene delegate as the delivery seam; they must also pin
  `.onOpenURL`, or the fix can be deleted as cruft with CI green — the exact failure that guard exists
  to prevent.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `ios-app-shell`: the delivery requirement gains the SwiftUI `.onOpenURL` path and states that
  *exactly once* is enforced by the app, not assumed of the platform; the false "currently UNMET"
  paragraph is replaced with a correctly-scoped record.
- `event-link`: the join gate SHALL treat a repeated delivery of the same link as a no-op until
  consumed, so redundant platform hooks cannot double-provision.
- `architecture-guards`: the delivery-seam requirement covers the SwiftUI path as well as the scene
  delegate, and its evidence block is corrected to scope the iOS 18 measurement to "app already
  running" rather than to the OS.

## Impact

- `iosApp/iosApp/iOSApp.swift` — `.onOpenURL` restored on the `WindowGroup`; the app-delegate
  continuation trio removed; the falsified comment block rewritten.
- `app/ios/src/iosMain/kotlin/app/snapsync/ios/SnapSyncRoot.kt` — the `onSwiftUiOpenUrl` entry point;
  the three `onApp*` entry points removed.
- The join gate (`ui/presentation` `StatusContainerHost.onOpenUrl`, or `:domain` if the rule belongs
  deeper) — the idempotence rule, with `commonTest` coverage on both targets.
- `test/architecture` — `EventLinkDeliveryTest` extended to pin `.onOpenURL`; `SwiftShellGuardTest`
  pins shrink as the app-delegate hooks go.
- `test/rig` — trigger-inventory exclusions follow the entry-point set.
- `openspec/specs/{ios-app-shell,event-link,architecture-guards}` — the corrections above.
- No backend, AASA, entitlement, or codec change. The `autoJoin=true` dev path is the one that would
  actually double-**provision** today, so it is the sharpest test of the idempotence rule.
