## Context

The shared KMP stack (8 modules) has only ever targeted `jvm()`; nothing compiles for iOS, there is no iOS app, and CI is a single `ubuntu-latest` GitHub Actions job running `./gradlew build`. The running desktop app renders `StatusScreen` from a `PanelController` mock — no platform wires a live ledger or a SQLDelight driver. The build VM previously planned for iOS (QEMU-on-AMD) is dead: it is x86_64 and Xcode 27 (which ships the iOS 27 SDK) is Apple-Silicon-only. The operator has no Mac. These constraints, plus the requirement to build against the iOS 27 beta SDK, were resolved in a prior design interview.

## Goals / Non-Goals

**Goals:**
- Compile the shared UI-render-path modules for iOS and prove the full pipeline (Kotlin/Native → Compose iOS → Xcode app) on cloud CI.
- Produce a runnable iOS simulator app rendering the real shared `StatusScreen` (one static `UiState`).
- A merge-gating iOS build check on every push, built against the iOS 27 beta SDK.
- Do all Mac-free work on the existing free GitHub Actions Linux runner; do only the irreducible Apple delta on Codemagic.

**Non-Goals:**
- Live ledger / SQLDelight `NativeSqliteDriver` wiring (no platform has this; out of scope).
- iOS unit/simulator tests, device builds, code signing, TestFlight, App Store.
- Android targets (Android will live on the free Linux runner when it arrives).
- Pinning a stable Xcode (impossible until iOS 27 GM; tracked as a follow-up).

## Decisions

### D1 — Provider: Codemagic, over Bitrise / Xcode Cloud / self-hosted
Kotlin/Native cannot cross-compile Apple targets from Linux, so a macOS host is unavoidable. GitHub-hosted runners cap at Xcode 26 (no iOS 27 beta). With no Mac, Xcode Cloud is blocked by its Mac-only first-workflow onboarding. Codemagic is Mac-free (the Xcode project is committable template files; config is YAML), KMP/CMP-native, has 500 free M2 min/mo, and exposes the beta via `xcode: edge`. Bitrise was a close second; Xcode Cloud is reconsidered only if Mac access appears.

### D2 — Two-track CI; new `ios-ci` capability, not a `ci-build` delta
Linux-buildable work stays on GitHub Actions (`ci-build`, unchanged); only the Kotlin/Native framework + `xcodebuild` run on Codemagic. `ios-ci` is a **separate** capability mirroring `desktop-app-shell : ci-build :: ios-app-shell : ios-ci`. Rationale: the two CI tracks share a *shape* (build-on-push → gating check) but no *mechanics* (provider, runner, command, check name, concurrency model), and `ios-ci` is a seed that will grow signing/TestFlight/App Store while `ci-build` stays stable. Their current twin-ness is a transient seed-stage coincidence.

### D3 — `:app:ios` module + `iosApp/` Xcode project (mirror of `:app:desktop`)
A new KMP `:app:ios` module (iOS targets only) depends on `:domain:ui`, exposes a **static** Compose framework (`isStatic = true`, `baseName = "SnapSyncKit"`), and provides `MainViewController = ComposeUIViewController { StatusScreen(<static UiState>) }`. The `iosApp/` Xcode project consumes the framework via the `embedAndSignAppleFrameworkForXcode` Run Script build phase, so `xcodebuild` transitively triggers the Gradle framework link. Generated from the CMP wizard template and committed from Linux.

### D4 — "Shared stack builds for iOS" lives in `ios-app-shell`, scoped to the app's closure
Buildability is an app-shell concern (mirroring `desktop-app-shell`'s "Buildable on the verified toolchain"), scoped to `:app:ios` + its dependency closure compiling for `iosSimulatorArm64`. The off-path `:capability:s3` gets iOS targets too (forward-readiness) but as a **task, not a standing invariant** — we deliberately avoid committing to "every shared module is iOS-capable forever."

### D5 — iOS check is REQUIRED to merge (accepting moving-beta risk)
The iOS build check is added to `.github/rulesets/main.json` as a required check alongside `build`, making `branch-protection` the single registry of merge gates. This couples merge-ability to a moving beta (`xcode: edge`) by necessity, since 27 cannot be pinned until GM. Accepted deliberately; mitigated by the escape hatch (R3) and a planned pin at GM.

### D6 — Targets: declare `iosArm64` + `iosSimulatorArm64`; CI builds simulator only
Default-hierarchy `iosMain`. Device target proves codegen but is not built on CI (no device to run on, would need signing). CI builds only the simulator app — no signing.

## Risks / Trade-offs

- **R1 — Required check name must match exactly, or merges freeze forever** → A ruleset requiring a check string Codemagic never posts blocks *all* merges. Mitigation: empirically confirm the exact context string Codemagic's GitHub App posts (observe a real run) before adding it to `main.json`; require *that* exact string.
- **R2 — Bootstrap ordering deadlock** → The PR that adds the required iOS check can't merge unless the check is already green on it. Mitigation: Codemagic is already connected to the repo, so only the build config remains; confirm a green check on the PR *before* the ruleset reapply requires it. (Connection step is done, which removes the slowest part of this risk.)
- **R3 — Moving beta as a required gate freezes merges on toolchain churn** → `xcode: edge` rotating (new beta, Kotlin/Native lag) or a Codemagic outage reds the build through no code change, blocking even Linux-only PRs. Mitigation: the ruleset is committed JSON applied via admin `gh` — drop/restore the check in `main.json` (or admin-bypass) to unfreeze; pin `xcode: 27.x` at GM.
- **R4 — Adding iOS targets breaks the green Linux `./gradlew build`** → Mitigation: Kotlin disables Apple-target compile tasks on non-Mac hosts, so `build` should stay green on `ubuntu-latest`; verify explicitly as a task before relying on it.
- **R5 — Free-minute exhaustion at every-push** (operator chose every-push over the 500 min/mo budget) → Mitigation: aggressive `~/.gradle` + `~/.konan` caching (warm ~3–5 min vs cold ~12 min) and cancel-superseded-builds.

## Migration Plan

1. Add iOS targets to the render-path modules; verify Linux `./gradlew build` stays green (R4).
2. Add `:app:ios` (framework + `MainViewController`) and the committed `iosApp/` Xcode project.
3. Author `codemagic.yaml` (mac M2, `xcode: edge`, caches, cancel-superseded, `xcodebuild` simulator gate).
4. (Codemagic ↔ GitHub already connected.) Confirm Codemagic runs the committed `codemagic.yaml` on the change's PR, that a green iOS check appears, and record its exact context string (R1, R2).
5. Add that exact check string to `.github/rulesets/main.json`; it reapplies during `/ship`.
6. Add `:capability:s3` iOS target (forward-readiness).
7. Follow-up (post-GM): pin `xcode: 27.x`.

## Open Questions

- Exact Codemagic status-check context string (resolved empirically in step 4 — blocks R1).
- Final iOS deployment target (proposed iOS 16.0) and bundle id (proposed `app.snapsync`) — low-stakes, confirm during step 2.
- Which static `UiState` the first screen renders (e.g. `InProgress`) — cosmetic.
