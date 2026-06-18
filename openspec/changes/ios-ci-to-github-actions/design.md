## Context

`ios-first-target` chose Codemagic for iOS CI under a now-defunct constraint: building against the iOS 27 **beta** SDK required `xcode: edge` on an Apple-Silicon Mac. GitHub-hosted runners then capped at Xcode 26 and the self-hosted x86 VM couldn't run Xcode 27 (Apple-Silicon-only), so a cloud Mac (Codemagic) was the only path to the beta SDK. The current state: `build.yml` (`ubuntu-latest`, `./gradlew build`) on GitHub Actions + `codemagic.yaml` (`mac_mini_m2`, `xcode: edge`) on Codemagic, with `.github/rulesets/main.json` requiring both the `build` check (GitHub Actions, `integration_id 15368`) and `iOS simulator build` (Codemagic, `integration_id 34548`).

Two facts have since changed the calculus: (1) the **iOS 27 beta requirement is dropped** — the app targets the iOS 26 GM SDK; (2) GitHub's **`macos-26` runner** is GA (Feb 2026) with **Xcode 26 GM**, and standard macOS runners are **free and unlimited on public repos** (this repo is public). The sole justification for Codemagic no longer holds.

## Goals / Non-Goals

**Goals:**
- Build the iOS simulator app on **GitHub Actions `macos-26`** against the runner's **GM Xcode**, build-only and unsigned, as the merge-gating check — behavioural parity with the Codemagic gate, minus the beta SDK.
- Consolidate to **one CI provider**; remove `codemagic.yaml` and the cross-provider plumbing.
- Repoint the `branch-protection` required iOS check from Codemagic to GitHub Actions.

**Non-Goals:**
- Code signing, device archive, TestFlight, App Store — all in `ios-testflight-delivery`.
- Any `xcode: edge` / iOS 27 beta build (the requirement is gone; revisit when 27 reaches GM and the runner image carries it).
- Touching the Linux `build` job, the `:app:ios` module, the `iosApp/` Xcode project, or the shared iOS targets.

## Decisions

### D1 — GitHub Actions `macos-26` replaces Codemagic
The only reason for Codemagic was the beta SDK on a cloud Mac. With GM 26 as the target and `macos-26` carrying Xcode 26 GM for free on public repos, GitHub Actions does the iOS build on the provider we already use for Linux. This **reverts the `ios-first-target` D1/D2 provider decision**. Net removals: the Codemagic↔GitHub OAuth link, the external required check, the Codemagic YAML dialect, and the moving-beta gate (former risks R3/R5 of `ios-first-target` disappear).

### D2 — Separate `ios.yml`, not a job in `build.yml`
The Linux `build` job stays an independent, fast `ubuntu-latest` workflow for quick feedback. iOS runs in its own `ios.yml` on `macos-26` with its own concurrency group and toolchain caching. Distinct runner, distinct cache keys, distinct (slower) wall-clock — keeping them separate avoids coupling Linux feedback latency to a cold macOS build.

### D3 — Pin a stable check context `ios-build`; require `(ios-build, 15368)`
The required-status-check match is `(context, integration_id)`. The job's display `name:` is pinned to `ios-build` so its posted context is stable and predictable, and the ruleset requires exactly `{ "context": "ios-build", "integration_id": 15368 }` (15368 = the GitHub Actions app, same as `build`). This directly addresses the historical R1 ("required check name must match exactly or merges freeze forever").

### D4 — Build-only, unsigned, simulator gate (behaviour preserved)
The build step stays `xcodebuild -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build`, triggering the project's "Compile Kotlin Framework" Run Script phase. No tests, no simulator boot, no signing. The "no signing" clause is retained deliberately — signing is the boundary with `ios-testflight-delivery`.

