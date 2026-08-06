## Why

Two TestFlight members have reported a broken status screen on build `0.2(542)`, on **different devices,
different OS majors and different upload tiers**:

- `SNAPSYNC-6` — iPhone XS, iOS 18.7.9, url_session tier: *"just a white background, the sync status
  checkmark and a border for the qr code"* — every `Text` and the QR bitmap failed to paint.
- `SNAPSYNC-8` — iPhone 14, iOS 26.5.2, photokit tier: *"qr code und Icon werden als farbige Quadrate
  dargestellt"* — the QR and a vector icon painted as coloured squares.

In both, plain geometry (the card fill, the shape, the background) drew correctly. What failed is exactly
the content that reaches the screen through a **cached GPU texture**: the Skia glyph atlas, qrose's
`DrawCache` `ImageBitmap`, and `VectorPainter`'s cached layer.

Investigation eliminated the obvious causes with evidence rather than assumption:

- **Not memory.** The two production watchdog terminations carry no memory figures at all, and the causal
  shape is wrong — jetsam *kills* a process, while these processes survived and drew wrong.
- **Not a main-thread stall.** `enableAppHangTracking` is **on by default** (2 000 ms) in
  `sentry-kmp 0.27.0`; it has reported zero events. `SNAPSYNC-8`'s entire resume took **1.1 s**
  (`onForeground` returned in 3 ms) and the report was written **43 s later**, on a settled, idle UI.

What both sessions share is a **session shape**:

```
process launched/woken in the BACKGROUND (silent push / BGTask)
     │  Compose composes a scene that nobody can see
     │  hours of dormancy — iOS reclaims the app's GPU resources, by contract
     ▼
first foreground ──▶ that same scene is presented ──▶ wrong pixels
```

The middle step is confirmed on device, not inferred: on a process holding a *background assertion*,
`applyLaunchEnvSeed` — whose only caller is a `LaunchedEffect` inside `MainViewController` — fires **345 ms
after process start, before `onForeground`**. `SNAPSYNC-8` shows the same at `12:11:40`, three hours before
its first foreground.

**And nothing needs that scene.** `renderHost`, the Orbit container the UI renders from, has exactly one
caller: `MainViewController`. Every background trigger runs off `SnapSyncRoot.app`, an independent `by lazy`
`AppCore`. The scene is built in the background purely because SwiftUI instantiates `ContentView`
unconditionally when the scene connects — and iOS connects scenes in `UISceneActivationState.background`.

So the app stands up a Compose runtime and a Metal renderer in a process that cannot draw, keeps it alive for
hours across the exact window in which iOS reclaims GPU resources, and then presents it.

## What Changes

- **The Compose scene is created only while the app is active.** A process that launches or wakes in the
  background builds no `ComposeUIViewController`, no Compose runtime and no renderer; the first activation
  builds it.
- The decision stays out of Swift. `:app:ios` resolves a **pure, tested sealed mode** and dispatches on it
  with one `when` — the pattern `resolveComposition` already establishes for `ForgeShell`/`LiveShell` — so
  the transcriber law (`SwiftShellGuardTest` pins Swift decisions at zero) holds.
- No new logging. The change is **self-verifying** with a line already merged: `b0639002` wrapped the entry
  point in `platformEntry("MainViewController")`, so scene construction is logged with enter/exit. Today it
  appears before `onForeground` on background launches; afterwards it must not appear until activation.

Explicitly **not** in this change, and not blocked by it:

- rebuilding the scene after a *long foreground-built background* (the shape neither report shows);
- memory correlates, hang-threshold tuning, dispatcher-hop guards, or any framebuffer capture;
- an upstream fix. This is a **mitigation for a Compose Multiplatform defect**
  ([CMP-5978](https://youtrack.jetbrains.com/issue/CMP-5978), open since 2024-08; see also
  [CMP-10192](https://youtrack.jetbrains.com/issue/CMP-10192), [CMP-9488](https://youtrack.jetbrains.com/issue/CMP-9488),
  [CMP-10033](https://youtrack.jetbrains.com/issue/CMP-10033)). Freeing and rebuilding GPU resources across
  backgrounding is the renderer's job, and this change should be **deletable** once CMP does it.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `ios-app-shell`: the shell's root-view-controller requirement gains a condition on **when** the root view
  controller is obtained — the pass-through stays trivial, but a scene is composed only while the app is
  active, and never in a background-launched or background-woken process.

## Impact

- `iosApp/iosApp/` — the SwiftUI/scene wiring that decides when the Compose view controller is installed.
- `app/ios/src/iosMain/.../MainViewController.kt` — dispatch on the sealed scene mode.
- `domain/.../model/` — the pure scene-mode resolver plus its `commonTest` coverage (JVM + simulator).
- `:test:architecture` — the Swift/Kotlin shell guards must still measure zero decisions.
- No change to `:domain` features, flows, ports, adapters, the upload/download machinery, or the extension
  (which has no UI).
- Verification is a device run, not a test: a background-woken process must log no `MainViewController`
  entry until its first `onForeground`.
