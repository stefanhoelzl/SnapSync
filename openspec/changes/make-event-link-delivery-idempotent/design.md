## Context

The event link is the only route into or between events: there is no in-app scanner and no
paste-a-link field. When it fails, the only way through is a force-quit — which is what the member
did on `SNAPSYNC-25` before reporting it.

**What was measured**, over seven builds on an iPhone XS (iOS 18.7.9) and an SE2 (iOS 26.6), with
per-hook entry-point names in `debug.log` (capability `diagnostic-logging`):

| build | OS | app running | source | result |
|---|---|---|---|---|
| 607 | 18.7.9 | yes | link (source unrecorded) | 0/3 delivered |
| 673 | 18.7.9 | yes | Camera QR | 2/2 delivered |
| 678 | 18.7.9 | yes | Camera QR | 5/5 delivered, **without** `willContinueUserActivityWithType` |
| 681/683 | 18.7.9 | yes | Notes · WhatsApp · Safari banner | 0/4 — `willContinue` fires, nothing follows |
| 683 | 18.7.9 | yes | link | app-delegate trio: **0 hits, all three** |
| 687 | 18.7.9 | yes | Notes · WhatsApp | delivered via `.onOpenURL` — **fixed** |
| 687 | 18.7.9 | no (cold) | Notes | **double**: `onLaunchActivity` + `onSwiftUiOpenUrl`, ~130 ms |
| 687 | 26.6 | yes | link | **double**: `onSwiftUiOpenUrl` + `onSceneContinueActivity`, 8 ms |
| 687 | 26.6 | no (cold) | Camera | **double**: `onLaunchActivity` + `onSwiftUiOpenUrl`, 105 ms |
| 690 (main) | 26.6 | both | Camera · Notes | 4 deliveries, 4 singles — **iOS 26 was never broken** |

Three wrong conclusions were drawn and discarded along the way, and they are recorded here because
each was reached from real evidence: (1) that `willContinueUserActivityWithType` was the fix — killed
by 678's 5/5 without it; (2) that the discriminator was warm-vs-cold — killed by Camera-warm working;
(3) that it was WhatsApp or an in-app browser — killed by Notes, which has neither. The surviving
discriminator is **whether the app is already running**.

**The cause is ours.** A scene has exactly one delegate. `application(_:configurationForConnecting:)`
installs ours, so SwiftUI's is never created — and SwiftUI's machinery is what feeds `.onOpenURL`.
Measured 2026-08-04: with our delegate installed, 8 warm deliveries, 8 hits on `scene(_:continue:)`,
zero on the SwiftUI modifier. On iOS 26 that costs nothing. On iOS 18 the SwiftUI path is the only
warm path and we had switched it off. Independently reported with the identical signature — SwiftUI +
custom scene delegate, `willContinue` fires, `continue` does not, cold fine, *"works in a barebones
project"* — in Apple Developer Forums 758864 and 746362.

## Goals / Non-Goals

**Goals:**

- An opened event link reaches `onOpenUrl` whether or not the app is already running, on iOS 18 and
  iOS 26 alike.
- Delivery is **exactly once per opened link**, enforced by tested code rather than assumed of the
  platform.
- The correction is version-agnostic: a future iOS that adds or removes a delivery hook cannot
  reintroduce double-provisioning.
- Every claim written into the contract of record is scoped to the configuration and build measured.

**Non-Goals:**

- Fixing UIKit. `scene(_:continue:)` still never fires on iOS 18.7.9 when the app is running; we route
  around it. An Apple Feedback report is worth filing separately and is not this change.
- A non-link route into the app (paste a link, in-app scanner). It would remove this whole failure
  class and is worth its own change; it is not needed to fix this defect.
- Changing the AASA, entitlement, payload codec, or backend — all unchanged and uninvolved.

## Decisions

### 1. Idempotent delivery, not a per-OS hook table

The gate ignores a repeat of the same link until it is consumed. Any number of platform hooks then
becomes safe, on any OS, without knowing which ones fire where.

*Alternatives considered.* **A pure OS-version resolver**, in the `resolveUploadMechanism` idiom, was
the initially preferred shape and is rejected on the evidence: iOS 18 needs the SwiftUI path **on** for
the running-app case, but it also fires on **cold**, where `onLaunchActivity` has already delivered —
so the resolver would need a cold-vs-warm input, which is state about what just happened, i.e.
deduplication wearing a different hat. **Dropping the scene delegate and relying on `.onOpenURL`
alone** is rejected because the modifier fired for only **2 of 4** deliveries on iOS 26.6: it is not
reliable as a sole path anywhere. **`NSUserActivityTypes` in `Info.plist`** was rejected without
spending a build — it is a static declaration, and both the working and failing cases were the same
activity type reaching the same delegate in one process, so it cannot explain a difference that is
source- or state-dependent.

