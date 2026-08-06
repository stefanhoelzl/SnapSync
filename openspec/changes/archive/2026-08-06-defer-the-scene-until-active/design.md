## Context

`iosApp` uses the SwiftUI App lifecycle: `WindowGroup { ContentView() }`, where `ContentView` is a single
`UIViewControllerRepresentable` wrapping `MainViewControllerKt.MainViewController()`. SwiftUI instantiates
that content when the **scene connects**, and iOS connects scenes in `UISceneActivationState.background` —
so a process launched or woken by a silent push or a `BGTask` builds a full Compose runtime and Metal
renderer that nothing can draw to.

Three measurements, none of them from reading the current code:

1. **The scene composes while invisible.** On the SE2 (iOS 26.5.2), a `dvt launch` against a locked device
   produced a process holding a background assertion (`UIKitCore: "Creating new assertion because there is
   no existing background assertion"`), FrontBoardServices realizing `_UISceneRenderingEnvironmentSettings`,
   and `applyLaunchEnvSeed` — sole caller a `LaunchedEffect` inside `MainViewController` — at **+345 ms,
   before `onForeground`**. `SNAPSYNC-8` shows the same at `12:11:40`, 3 h 16 m before its first foreground.
2. **Nothing else needs the scene.** `renderHost` has exactly one caller (`MainViewController`); the live
   stack is an independent `by lazy` `AppCore` built by `snapSyncApp`.
3. **The competing explanations are dead.** `enableAppHangTracking` defaults to `true` at 2 000 ms in
   `sentry-kmp 0.27.0` and has reported zero events; `SNAPSYNC-8`'s resume completed in 1.1 s with
   `onForeground` returning in 3 ms, and the report was written 43 s later. The two production watchdog
   terminations carry no memory figures, and jetsam kills processes rather than corrupting their output.

