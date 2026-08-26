## Context

The scene-activation gate (capability `ios-app-shell`, mitigation for CMP-5978) has three moving parts:

1. `resolveScene(visibility, everActive)` — pure, tested, answers **Deferred** or **Live**.
2. `everActive` — the shell's record that this process has been active, written by `onForeground()` (the
   Kotlin `NSNotificationCenter` observer of `UIApplicationDidBecomeActiveNotification`, installed in
   `onLaunch`) **and** by `onSceneActive()`.
3. `generation` — a SwiftUI `@State` in `ContentView`, bound to `.id(…)`, assigned from
   `SnapSyncRoot.onSceneActive()` inside `.onReceive(UIApplication.didBecomeActiveNotification)`.

Parts 2 and 3 observe **the same notification through two different subscriptions**, and only one of them
exists before SwiftUI first evaluates `ContentView.body`. The current spec acknowledges this and declines
to constrain it: *"the app-level foreground entry and the scene composition ride the same notification, so
their relative order is not contracted."* That uncontracted ordering is the defect.

Both orderings occur in production. On the reporting device (iPhone XR, iOS 18.7.9) 3 of 21 activated
processes took the notification-first order; on the SE2 (iOS 26.6) 18 of 18 forced launches took the
body-first order, so the distribution is device- and OS-dependent and cannot be relied on either way.

- **body first** → `sceneMode()` sees BACKGROUND + `everActive = false` → `Deferred`; the notification then
  arrives with `.onReceive` subscribed; `generation` 0 → 1; the placeholder is swapped for the live scene.
  Correct, and `generation` is settled at 1 for the rest of the process.
- **notification first** → Kotlin's observer sets `everActive = true`; the body then runs with the app
  ACTIVE → `Live` immediately. Swift's `.onReceive` was not yet subscribed and **missed that
  notification**, so `generation` stays `0` while a live scene is already installed. The process is
  *armed*: the next `didBecomeActive` flips `generation` 0 → 1 and rebuilds the representable.

The rebuild is where the damage happens, because `liveScene` is `by lazy` — one `ComposeUIViewController`
per process. `makeUIViewController` therefore returns a controller that is already a child of the
outgoing representable's host. Measured consequence: the Compose view leaves the hierarchy and does not
come back, because `onSceneActive()` returns the constant `1` from then on, so `.id` never changes again
and `makeUIViewController` is never called again. Only a process restart recovers — which is exactly what
SNAPSYNC-15's reporter did.

A second armed path needs no launch race at all and is worth recording: if iOS disconnects and reconnects
the scene session in a process that has already been active (memory pressure does this routinely — one
dump shows 51 MB free), SwiftUI rebuilds `ContentView` with `@State generation = 0` while `everActive` is
already `true`, so the new host's *first* `make` hands back the parented `liveScene` immediately. Both
decisions below cover this path too.

Constraints this design must respect:

- `SwiftShellGuardTest` pins `iosApp/iosApp/ContentView.swift` at **zero** `if`/`guard`/`switch`/`??`,
  exact in both directions — the fix may add no Swift conditional.
- `KotlinShellGuardTest` holds `:app:*` Kotlin at zero unpinned decisions — the root may record facts and
  read a resolver's answer, but may not branch.
- `:app:ios` is wiring-only and **untested by rule**, so every decision must live in a pure `model/`
  function covered by `commonTest` on JVM and the simulator.
- The CMP-5978 deferral itself is not in question and keeps its expiry trigger.

## Goals / Non-Goals

**Goals:**

- Make the launch ordering **irrelevant** rather than contracted, so neither observer's timing can leave a
  process armed.
- Honour Apple's `UIViewControllerRepresentable` contract, so that *any* rebuild — including one this
  design did not anticipate — degrades to a fresh scene rather than a blank one.
- Keep the two guarantees the gate already provides: a background-woken process composes nothing, and an
  ordinary app switch does not discard screen-local Compose state.
- Make the armed state, and the three different whites, readable from a diagnostic dump without inference.

**Non-Goals:**

- Removing or weakening the CMP-5978 deferral. Untouched; same expiry trigger.
- Rebuilding the scene after a *long* background (the spec already names this as a separate, unshipped
  option).
