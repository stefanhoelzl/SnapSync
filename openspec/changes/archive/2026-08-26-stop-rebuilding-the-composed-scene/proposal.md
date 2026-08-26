## Why

Three operator bug reports — Bugsink **SNAPSYNC-15** (2026-08-14, build 605), **SNAPSYNC-19**
(2026-08-17, build 607) and **SNAPSYNC-24** (2026-08-21, build 607) — all say the same thing: after
foregrounding, the app showed a white screen. SNAPSYNC-15 adds the decisive detail — *"restarting the
app fixed it"*.

The scene-activation gate added for CMP-5978 (capability `ios-app-shell`) rebuilds the SwiftUI
representable exactly once per process, when Kotlin's **scene generation** flips `0 → 1`. That flip is
supposed to coincide with the moment a deferred placeholder must become the live scene. It does not
always: `generation` is answered by Swift's `.onReceive(didBecomeActive)`, while the scene *mode* is
answered by `resolveScene`, whose `everActive` input is written by **Kotlin's own observer of the same
notification** (`onLaunch` → `onForeground`). When the notification wins the race against the first
evaluation of `ContentView.body`, the very first `MainViewController()` call already resolves `Live` —
and Swift's subscription, not yet installed, misses that notification. The process is then left with a
live scene installed at `generation = 0`: a rebuild **armed but not yet fired**. The next ordinary
foreground — possibly a day later — fires it.

The rebuild is fatal because `liveScene` is memoized `by lazy`, one instance per process. Apple
specifies `makeUIViewController` as *"Creates the view controller object … The system calls this method
only once, when it creates your view controller for the first time"* — **per view identity**, and
`.id(generation)` deliberately mints a new identity. Handing the new host the controller the outgoing
host still owns means the outgoing host's teardown (*"remove your view controller cleanly"*) detaches
the controller the new host just adopted. The Compose view leaves the hierarchy and never returns,
because `onSceneActive()` returns the constant `1` thereafter — no further `.id` change, no further
`makeUIViewController`, no repair. Only a process restart recovers it.

Evidence, strongest last:

- **Log correlation.** Across the three dumps' `debug.log` tails, 35 distinct app processes: 18 healthy
  (`deferred` → `live`, and never another `MainViewController` line however many activations followed),
  14 background-only (`deferred`, never activated), and **3 armed** (`live` first, no `deferred`, no
  `onSceneActive`). Two of the three armed processes later emitted a second `MainViewController(mode=live)`
  on an ordinary warm foreground — and those two are exactly SNAPSYNC-15 and SNAPSYNC-24. The third,
  SNAPSYNC-19, was reported 34 s into an armed cold launch, before its rebuild could fire. A further 18
  processes forced on the SE2 were healthy, for **36 healthy processes with zero second-`make` calls**.
- **Apple's contract**, quoted above, which the memoization violates by construction.
- **A controlled on-device experiment** (SE2, iPhone12,8, iOS 26.6, 2026-08-25). Two builds differing in
  **one line**, both forced into the armed state, both logging the identical two-`make` rebuild:

  | probe | `liveScene` | screen |
  |---|---|---|
  | A | `by lazy { composeScene() }` (production) | **white** |
  | B | `get() = composeScene()` (fresh per call) | renders fully |

  The rebuild occurs in both. Only the memoization decides whether it blanks the screen. This also shows
  the defect is not iOS-18-specific, though every report came from iOS 18.7.9.

Two supporting defects surfaced while reading this and are folded in, because each is a reason the bug
stayed invisible:

- **`onSceneActive` never logs its return value.** `Logger.invocation`'s `result` renderer defaults to
  `{ "" }` and this call site does not opt in, so every `← onSceneActive (2 ms)` in every dump omits the
  one number that would have identified an armed process directly, instead of by inferring it from the
  *absence* of a `deferred` line.