### D5 — Caching: `setup-gradle` + `actions/cache` for Konan
`gradle/actions/setup-gradle@v5` handles `~/.gradle` (matching `build.yml`). `~/.konan` (the Kotlin/Native toolchain) is cached via `actions/cache` keyed on the Kotlin/Native version so a warm build skips the multi-minute Konan download. Replaces Codemagic's `cache_paths`.

### D6 — Cancel-superseded via `concurrency:`
A `concurrency: { group: ${{ github.ref }}, cancel-in-progress: true }` block mirrors `build.yml` and replaces Codemagic's `cancel_previous_builds`.

### D7 — Toolchain launcher: `setup-java` temurin 25
Mirror `build.yml`: `actions/setup-java` (temurin, Java 25) provides the Gradle launcher JVM; the JDK toolchain is foojay-auto-provisioned. (Codemagic used a Java 21 launcher; standardising on 25 matches the Linux job.)

### D8 — Float on the runner's default GM Xcode (don't pin)
`macos-26` defaults to a GM Xcode (26.4.1 as of 2026-05-18) and bumps over time. We float on the default rather than pinning via `maxim-lobanov/setup-xcode`, accepting that a future default bump is a GM→GM move (low risk, unlike the old moving-beta). Pinning remains an available mitigation (see R3) if a bump ever breaks the build.

## Risks / Trade-offs

- **R1 — Required-check context mismatch freezes merges** → If the ruleset requires a `(context, integration_id)` the GitHub Actions job never posts, *all* merges block. Mitigation: D3 pins the job name; confirm the exact posted context on a real PR run and require precisely that string; verify green on the swap PR before the reapplied ruleset enforces it.
- **R2 — Bootstrap ordering on the swap PR** → The PR that repoints the required check must itself show the new `ios-build` check green before `/ship` reapplies the ruleset requiring it. Mitigation: the GitHub Actions check runs on the PR's pushes (no external connection needed), so it is green well before ship; confirm before merging.
- **R3 — `macos-26` default Xcode bump breaks the build** → A future runner-image Xcode bump could change behaviour. Mitigation: GM→GM bumps are low-risk; if one breaks, pin with `maxim-lobanov/setup-xcode@v1` to a known GM version. Far smaller blast radius than the retired moving-beta gate.
- **R4 — Cold `macos-26` build is slow / Konan re-downloads** → First/cache-cold builds download the Konan toolchain (multi-minute). Mitigation: D5 caching. Cost is wall-clock only — macOS minutes are free on public repos, so there is no minute-budget pressure (the old R5 is gone).
- **R5 — Stale Codemagic check still posts** → After removing `codemagic.yaml`, if the Codemagic webhook still fires it posts a check that is simply no longer *required* — harmless. Mitigation: optional operator cleanup (disconnect the app/webhook in the Codemagic UI).

## Migration Plan

1. Add `.github/workflows/ios.yml` (macos-26, GM Xcode, `setup-java` 25, `setup-gradle`, Konan `actions/cache`, `concurrency` cancel, `xcodebuild` simulator gate, job `name: ios-build`).
2. Delete `codemagic.yaml`.
3. On the change's PR: confirm `ios.yml` runs, the `ios-build` check goes green, and the Linux `build` check stays green; capture the exact posted context string (R1).
4. Update `.github/rulesets/main.json`: replace the Codemagic entry with `{ "context": "ios-build", "integration_id": 15368 }` (the captured string). Reapplied during `/ship`.
5. (Optional, operator) Disconnect the Codemagic GitHub app/webhook.

## Open Questions

- Exact GitHub Actions posted context string — proposed `ios-build`, confirmed empirically in step 3 (blocks R1).
- Whether to pin `macos-26`'s Xcode (proposed: float on default GM; pin only if a bump breaks — R3).
- The `ios-first-target` follow-up to "pin `xcode: 27.x` at GM" is **obsolete** — there is no beta gate to pin anymore; iOS-27 adoption will instead be a future move to a `macos-` image carrying Xcode 27 GM.
