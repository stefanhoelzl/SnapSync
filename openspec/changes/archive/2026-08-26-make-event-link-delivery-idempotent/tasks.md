## 1. Start from a clean branch

- [x] 1.1 Reset the working branch's **code** to `origin/main`, keeping this change's own
      `openspec/changes/make-event-link-delivery-idempotent/` files — the probe commits (`bb461366` and
      its three predecessors) are all marked NOT FOR MERGE, and their evidence lives in this change's
      `design.md` and in Bugsink `SNAPSYNC-38` through `SNAPSYNC-48`. ⚠️ A bare `git reset --hard`
      discards the proposal along with the probes; the artifacts are committed separately for exactly
      this reason, so rebase or cherry-pick that commit rather than resetting past it
- [x] 1.2 Confirm the merged instrumentation from PR #215 is present on `main`: the scene-delegate
      recorders, and `onSceneWillConnect(activities=…)` forwarded **before** the `forEach` in the cold
      hook

## 2. Idempotent delivery (the fix, tested first)

- [x] 2.1 Add the duplicate-delivery rule to the join gate, keyed on the pending join's event and
      cleared when the pending join is committed or dismissed (see `design.md` Open Questions — the
      gate already owns "re-scanning the already-joined event is a no-op", the same species of rule)
- [x] 2.2 Record an ignored duplicate with the entry point that delivered it, so it is distinguishable
      from a link that never arrived
- [x] 2.3 `commonTest` (runs on JVM **and** `iosSimulatorArm64`): same link twice starts one pending
      join and issues one details fetch; a different link supersedes; the same link after commit or
      dismissal is a fresh delivery
- [x] 2.4 `commonTest`: an `autoJoin=true` link delivered twice provisions **once** — the only path
      that double-provisions today, so the sharpest test of the rule
- [x] 2.5 `:test:integration` over `:test:world`: assert `UiState` **and** world outcomes for a
      doubled delivery — one enrollment, one membership

## 3. Restore the SwiftUI delivery path

- [x] 3.1 Add `.onOpenURL` to the `WindowGroup`, forwarding the raw `absoluteString` to Kotlin under
      its own entry-point name; no parsing in Swift
- [x] 3.2 Add the Kotlin entry point with `@PlatformEntry` and the logging wrapper, routing to the same
      `onOpenUrl` door every other path uses
- [x] 3.3 Add its rig trigger-inventory entry (a trigger or an exclusion with the reason that makes the
      omission safe)
- [x] 3.4 Add `scene(_:didFailToContinueUserActivityWithType:error:)` as a recorder — the third of
      UISceneDelegate's continuation trio, and the only hook that NAMES a failure. The proposal lists it
      under "also in scope" and this task list omitted it; it was probe-only and never merged

## 4. Remove the app-delegate continuation trio

- [x] 4.1 Delete `application(_:willContinueUserActivityWithType:)`,
      `application(_:continue:restorationHandler:)` and
      `application(_:didFailToContinueUserActivityWithType:error:)` from the Swift shell
- [x] 4.2 Delete the three `onApp*` Kotlin entry points and their rig exclusions
- [x] 4.3 Shrink the `SwiftShellGuardTest` pin table in the same commit, so it never overstates the debt

## 5. Guards

- [x] 5.1 Extend `EventLinkDeliveryTest` to pin `.onOpenURL` and its forwarding, with a failure message
      carrying the evidence — this path is the likeliest to be deleted as cruft, because the shell's own
      comments argued for weeks that the modifier never fires
- [x] 5.2 Verify the guard is non-vacuous: it fails when the modifier or its forwarding is removed
- [x] 5.3 Confirm the guarded Swift sources are declared as inputs of the guard's test task, or the
      guard silently stops re-running when its subject changes

## 6. Correct the record

- [x] 6.1 Rewrite the falsified comment block in `iosApp/iosApp/iOSApp.swift` — `.onOpenURL` "never
      fires for a universal link" was true for a configuration with no custom scene delegate and is
      false now; scope the replacement to the build and configuration measured
- [x] 6.2 Update `SnapSyncRoot`'s KDoc on the scene-continuation entries to the "app already running"
      framing, dropping the warm-vs-cold and link-source framings that the measurements retired
- [x] 6.3 Check no other prose in the repo still claims the SwiftUI modifier cannot receive a universal
      link, or that iOS 18 cannot do continuations at all

## 7. Verify and ship

- [x] 7.1 `./gradlew build architectureDiagrams` green; `openspec validate --specs --strict` green
- [x] 7.2 Dispatch the branch (`gh workflow run ios.yml --ref <branch>`) and verify **on device**,
      because nothing else can: an iOS 18 device and an iOS 26 device, app running and force-quit,
      invite opened from Notes and from a messenger — `onOpenUrl` exactly **once** per opened link in
      all four cases, and the join surface renders
- [x] 7.3 Confirm from a diagnostic dump that duplicates are logged and ignored rather than silently
      absent
- [x] 7.4 Open the PR with the `bug` changelog label, and a `Bugsink-Resolves:` trailer for
      `SNAPSYNC-25`. ⚠️ ORDER: this cannot precede the archive — `/ship`'s precondition 1.3 aborts
      while any change is un-archived — so the sync-and-archive commit is part of the PR that ships,
      and this task completes on that merge. The task list originally implied the reverse order,
      which cannot occur