- Changing any Swift file. The fix is entirely Kotlin.
- Explaining SNAPSYNC-19's "white screen for a few seconds". That report is a **different** failure — an
  armed cold launch reported 34 s in, before its rebuild could fire — and is being investigated
  separately (workspace `white-screen-cold-launch`). Nothing here claims to fix it.
- Fixing the main-thread `by lazy` contention between the first render and the composition lane over the
  `host`/`app` graph. Real, measured at ~90–130 ms, named in `ConstructorBlockingTest`'s own KDoc as
  out of its scope — but it is not this defect and is not addressed here.

## Decisions

### D1 — The generation counts what was handed out, not what fired first

`onSceneActive()` stops returning a constant. `sceneMode()` advances a counter as it returns each answer
(one assignment, no branch — `MainViewController()` is its only caller, so the count is complete by
construction), and `onSceneActive()` returns that counter. The advance is a pure resolver in `model/`:

```
sceneGenerationAfter(previous: Int, handedOut: SceneMode): Int =
    Deferred -> 1          // a placeholder is being installed and MUST be retired
    Live     -> previous   // carry forward: no rebuild now, and none later
```

Total over the sealed type, so the compiler fails closed if a third `SceneMode` is ever added — the same
posture `sceneFor` already takes.

**It must be monotonic, and this is the part that is easy to get wrong.** A first revision answered from
the mode most recently handed out (`Deferred → 1`, `Live → 0`, `null → 0`). Every ordering below still
reads correctly at first activation — but once the placeholder is replaced the record says `Live`, the
answer falls `1 → 0`, and `.id(…)` rebuilds on the FALL exactly as it would on a rise. Measured on a
simulator (iOS 26, 2026-08-26): three `MainViewController` calls in one process, the third on the first
warm foreground, discarding screen-local Compose state. Threading `previous` through is what makes "once
retired, stays retired" expressible without the shell holding a branch.

Traced against all four orderings, with the value after each step:

| ordering | first `make` | signal | later activations | rebuild? | outcome |
|---|---|---|---|---|---|
| body first (healthy) | `Deferred` → 1 | 1 | 1, 1, … | one, then none | placeholder → live. Correct. |
| notification first (armed today) | `Live` → 0 | 0 | 0, 0, … | **none** | live scene stays installed. Correct. |
| background-connected | `Deferred` → 1 | 1 | 1 at first activation | one, then none | placeholder → live. Correct. |
| scene reconnect after active | `Live` → carries | unchanged | unchanged | **none** | correct — and no parented reuse. |

*Alternative considered — have Swift seed `generation` from Kotlin when the body first runs* (e.g.
`@State` initialised from a `currentSceneGeneration()`, or `.task`). Rejected: `@State` initialisers run
at a moment SwiftUI chooses, `.task`/`onAppear` fire in the background-connected case too and would
defeat the deferral, and every variant puts more timing-sensitive logic in the file the transcriber law
most wants inert.

*Alternative considered — key `.id` on the mode rather than a generation.* Rejected: it re-encodes the
same decision in Swift and would rebuild on any mode change, which is precisely what must not happen.

### D2 — `makeUIViewController` creates; the memoization goes

`liveScene` becomes a per-call `composeScene()`. Apple: *"You must implement this method and use it to
**create** your view controller object… The system calls this method only once, when it creates your view
controller for the first time"* — once **per view identity**, and `.id(generation)` exists to mint a new
identity. Returning the parented instance makes one object simultaneously the thing the new identity
adopts and the thing the old identity's teardown removes.

The memoization's stated defence was that re-composing on each foreground would discard screen-local
Compose state. Under D1 that defence no longer applies: the only rebuild left is the placeholder → live
swap, and the placeholder composes nothing, so no screen-local state exists at that instant. D1 and D2 are
therefore not redundant — D1 removes the *spurious* rebuild, D2 removes the *consequence* of any rebuild —
and keeping only one leaves either a latent contract violation or a day-later blank screen.

**Measured, not reasoned** (SE2, iOS 26.6, 2026-08-25): two builds differing only in this line, both forced
armed, both logging the identical two-`make` rebuild — memoized rendered **white**, per-call rendered the
full screen.