The deeper reason to prefer idempotence: this investigation was wrong about the hook matrix three
times in one day. A design whose correctness depends on that matrix being right is a design that will
break again the next time it is wrong.

### 2. Both delivery paths stay live

`.onOpenURL` is restored and the scene delegate is kept. Neither is reliable alone — the scene
delegate misses the running-app case on iOS 18, and the modifier is intermittent on iOS 26 — but the
union has delivered in every measured configuration. With decision 1 in force, redundancy is free.

This deliberately inverts the previous rule, which read *"stacking redundant delivery hooks"* as the
hazard. It was the hazard while delivery was not idempotent. Once it is, redundancy is the
availability strategy, and the hazard it guarded against is gone.

### 3. The app-delegate trio is removed, with its negative recorded

Build 683 implemented all three app-delegate continuation callbacks and measured **zero hits**,
including in the state that had never been tested: a scene continuation UIKit announced and abandoned.
That confirms the 2026-07-16 "never called in a SwiftUI app" row for iOS 18 as well. Dead hooks in a
wiring-only shell are worse than absent ones — they read as live paths.

### 4. Claims are scoped to the measured configuration, never to the OS

Three claims merged into the record earlier in this investigation were falsified within hours, all by
the same error: generalising a measurement of *one build in one configuration* into a statement about
*the platform*. "iOS 18.7.9 does not call `scene(_:continue:)`" is false; "iOS 18.7.9 did not call it
for builds through 607, whose configuration starved SwiftUI's delegate" is true. Replacement claims
name the build, the configuration and the expiry trigger.

## Risks / Trade-offs

- **Idempotence needs a consumption boundary** → "until consumed" must be defined precisely: a repeat
  of the *same* link while a pending join for it is open is a no-op; a *different* link supersedes;
  and once the join is committed or cancelled the same link may legitimately arrive again. Tested in
  `commonTest` on both targets, including the `autoJoin=true` path, which is the only one that would
  double-**provision** today.
- **`.onOpenURL` is intermittent on iOS 26.6 (2 of 4)** → accepted, and the reason the scene delegate
  stays. Neither path is load-bearing alone; the union is. Any future change that removes one must
  re-measure the other on both OS majors.
- **The delivery-seam guard must cover the new path** → otherwise `.onOpenURL` reads as the cruft the
  guard's own failure message warns about, and can be deleted with CI green. `EventLinkDeliveryTest`
  pins it, and the failure message carries the evidence.
- **Evidence is one device per OS major, n=1 per configuration** → expiry: re-measure at the next iOS
  major, and whenever a delivery hook is added or removed. A simulator cannot substitute — the
  associated-domains entitlement makes the app un-launchable there, so no link entry point fires
  (measured 2026-08-25).
- **Double delivery is currently shipping to nobody** → `.onOpenURL` exists only on the probe branch,
  so there is no live regression to race. iOS 26 users are unaffected today; iOS 18 users cannot
  switch events without a force-quit.

## Migration Plan

Ships as one build. No data migration, no backend change, no AASA change (so no reinstall needed —
devices cache the AASA weekly with no invalidation, and it is untouched).

**Verify on device, because nothing else can** (the Swift shell is untestable by project rule): on an
iOS 18 device and an iOS 26 device, with the app running, open an invite from Notes and from a
messenger; then force-quit and repeat. Read `debug.log`: `onOpenUrl` must appear **exactly once** per
opened link in all four cases, and the join surface must render. A second `onOpenUrl` for the same URL
is the regression this change exists to prevent, and the per-path entry names make it visible.

**Rollback.** Reverting restores the iOS 18 defect — a resident app that silently ignores every
invite — while leaving iOS 26 healthy. There is no safer prior state.

## Open Questions

- **Where does the idempotence rule belong** — the presentation gate (`StatusContainerHost.onOpenUrl`,
  closest to the pending-join state it keys on) or `:domain`? The gate already owns "re-scanning the
  already-joined event is a no-op", which is the same species of rule, and that argues for keeping
  them together.
- **Is the SE2's `.onOpenURL` intermittency a dedupe inside SwiftUI?** Both firings on iOS 26.6
  carried the same payload, and later taps of the same link did not re-fire. If SwiftUI itself
  suppresses a repeated identical URL, that is a second, invisible layer of the behaviour this change
  makes explicit — worth knowing, not worth blocking on.
- **Should an Apple Feedback report be filed?** The characterisation is unusually clean: an announced
  continuation that is neither delivered nor failed, dependent on the app already running, with
  timestamps and three independent sources.
