## 1. Rebase onto the in-flight sibling change first

- [x] 1.1 Confirm `extract-upload-platform-mappings` (workspace `photokit-mapping-extraction`) has landed on
      `main`, then rebase this branch onto it. It ships **ahead** of this change and edits the same file:
      `IosUrlSessionUploadPlatform.kt` is modified and `UrlSessionOutcome.kt` / `UrlSessionOutcomeTest.kt` are
      added beside it, so the file's **shape** changes, not just its contents. Both branches were cut from
      `dc16b86b`. Rebasing after it lands is cheap; discovering it mid-apply is not.
- [x] 1.2 After the rebase, re-locate the deletion sites in `IosUrlSessionUploadPlatform.kt` before editing —
      the constructor parameter list and the `session by lazy` may have moved. Task 3.4's grep is the check that
      nothing was missed.

## 2. Delete the transport downgrade

- [x] 2.1 `IosUrlSessionUploadPlatform` (`:adapter:ios:app-only`): drop the `useBackgroundSession` constructor
      parameter and the `useBackground` field, and collapse the `session by lazy` `if` to
      `backgroundSessionConfigurationWithIdentifier(sessionId)` unconditionally.
- [x] 2.2 Replace that constructor's comment with the measured text per design D3: the transport runs on the
      simulator (cite the probe, its date, `iosSimulatorArm64`, macOS 26.5.2 / Xcode 26.6), and state that
      **app relaunch for `handleEventsForBackgroundURLSession` is unproven** there. Do not write "background
      URLSession works in the simulator" unqualified.
- [x] 2.3 `UrlSessionUploadController` (`:app:ios`): drop the `useBackgroundSession` parameter and stop passing
      it to the platform.
- [x] 2.4 `SnapSyncRoot`: delete the `useBackgroundSession = (mode as CompositionMode.Live).useBackgroundSession`
      argument, removing the cast that read `Live` outside the one `when (mode)` switch.

## 3. Delete the host fact and `OsFacts`

- [x] 3.1 `SnapSyncRoot`: delete the `NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"]` read and
      the `osFacts` property; pass `backgroundUploadSupported()` straight to the resolver. Drop the now-unused
      `OsFacts` import.
- [x] 3.2 `CompositionMode.kt` (`:domain` `model/`): delete the `OsFacts` data class; narrow
      `CompositionMode.Live` to `Live(tier)`, removing `useBackgroundSession` and its KDoc; change
      `resolveComposition` to take `backgroundUploadSupported: Boolean` in place of `osFacts` and update the
      KDoc that describes its inputs.
- [x] 3.3 `CompositionModeTest`: delete the `a simulator downgrades off the background session` test; replace the
      `device`/`old`/`simulator` `OsFacts` fixtures with plain booleans; update the three `Live(...)` assertions
      that name `useBackgroundSession`.
- [x] 3.4 Confirm no other reference survives: `grep -rn "OsFacts\|isSimulator\|useBackgroundSession\|SIMULATOR_DEVICE_NAME"`
      over `app/`, `domain/`, `adapter/`, `test/` returns nothing outside `openspec/changes/archive/`.

## 4. Follow the deletion through the guards and generated docs

- [x] 4.1 `PlatformIdentifierTest` (`:test:architecture`): reword the accepted-exception reason for
      `CompositionMode.kt`, which currently says "the resolver is a total function over `OsFacts`". Keep the
      `PHOTOKIT`/`URL_SESSION` pin — it is still required and still correct.
- [x] 4.2 `SnapSyncApp.kt` (`:domain` `compose/`): update the comment that cites `OsFacts` as a value shape.
- [x] 4.3 Run `./gradlew architectureDiagrams` and commit the result (`architecture/di.md` lists `OsFacts`).
      The `diagrams` check is required; a stale tree blocks the PR.
- [x] 4.4 `app/ios/CLAUDE.md`: correct the two-upload-tiers section, which states the transport "stays a
      background `URLSession` (simulator-ness is read from `SIMULATOR_DEVICE_NAME`, not inferred from this
      flag)" — the parenthetical describes deleted code. Keep the surrounding point: the force flag selects the
      tier and nothing else.

## 5. Sync the specs

- [x] 5.1 Apply the `ios-url-session-upload` delta: replace the "SHALL be derived from the environment" sentence
      with the one-transport-per-host requirement and add the new scenario. Leave lines 16 and 350 **unchanged** —
      they were correct.
- [x] 5.2 Apply the `module-architecture` delta to "One shared composition" (resolver inputs wording plus the
- [x] 5.4 Apply the `ios-app-shell` delta (the `OsFacts` phrase in "iOS live composition root") — a third
      capability, surfaced by the archive's dead-types gate rather than by the impact analysis.
      target-fixed-fact scenario).
- [x] 5.3 Verify no other spec asserts the simulator downgrade:
      `grep -rn "simulator" openspec/specs/ | grep -i "session\|transport"`.

## 6. Supersede D5 (no archive edit)

- [x] 6.1 Confirm `changes/archive/2026-07-12-fix-download-session-lifecycle/design.md` is **untouched** by this
      change — the correction lives in this change's `design.md` D4 only.
- [x] 6.2 Confirm this change's `design.md` D4 carries all three facts: the parenthetical is false, D5's decision
      stands on its `__NSURLBackgroundSession` subclass argument, and D5's "downloads inert on the simulator" is
      now unproven.

## 7. Unrelated correction (separate commit, same PR)

- [x] 7.1 `test/world/build.gradle.kts`: correct the comment claiming JUnit 4 is "the framework every jvm test
      task in this build runs on" — `:test:architecture` and `:tools:diagrams` use `useJUnitPlatform()` (JUnit 5),
      and Compose UI tests pull JUnit 4 via `compose.desktop.uiTestJUnit4`. Keep the dependency itself: it is
      correct for this module. Commit separately from tasks 2–6.

## 8. Verify

- [x] 8.1 `./gradlew build` green (compiles all targets, JVM tests, `:test:architecture` guards, `detektAppShell`,
      `diagrams`).
- [x] 8.2 `./gradlew compileIosMainKotlinMetadata` green — the Linux-runnable proxy for the iOS source sets.
- [x] 8.3 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` passes (structure only — it opens no
      `.kt` file and cannot see whether the claims are true).
- [x] 8.4 iOS simulator tests (`iosSimulatorArm64Test`) green — run on ssh-mac 2026-08-09: 1221 tests, 0
      failures, 0 errors across 9 modules; `CompositionModeTest` ran its 9 remaining cases on
      `iosSimulatorArm64`. CI `macos-26` re-runs them as a merge gate.
- [ ] 8.5 PR carries the `internal` changelog label.