*Alternative considered — memoize but detach from the previous parent first* (`willMove(nil)` /
`removeFromSuperview` / `removeFromParent`). Rejected: it writes UIKit containment into the untested
`:app:ios` shell and races SwiftUI's own bookkeeping, since the outgoing host still dismantles the
controller afterwards.

*Alternative considered — drop `.id` entirely and swap a child controller inside a stable container.*
Rejected for this change: it is the cleanest end state and removes the whole class, but it puts real
child-view-controller containment logic into a module that is wiring-only and untested by rule. Worth
revisiting if the deferral outlives CMP-5978.

### D3 — The placeholder gets a defined backdrop

`deferredScene()` sets `view.backgroundColor = UIColor.systemBackgroundColor`. One symbol, no conditional,
so `KotlinShellGuardTest` stays at zero decisions and UIKit resolves light/dark itself. This removes the
dark-mode white flash on every cold launch, and makes a placeholder distinguishable from a detached view
in dark mode.

It does **not** make them distinguishable in light mode, where `systemBackground` is white — that load is
carried by D4's log line instead, and the spec says so rather than implying a visual guarantee it does not
provide.

The KDoc sentence claiming the scene delegate colours the window is deleted: no code anywhere colours any
window, so the claim is false and is the reason the placeholder was left untinted.

*Alternative considered — bind the exact `AppTheme` colours (`#F4F6F8` / `#0C0E12`) via
`UIColor(dynamicProvider:)`.* Rejected: the provider closure must read `userInterfaceStyle`, which is a
decision in `:app:ios` and would need a `KotlinShellGuardTest` pin bump. Deferring to the platform colour
buys nearly all the benefit for none of that.

### D4 — The generation is logged

`onSceneActive`'s `Logger.invocation` gains `result = { "generation=$it" }`. The mechanism already exists
and is already used elsewhere (`← photoPermission.onDidBecomeActive = GRANTED`); the `result` renderer
simply defaults to `{ "" }` and this call site never opted in. That omission is why every dump shows
`← onSceneActive (2 ms)` and the armed state had to be inferred from the *absence* of a `deferred` line
rather than read directly.

## Risks / Trade-offs

- **The measurement is iOS 26.6, every report is iOS 18.7.9.** → The causal experiment was run on the only
  device available. It shows the mechanism is not iOS-18-specific, which is the direction that matters:
  a fix justified on iOS 26 behaviour is not weakened by iOS 18 behaving the same or worse. The log
  correlation (3 armed → 2 whites; 36 healthy → 0) is what speaks for iOS 18, and D4 makes the next dump
  from that device decisive either way.
- **Dropping the memoization means a rebuild now builds a second Compose runtime and Metal renderer.** →
  Under D1 the only remaining rebuild is placeholder → live, where the outgoing controller composes
  nothing, so there is no second runtime in practice. If an unforeseen rebuild does occur, a brief second
  scene is strictly better than a permanently blank one.
- **D1 relies on `MainViewController()` being the only caller of `sceneMode()`.** → True today and
  enforced by `sceneMode()` being `internal` to `:app:ios`. A second caller would silently corrupt the
  record. Worth a guard, but the risk is small and visible in one file.
- **`systemBackgroundColor` is not the app's background colour.** → Close enough to remove the flash
  (white/black vs `#F4F6F8`/`#0C0E12`), and the alternative costs a shell-guard pin bump. If the seam
  becomes visible in practice, revisit with the dynamic provider.
- **The armed launch ordering cannot be reproduced on the SE2 without instrumentation** (18/18 healthy
  via `dvt launch`). → Verification after the fix is therefore by *absence* — the armed signature
  (`live` first, no `deferred`) should stop appearing in dumps, and D4's generation line makes that
  readable at a glance rather than inferred.

## Open Questions

- Should a `:test:architecture` guard pin that `sceneMode()` has exactly one caller, so D1's completeness
  is mechanical rather than reviewed? Cheap, and the laws favour mechanical.
- Is the `.id`-free container design (rejected above) the right end state once CMP-5978 is fixed and the
  deferral is deleted — or does the deferral's removal make the whole generation mechanism disappear with
  it? The expiry trigger should probably name this.
