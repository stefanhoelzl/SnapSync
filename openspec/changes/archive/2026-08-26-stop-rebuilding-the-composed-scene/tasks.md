## 1. The pure rule (`:domain` `model/`)

- [x] 1.1 Add `sceneGenerationFor(handedOut: SceneMode?): Int` to
      `domain/src/commonMain/kotlin/app/snapsync/model/SceneMode.kt`, beside `resolveScene`: `null` → 0,
      `SceneMode.Deferred` → 1, `SceneMode.Live` → 0. Write it as an exhaustive `when` over the sealed
      type so a third mode fails the compile, and document WHY each arm answers as it does (a placeholder
      must be replaced; a live scene must never be rebuilt; nothing handed out means the first resolution
      will be live anyway).
- [x] 1.2 Extend `domain/src/commonTest/kotlin/app/snapsync/model/SceneModeTest.kt` to cover all three
      inputs of `sceneGenerationFor`, including the `null` case, and add a test asserting the composed
      rule end-to-end for both launch orderings: `resolveScene` → record → `sceneGenerationFor` yields a
      rebuild ONLY when the recorded mode was `Deferred`.

## 2. The shell wiring (`:app:ios`)

- [x] 2.1 In `SnapSyncRoot.kt`, record the resolved mode as `sceneMode()` returns it — an assignment, not
      a branch — with a comment stating that `MainViewController()` is its only caller, which is what makes
      the record complete. **Written as plain statements, not `.also { … }`**: `detektAppShell` holds this
      module at straight-line complexity and counts a trailing lambda against it, so `.also` failed the
      gate. A suppression was not warranted for a two-line body.
- [x] 2.2 Change `onSceneActive()` to return `sceneGenerationFor(handedOutScene)` instead of the constant
      `SCENE_GENERATION_ACTIVE`, keeping `everActive = true`. Update its KDoc: the generation is no longer
      "0 before any activation and 1 afterwards" but "one per placeholder handed out", and it may
      legitimately stay 0 for a whole process.
- [x] 2.3 Delete the now-unused `SCENE_GENERATION_ACTIVE` constant, or repurpose it as the named
      `Deferred` answer inside the resolver — do not leave a constant nothing reads.
- [x] 2.4 Add `result = { "generation=$it" }` to `onSceneActive`'s `Logger.invocation` call so the value
      reaches `debug.log`.
- [x] 2.5 In `MainViewController.kt`, replace `private val liveScene: UIViewController by lazy { … }` with
      a per-call `composeScene()`. Rewrite the KDoc: state Apple's create-per-identity contract, record
      that the memoization was measured to blank the screen (SE2, iOS 26.6, 2026-08-25), and explain why
      the screen-local-state defence no longer applies once the generation rule lands.
- [x] 2.6 Give `deferredScene()` `view.backgroundColor = UIColor.systemBackgroundColor` and delete the
      KDoc sentence claiming the scene delegate colours the window — nothing does.

## 3. Guards and gates

- [x] 3.1 Run `./gradlew compileIosMainKotlinMetadata` (the Linux-runnable iOS proxy) and fix any
      breakage before going further.
- [x] 3.2 Run `./gradlew build` and confirm the shell gates still pass at zero — in particular
      `detektAppShell` (which caught `.also`/`.apply` as complexity and forced the statement form) and
      `SwiftShellGuardTest` (no Swift file is touched, so `ContentView.swift` stays pinned at zero
      `if`/`guard`/`switch`/`??`). BUILD SUCCESSFUL with all gates green.
- [x] 3.3 Decide the design's first open question: whether to add a `:test:architecture` guard pinning
      `sceneMode()` to exactly one caller. **Resolved: implemented** as `SceneRecordCompletenessTest`
      (one caller, one writer), and negative-tested both assertions — each goes red for the right reason
      when violated, green on restore.
- [x] 3.4 Run `./gradlew architectureDiagrams` and commit any regenerated output; stale `architecture/`
      blocks the PR.

## 4. On-device verification

- [x] 4.1 Build a dev IPA (`ssh-mac-build`) from the fixed branch and install it on the SE2 — which
      currently has NO SnapSync installed and no membership, both left by this change's investigation.
      This install doubles as restoring the device to a usable state. **Done** (2026-08-26): the device is
      left running the clean, un-probed fixed build, verified rendering.
- [x] 4.2 Re-run the Probe A experiment against the fixed build: temporarily seed `everActive = true`,
      confirm the log still shows the armed ordering (`live` first, no `deferred`) and that
      `← onSceneActive = generation=0` now appears, that NO second `MainViewController(mode=live)` follows,
      and that the screen renders. Revert the probe. **Done** — all four confirmed on SE2/iOS 26.6:
      `MainViewController(mode=live)` once, `← onSceneActive = generation=0 (0ms)`, exactly ONE
      `MainViewController` call in the process (pre-fix: two), screen renders. The probe existed only on
      the runner and was reverted there before signing; the tree never carried it.
- [x] 4.3 Cold-launch the fixed build normally several times and confirm the healthy path is unchanged.
      **Cold path done on device** — 5 launches, every one `deferred` → `← onSceneActive = generation=1`
      → `live`, exactly one rebuild, screen renders.
      **Warm path done on a SIMULATOR** (iOS 26, 2026-08-26), which the device could not do: `dvt launch`
      always respawns, but `simctl launch` on a running app REUSES the process (measured — same pid), so a
      real warm activation is reachable there. ⚠️ **This is what caught a bug in the first fix**: the
      signal fell `1 → 0` once the placeholder was retired, and `.id(…)` rebuilds on a fall, so a third
      `MainViewController` call appeared on the first warm foreground. Rule made monotonic
      (`sceneGenerationAfter(previous, handedOut)`); re-verified: two warm foregrounds, `generation=1` at
      both, **exactly 2** `MainViewController` calls in the process, screen renders (`#F4F6F8`).
- [x] 4.4 Confirm the dark-mode cold launch shows no white flash. **Done on a SIMULATOR** — the right host
      for this, and my earlier claim that it was unverifiable was wrong: `simctl ui <dev> appearance dark`
      sets it headlessly and needs no device lease. A burst capture could NOT catch the ~161 ms placeholder
      window (12 frames, all post-launch, all identical), so the colour was verified DIRECTLY instead, with
      a probe forcing `sceneFor` to hold the placeholder on screen: centre pixel `(0,0,0)` under dark and
      `(255,255,255)` under light — `systemBackgroundColor` resolving dynamically, so the dark-mode flash
      is gone and it is the system colour rather than a hardcoded black. Probe reverted on the runner
      (`source restored: 0`); the tree never carried it.

## 5. Spec sync and landing

- [x] 5.1 Confirm `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and `--changes --strict`
      both pass. Remember validation checks structure, not truth — re-read the delta against the code that
      actually landed.
- [ ] 5.2 Open the PR with the `bug` changelog label (a customer-visible white screen, not `internal`),
      and cite the three Bugsink issues plus the Probe A/B measurement in the description.
- [ ] 5.3 Add a `Bugsink-Resolves:` trailer for SNAPSYNC-15 and SNAPSYNC-24 so `/ship` resolves them on
      merge. Do NOT include SNAPSYNC-19 — it is a different failure and is being investigated separately.
- [ ] 5.4 After merge, watch the next dumps from the reporting device for the armed signature (a process
      whose first `MainViewController` line is `live`, now readable directly from the generation line).
      Verification here is by absence, because the armed ordering could not be reproduced on the SE2
      without instrumentation.
