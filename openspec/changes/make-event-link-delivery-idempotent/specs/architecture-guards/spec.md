## MODIFIED Requirements

### Requirement: The Swift shell keeps the event link's delivery seam

A test-only JVM guard SHALL assert that the iOS Swift shell still carries **every** event-link delivery
path and forwards each to the Kotlin entry point (capability `ios-app-shell`). There are two, fed by
different machinery, and neither is sufficient alone — so the guard SHALL pin both. Specifically it
SHALL assert that the shell:

0. declares SwiftUI's **`.onOpenURL`** on the `WindowGroup`, forwarding the URL to Kotlin — the path
   that carries a link opened while the app is already RUNNING on iOS 18.7.9, where the scene
   delegate's continuation never fires. It is the likeliest of all of these to be deleted as cruft,
   because this file's own history argued for years that it never fires for a universal link;

1. installs a scene delegate via the app delegate's `application(_:configurationForConnecting:options:)`
   (setting `delegateClass`) — without this the delegate is inert;
2. implements `scene(_:willConnectTo:options:)` — the **cold** half, reading the launching link from the
   connection options — and forwards the **count** of delivered activities to Kotlin **before** iterating
   them, so a scene that connects carrying none is still recorded;
3. implements `scene(_:continue:)` — the **warm** half; and
4. forwards the delivered `NSUserActivity` **whole** from that delegate, with each hook forwarding
   under its **own** Kotlin entry-point name (cold and warm are distinguishable in a device log, which
   is what lets a dump say which hook the platform actually invoked) (migration step 12, the transcriber law: the browsing-web filter and the raw
   `absoluteString` read — fragment included — are the tested `model/` codec's, routed on to
   `onOpenUrl` in Kotlin; a Swift-side field extraction would be an unpinned decision under the
   shell gates).

The guard SHALL fail loudly rather than vacuously: if the file it inspects has moved or no longer
contains the markers it expects, it SHALL fail rather than pass while scanning nothing. The guarded Swift
sources SHALL be declared as inputs of the guard's test task, or the guard silently stops re-running when
its subject changes — a guard that goes stale is a guard that fails open.

This is the first guard over Swift. `:app:ios` and the Swift shell are wiring-only and **untested** by the
project's hard rule, and on 2026-07-16 that rule's blind spot shipped: the app received event links via
SwiftUI's `onOpenURL`, which never fires for a universal link, so **every invite silently did nothing**
while every automated check stayed green. The guard does not test behavior — the seam remains
device-verified — it pins the **structure** that behavior depends on, which is exactly what this
capability exists for.

The guard is a **regression guard, not a discovery guard**, and SHALL be understood as such: it could not
have caught the original defect, because nobody knew `scene(_:willConnectTo:options:)` was the answer
until it was measured on a device. What it catches is the realistic future: a reader sees a UIKit scene
delegate in a SwiftUI app, concludes it is legacy cruft that `.onOpenURL` supersedes, deletes it — and
every event link dies silently, with CI green. That is the same species as *the data-protection
entitlement never raises the default protection class*: a small edit that reads as an improvement and
disables a whole feature invisibly.

Because the failure is invisible, the guard's **failure message** SHALL carry the evidence — it is the
only thing standing between the next reader and re-introducing the bug. The evidence:

- `.onOpenURL` and `application(_:continue:restorationHandler:)` never fire for a universal link at all.
- SwiftUI's continuation modifier never fires **cold**, and **cannot be added as a second warm path
  while this delegate exists**: a scene has exactly one delegate, this app installs its own, so
  SwiftUI's — which feeds that modifier — is never created. Measured on device 2026-08-04: 8 warm
  deliveries, 8 hits on `scene(_:continue:)`, **zero** on the modifier. The 2026-07-16 matrix measured
  it in the opposite configuration; those rows are mutually exclusive setups, not composable features.
- **With a custom scene delegate installed, the scene delegate's continuation does not fire on iOS
  18.7.9 while the app is already RUNNING** — measured on an iPhone XS, builds 681/683:
  `scene(_:willContinueUserActivityWithType:)` announces a continuation and then neither
  `scene(_:continue:)` nor `scene(_:didFailToContinueUserActivityWithType:error:)` follows, from Notes,
  WhatsApp and Safari's smart banner alike; a Camera QR scan and any cold launch deliver normally. The
  cause is ours: a scene has exactly ONE delegate, ours displaces SwiftUI's, and SwiftUI's machinery
  feeds `.onOpenURL` — so restoring that modifier delivered the link on the same OS build (687). A
  previous revision of this bullet said "iOS 18.7.9 does NOT call `scene(_:continue:)`", a claim about
  the PLATFORM; it was disproved within hours on that same OS build. Scope such claims to the build and
  configuration measured. The same signature is reported independently in Apple Developer Forums 758864
  and 746362.
- **`.onOpenURL` is not reliable alone either** — it fired for 2 of 4 deliveries on an SE2 (iOS 26.6,
  build 687). Both paths are therefore pinned, and delivery is made idempotent in tested code
  (capability `event-link`) rather than by choosing between them.
- A **simulator cannot substitute** for any of this: the associated-domains entitlement makes the app
  un-launchable there, so no link entry point fires at all (measured 2026-08-25), and `simctl openurl`
  is accepted while delivering nothing.
- Expiry: re-measure at the next iOS major, and whenever a delivery hook is added or removed. Evidence
  is one device per OS major.

#### Scenario: Removing the scene delegate fails the build

- **WHEN** the Swift shell no longer installs a scene delegate, or no longer implements
  `scene(_:willConnectTo:options:)` or `scene(_:continue:)`, or no longer forwards the activity to
  `onUserActivity`
- **THEN** the guard test fails, naming what is missing and why it matters

#### Scenario: A cold connection with no activity is still recorded

- **WHEN** the cold half no longer forwards the delivered-activity count before iterating, so its only
  Kotlin call sits inside the loop and an empty `userActivities` records nothing
- **THEN** the guard test fails. The forwarding rule below cannot catch this on its own — the call is
  lexically present and merely never runs — and the consequence is measured: on `SNAPSYNC-25` a
  delegate that was installed and handed nothing was indistinguishable from a delegate that was never
  installed, and that ambiguity was the whole investigation

#### Scenario: The guard is not vacuous

- **WHEN** the Swift file the guard inspects is absent, renamed, or no longer contains the markers it
  expects
- **THEN** the guard fails, rather than passing while inspecting nothing

#### Scenario: The guard re-runs when the Swift shell changes

- **WHEN** only the iOS Swift shell is edited and the guards are run
- **THEN** the guard task re-runs rather than reporting up-to-date

#### Scenario: An intact shell passes

- **WHEN** the shell installs the scene delegate and implements both halves, forwarding the
  delivered activity whole to `onUserActivity`
- **THEN** the guard passes

#### Scenario: Removing the SwiftUI delivery path fails the build

- **WHEN** the Swift shell no longer declares `.onOpenURL` on the `WindowGroup`, or no longer forwards
  its URL to Kotlin
- **THEN** the guard test fails, naming what is missing and carrying the evidence that this path is the
  one that delivers a link opened while the app is already running on iOS 18.7.9 — because the deletion
  this guards against is a reader trusting the older, falsified claim that the modifier never fires