Apple's contract is the forcing fact: an app **must not submit GPU work while backgrounded**
(`kIOGPUCommandBufferCallbackErrorBackgroundExecutionNotPermitted`), and a backgrounded app's GPU resources
are reclaimed. Every Metal-backed app is expected to stop its display link and free GPU resources on
background and rebuild them on foreground. Compose Multiplatform 1.11.1 evidently does not
([CMP-5978](https://youtrack.jetbrains.com/issue/CMP-5978), open since 2024-08, unreproduced upstream).

## Goals / Non-Goals

**Goals:**

- No Compose scene, runtime or renderer exists in a process that is not active.
- The scene the user eventually sees was built while the app was visible.
- The change verifies itself from an already-merged log line, with no new instrumentation.
- The decision is tested Kotlin; Swift stays a transcriber.

**Non-Goals:**

- Rebuilding a scene that was built in the **foreground** and then backgrounded for hours (option C). No
  report shows that shape; shipping it would be speculation on speculation.
- Diagnosing *which* cache breaks. That needed a framebuffer capture, which is out of scope by decision.
- Fixing the renderer. This is a mitigation; the defect is upstream.
- Any change to features, flows, ports, adapters, the upload/download machinery, or the extension.

## Decisions

### D1 — Defer creation rather than recover from it

Four candidates were considered:

| | behaviour | verdict |
|---|---|---|
| A | force a redraw on foreground | rejected — redrawing from the same dead caches redraws wrong |
| B | rebuild the scene on every background→foreground | rejected — discards screen-local state (open reconfigure surface, half-typed bug report, scroll position) every time |
| C | rebuild after a long background | deferred — covers a shape neither report shows |
| **E** | **never create the scene until active** | **chosen** |

E removes the precondition instead of recovering from it: with no renderer in the background there is
nothing to poison, nothing for iOS to reclaim, and no possibility of background GPU submission. It is also
strictly cheaper than B/C — no wasted composition, no state loss, and less memory in background processes —
and its UX cost is zero by construction, because a scene composed during a background wake has never been
seen or touched.

### D2 — `.id(sceneGeneration)` through the existing SwiftUI layer

Two mechanisms produce E:

- **The scene delegate owns the `UIWindow`** and installs the Compose view controller on
  `sceneDidBecomeActive`, dropping the vestigial SwiftUI App entry point. Idiomatic UIKit, and chosen
  first — see below for why it was reverted.
- **`.id(sceneGeneration)` churn** — Kotlin answers a generation (0 before any activation, 1 after) and
  SwiftUI binds it to `.id(…)`, rebuilding the representable exactly once. **Chosen.**

**This decision was reversed by a device measurement (2026-08-06), and the reversal is the point.**

The scene-delegate version was built, signed and installed. It worked when a user taps the app icon —
the log shows the exact intended sequence, `MainViewController(mode=deferred)` at scene connect and
`MainViewController(mode=live)` 259 ms later at activation. But a headless
`pymobiledevice3 developer dvt launch` produced a **black screen**: `onForeground` fired, so the app
*was* active, yet no scene session was ever connected and neither scene callback ran. SwiftUI's
`WindowGroup` had been creating that scene eagerly at launch; UIKit waits for the system to connect one,
and DVT's ProcessControl launch does not ask for it.

So the SwiftUI layer was not vestigial after all. It was silently providing the eager scene that the
project's on-device workflow depends on — `dvt launch` + `dvt screenshot` is the only way an agent can
see this app at all, and losing it would have made every future on-device check require a human tap.

`.id(sceneGeneration)` keeps that, and gets the same behaviour, because it keys on the **app-level**
`didBecomeActive` notification — which fires however the app is opened, DVT included — rather than on a
scene-level callback. The deferral is unaffected: a background-woken process never becomes active, so
the generation never changes, so no Compose scene is ever built.

The cost is honest: `.id()` identity churn is a slightly indirect way to obtain a lifecycle effect, and
the idiomatic UIKit restructure remains available as its own change, where a launch regression would not
be riding on a bug fix.

### D3 — The decision is a pure sealed resolver, dispatched once

`:app:*` Kotlin carries zero unpinned conditionals (detekt-gated) and Swift carries zero decisions
(`SwiftShellGuardTest`). The established pattern for a shell that must nonetheless branch is
`resolveComposition`: a pure, `commonTest`-covered sealed resolver in `model/`, with a single `when` in the
shell. This change follows it — a sealed scene mode resolved from the activation state, one `when` in
`MainViewController`, and an assignment (not a branch) in Swift.

### D4 — No new instrumentation

`b0639002` already wraps the entry point in `platformEntry("MainViewController")`, logging every
construction with enter/exit. That is the oracle: today the line appears before `onForeground` on a
background launch; after this change it must not appear until activation. Adding logging for a change that
already logs itself would be noise.

### D5 — This is a mitigation, and it names its expiry trigger

Freeing and rebuilding GPU resources across backgrounding is the renderer's responsibility under Apple's
contract, not the app's. This change exists because CMP 1.11.1 does not do it. **Expiry trigger: CMP-5978
(or its successor) fixed in a Compose Multiplatform release this project adopts** — at which point this
change should be re-evaluated and, if the renderer honours the contract, deleted. The mitigation must not be
described anywhere as the correct architecture, or the next person to read it will not know it can go.

Reporting upstream is a separate, non-blocking action: this investigation produced two devices, two OS
majors, two upload tiers, and on-device proof of background composition — materially more than the open
ticket has carried.

## Risks / Trade-offs

- **SwiftUI removal touches the app's entry point** → the riskiest step, and it MATERIALIZED on the first
  device build (2026-08-06): with the SwiftUI App gone, `Info.plist`'s
  `UIApplicationSceneManifest` — `{UIApplicationSupportsMultipleScenes: false}`, no configurations — left
  a UIKit app with **no scene at all**. `configurationForConnecting` was never consulted, neither scene
  callback fired, no window was created; the app launched, ran its entire stack healthily (foreground
  entry, reconcile, HTTP, push registration all logged) and showed a **black screen**. Fixed by declaring
  `UISceneConfigurations` naming `SnapSyncSceneDelegate`, and pinned by a new assertion in
  `EventLinkDeliveryTest` so it cannot silently regress — the same key carries both the UI and every cold
  event link. Universal-link delivery is the
  thing most likely to regress, and it is the one seam the project cannot test (`ios-app-shell` records that
  `.onOpenURL` and `.onContinueUserActivity` were both tried on device and neither works). Mitigate by
  keeping `SnapSyncSceneDelegate`'s two callbacks byte-identical and re-verifying a cold and a warm link on
  device before merge, using the `debug.log` `[onOpenUrl]` oracle the spec already defines.
- **The hypothesis is unconfirmed** → we infer success from silence: reports stop, or they don't. The
  framebuffer capture that would have confirmed it directly is out of scope by decision. Recorded so the
  absence of proof is not later mistaken for proof.
- **The uncovered shape** → a foreground-built scene, backgrounded for hours, then resumed, is untouched by
  this change. If reports continue with that session shape — visible in the `MainViewController` and
  foreground log lines — option C is the next step.
- **App-switcher snapshot** → iOS requests a snapshot when backgrounding. It only applies after a real
  foreground, where a scene exists, but it should be confirmed not to force a scene build in a
  background-launched process.
- **Dev launch triggers** → `applyLaunchEnvMembership` / `applyLaunchEnvSeed` run from `LaunchedEffect`s
  inside `MainViewController` and therefore move with the scene. Developer launches are foreground, so this
  should be inert, but `applyLaunchEnvMembership` has a second caller and both paths need checking against
  the `ios-app-shell` trigger requirements.
- **Doing nothing is also a risk** → the current behaviour keeps a Metal renderer alive in every
  background-woken process for hours, which is both the suspected fault and unnecessary work.

## Open Questions

- Does dropping `@main struct iOSApp: App` for a UIKit lifecycle disturb anything else the SwiftUI layer was
  quietly providing (safe-area propagation into Compose, keyboard avoidance, orientation)? `ContentView`
  applies `.ignoresSafeArea(.all)` today and `ScreenLayout` handles insets via `WindowInsets.safeDrawing`,
  so the expectation is no — to be confirmed on device.
- Should the resolver key on `sceneDidBecomeActive` only, or also admit `foregroundInactive` (the state
  during the app-switcher and incoming-call banners)? Building on `foregroundInactive` would be earlier and
  still visible-ish; building only on active is the stricter reading.
