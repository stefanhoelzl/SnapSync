## ADDED Requirements

### Requirement: The Swift shell keeps the event link's delivery seam

A test-only JVM guard SHALL assert that the iOS Swift shell still installs a **scene delegate** that
handles **both** halves of universal-link delivery and forwards to `SnapSyncRoot.onOpenUrl(_:)`
(capability `ios-app-shell`). Specifically it SHALL assert that the shell:

1. installs a scene delegate via the app delegate's `application(_:configurationForConnecting:options:)`
   (setting `delegateClass`) — without this the delegate is inert;
2. implements `scene(_:willConnectTo:options:)` — the **cold** half, reading the launching link from the
   connection options;
3. implements `scene(_:continue:)` — the **warm** half; and
4. forwards to `SnapSyncRoot.onOpenUrl` from that delegate.

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

Because the failure is invisible, the guard's **failure message** SHALL carry the evidence — that
`.onOpenURL` and `.onContinueUserActivity` and `application(_:continue:restorationHandler:)` were each
tried on device and are each insufficient — since that message is the only thing standing between the
next reader and re-introducing the bug.

#### Scenario: Removing the scene delegate fails the build

- **WHEN** the Swift shell no longer installs a scene delegate, or no longer implements
  `scene(_:willConnectTo:options:)` or `scene(_:continue:)`, or no longer forwards to `onOpenUrl`
- **THEN** the guard test fails, naming what is missing and why it matters

#### Scenario: The guard is not vacuous

- **WHEN** the Swift file the guard inspects is absent, renamed, or no longer contains the markers it
  expects
- **THEN** the guard fails, rather than passing while inspecting nothing

#### Scenario: The guard re-runs when the Swift shell changes

- **WHEN** only the iOS Swift shell is edited and the guards are run
- **THEN** the guard task re-runs rather than reporting up-to-date

#### Scenario: An intact shell passes

- **WHEN** the shell installs the scene delegate and implements both halves, forwarding to `onOpenUrl`
- **THEN** the guard passes
