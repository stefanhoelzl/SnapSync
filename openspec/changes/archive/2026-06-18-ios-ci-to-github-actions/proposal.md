## Why

The iOS CI runs on **Codemagic** for one reason only: it needed `xcode: edge` (the iOS 27 beta SDK) on an Apple-Silicon Mac, which the abandoned self-hosted x86 VM couldn't provide and GitHub-hosted runners didn't carry. That reason is gone. We have **dropped the iOS 27 beta requirement** — the app targets the iOS **26 GM** SDK (the background-upload work uses the iOS 26.1 `PHBackgroundResourceUploadExtension`, which ships on GM 26). Meanwhile GitHub now offers a **`macos-26` hosted runner** (GA Feb 2026) carrying **Xcode 26 GM**, and standard macOS runners are **free and unlimited on public repositories** — and this repo is public.

So the second CI provider is now pure overhead: a Codemagic↔GitHub OAuth link, an external required status check, a separate YAML dialect, and a moving-beta gate we no longer want. Consolidating the iOS simulator build onto GitHub Actions removes all of it and leaves a single CI provider.

This change is the **unblocked half** of the iOS-on-a-real-phone work: it has zero external prerequisites and can land immediately. The signed archive + TestFlight delivery (which is blocked on Apple Developer Program enrollment, an App Store Connect record, and an API key) is deliberately a separate follow-up change, `ios-testflight-delivery`.

## What Changes

- Add a GitHub Actions workflow (`.github/workflows/ios.yml`) on `macos-26` that builds the iOS simulator app via `xcodebuild` against the runner's **GM Xcode** (no `xcode: edge`, no beta). It remains **build-only and unsigned** (`CODE_SIGNING_ALLOWED=NO`) — the build is the sole pass/fail gate, exactly as before.
- The workflow's job posts a **stable status-check context** (`ios-build`, reported by the GitHub Actions app, `integration_id 15368`) used to gate merges.
- Caching moves from Codemagic `cache_paths` to GitHub Actions caching: `gradle/actions/setup-gradle` for `~/.gradle`, `actions/cache` for `~/.konan` (Kotlin/Native toolchain).
- Cancel-superseded moves from Codemagic `cancel_previous_builds` to a `concurrency:` block keyed on the ref (mirroring `build.yml`).
- **Remove `codemagic.yaml`** entirely.
- Update `.github/rulesets/main.json`: replace the required check `{ "context": "iOS simulator build", "integration_id": 34548 }` (Codemagic) with `{ "context": "ios-build", "integration_id": 15368 }` (GitHub Actions).

## Capabilities

### Modified Capabilities
- `ios-ci`: the iOS simulator build moves from a Codemagic workflow (`xcode: edge`, iOS 27 beta SDK) to a GitHub Actions workflow on `macos-26` (GM Xcode). Still every-push, build-only, unsigned, reporting a merge-gating status check. Caching and cancel-superseded are restated in GitHub Actions terms.
- `branch-protection`: the required iOS build check is now the one **reported by GitHub Actions**, not Codemagic.

## Impact

- **New CI config**: `.github/workflows/ios.yml` (macos-26). **No external connection step** — GitHub Actions is already the repo's CI; the Codemagic↔GitHub OAuth that `ios-first-target` required is no longer needed.
- **Removed CI config**: `codemagic.yaml`. The Codemagic app/webhook becomes inert; an operator can disconnect it in the Codemagic UI (out-of-repo cleanup, not required for correctness).
- **Modified ruleset**: `.github/rulesets/main.json` — the iOS required check's `context`/`integration_id` change; reapplied during `/ship`.
- **Bootstrap-ordering caveat**: the PR that swaps the required check must show the new `ios-build` (GitHub Actions) check **green on that PR** before the ruleset (reapplied at ship) requires it — otherwise merges freeze. Same class of risk as the original Codemagic check, mitigated the same way (confirm green first, pin the exact context string).
- **Unchanged**: the GitHub Actions Linux `build` job (`ubuntu-latest`, `./gradlew build`); the `:app:ios` module; the `iosApp/` Xcode project; the shared modules' iOS targets.
- **Out of scope**: code signing, device archive, TestFlight, App Store — all deferred to `ios-testflight-delivery`. No `xcode: edge` / iOS 27 beta anywhere.