- **The deferred placeholder is white, and so is everything else.** `deferredScene()` returns an untinted
  `UIViewController`; `UILaunchScreen` has no configured content; a detached Compose view shows the
  window backdrop. All three are the same white. `deferredScene()`'s KDoc asserts *"the backdrop belongs
  to the window, which the scene delegate colours once"* — **no code colours any window**:
  `SnapSyncSceneDelegate` implements only `willConnectTo` and `continue`, and no `UIWindow`/
  `backgroundColor` exists anywhere under `iosApp/` or `app/ios/src`. In dark mode this is also a plain
  product defect: launch screen and placeholder are white while the app is `#0C0E12`.

## What Changes

- **The scene generation is derived from what was actually handed out, not from which observer fired
  first.** `MainViewController()` is the sole caller of `sceneMode()`, so the root records the resolved
  mode as it answers, and `onSceneActive()` returns a generation computed from it by a new pure, total,
  `commonTest`-covered resolver over the sealed `SceneMode` (plus "nothing handed out yet"):
  nothing yet → `0`; `Deferred` → `1`; `Live` → `0`. A placeholder is installed ⇒ rebuild it; a live scene
  is already installed ⇒ never rebuild. Both launch orderings now produce a correct result, so the
  ordering stops needing to be contracted. Swift is untouched and stays at zero decisions.
- **`makeUIViewController` returns a freshly created controller, per Apple's contract.** The `by lazy`
  memoization on `liveScene` is removed. Its stated defence — that re-composing would discard
  screen-local Compose state on every foreground — dissolves under the first change: the only rebuild
  left is the placeholder → live swap, and the placeholder composes nothing, so there is no screen-local
  state in existence at that moment. Removing it also makes any *future* rebuild degrade to a fresh
  scene instead of a blank one.
- **The deferred placeholder gets `UIColor.systemBackgroundColor`** — one symbol, no conditional, UIKit
  doing the light/dark resolution — so a placeholder is no longer indistinguishable from a detached view,
  and the dark-mode white flash on cold launch goes away. The false KDoc claim about the scene delegate
  colouring the window is deleted.
- **`onSceneActive` logs its generation** via `Logger.invocation`'s existing `result` parameter, so an
  armed process is directly readable in any future dump.

Not breaking: no API, storage, or wire-format change. The CMP-5978 deferral itself is untouched and keeps
its existing expiry trigger.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `architecture-guards`: a new gate pins the single-caller/single-writer property the scene-generation
  rule rests on — the module `test/architecture` gained `SceneRecordCompletenessTest`, and this spec
  carries one requirement per guard.
- `ios-app-shell`: the scene-generation rule becomes a contract (derived from the mode handed out, not
  from notification ordering); the scenario "A later activation does not rebuild the scene" is
  strengthened to say what "rebuild" means and to forbid reusing an installed controller across one; the
  sentence declining to contract the foreground-entry/scene-composition ordering is replaced by the
  statement that makes the ordering irrelevant; the placeholder gains a defined backdrop; the resolved
  **generation** joins the resolved **mode** as a required device-log line.

## Impact

- `app/ios/src/iosMain/kotlin/app/snapsync/ios/SnapSyncRoot.kt` — record the handed-out mode in
  `sceneMode()`; `onSceneActive()` returns the resolved generation and logs it.
- `app/ios/src/iosMain/kotlin/app/snapsync/ios/MainViewController.kt` — drop the `liveScene`
  memoization; tint `deferredScene()`; correct its KDoc.
- `domain/src/commonMain/kotlin/app/snapsync/model/SceneMode.kt` — the new pure generation resolver
  beside `resolveScene`.
- `domain/src/commonTest/kotlin/app/snapsync/model/SceneModeTest.kt` — cover the resolver totally,
  including the "nothing handed out yet" case.
- `openspec/specs/ios-app-shell/spec.md` — the requirement and scenario changes above.
- `iosApp/` Swift is **unchanged**; `SwiftShellGuardTest`'s pin of `ContentView.swift` at zero
  `if`/`guard`/`switch`/`??` stays as-is.
- No change to the upload tiers, the ledger, the composition lanes, or any port.
