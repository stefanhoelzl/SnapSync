## Context

The iOS app builds today only as an **unsigned simulator app** (`ios-ci`, `.github/workflows/ios.yml`) on a `macos-26` runner. There is no signed build and no way onto a physical device. The developer is on **Linux with no Mac** (the macOS runner is the only Apple build environment), and holds a **paid Apple Developer Program** membership with a known Team ID. The app is currently a **shell** — it renders the shared Compose `StatusScreen` with a static `UiState`; there is no PhotoKit, no upload extension, no entitlements. Distribution channel is **TestFlight** (design §1, §8 "ops"), and TestFlight delivery was explicitly parked until now.

This change stands up the signing + TestFlight pipeline against the trivial shell, so the path is proven before the upload extension complicates it.

## Goals / Non-Goals

**Goals:**
- Get the current shell onto a physical iPhone via **TestFlight internal testing**, entirely from the macOS CI runner (no Mac).
- Prove the full **build → sign → archive → upload** path end-to-end, so a first failure is unambiguously a *pipeline* problem, not *app logic*.
- Keep the existing unsigned simulator **merge gate untouched**.
- Minimal, GitHub-native credential handling — encrypted secrets only, no certificate in the cache.

**Non-Goals:**
- The `PHBackgroundResourceUploadJobExtension` target, App Groups, entitlements, or any real upload functionality (later slices).
- App Store (public) release or external TestFlight testers / Beta App Review.
- fastlane, `match`, or a separate credential-storage repo.
- Bumping the deployment target to iOS 27 (the shell has no iOS-27 APIs; stays at 16.0).
- Local "Run on device" from Xcode (impossible without a Mac).

## Decisions

### D1 — Channel: TestFlight, built and uploaded from CI
A Mac-less developer cannot sideload via Xcode/Apple Configurator. The only path onto hardware is build+sign on the macOS runner → upload to App Store Connect → install via the **TestFlight** app on the phone. This also matches the design's chosen channel. *Alternatives:* local Xcode dev install (needs a Mac); ad-hoc IPA (needs a Mac/sideloader to install). Both rejected — no Mac.

### D2 — Signing credentials: official Apple Actions + encrypted GitHub Secrets (no cache, no fastlane)
Use `Apple-Actions/import-codesign-certs` to load a hand-minted Distribution `.p12` (base64 secret) into a temporary keychain, and `Apple-Actions/upload-testflight-build` to upload — both first-party, actively maintained. All credentials are **encrypted GitHub Secrets**.

The certificate is deliberately **not** cached: the Actions cache is not a secret store (not encrypted-at-rest, restorable by any run in the repo, larger exposure on a **public repo**), and a `.p12` is the Distribution private key. GitHub's own guidance is "use secrets."

*Alternatives:* raw `security` keychain commands (more YAML, and you'd also store the profile) — rejected as more to maintain; `fastlane match` (mints/rotates the cert via API, auto-managed) — rejected because it costs a separate private repo + a match passphrase for a single shell app.

### D3 — Provisioning profile: auto-managed via `xcodebuild -allowProvisioningUpdates`
Rather than pre-creating an App Store profile in the portal and downloading it, let `xcodebuild -allowProvisioningUpdates` (authenticated by the App Store Connect API key) create/refresh the profile against the **imported** certificate.

This rests on the **cert-vs-profile exhaustion distinction**: *certificates* are scarce (Apple caps Distribution certs at ~2–3) and dangerous to auto-create on ephemeral runners — so we mint the cert by hand and import it; auto-provisioning then never creates a cert. *Profiles* are cheap and unlimited, so auto-managing them carries none of that risk and removes (a) a one-time manual "create profile" step and (b) an annual profile-renewal papercut (auto-managed profiles re-fetch fresh each build).

*Alternative:* pre-made profile + `Apple-Actions/download-provisioning-profiles` — rejected for the shell (extra manual step + a profile that expires). **Revisit later** only if the iOS-27 `com.apple.photos.background-upload` entitlement turns out not to be auto-provisionable — then the extension target would need a hand-made profile (option (a)), for that one target. That is unknowable today and deferred with the extension.

### D4 — Trigger: every push to main, no path filter
Cut a build on every `main` commit. An allow-list `paths:` filter would have to track the iOS binary's dependency closure (most of `domain/**`) and could **drift into a silent skip** (binary changed, filter didn't fire → stale build on device — the dangerous failure). For a solo tool nowhere near TestFlight quotas, the zero-drift, zero-maintenance simplicity of "every push" outweighs a few wasted build slots. Build number = `github.run_number` increments regardless, so skips/re-runs never collide.

### D5 — Monotonic build numbers from `github.run_number`
`MARKETING_VERSION = 0.1.0` (pre-release signal); `CURRENT_PROJECT_VERSION` injected at build from `github.run_number` (a monotonic counter). TestFlight rejects re-used build numbers for a marketing version; `run_number` only ever increases, even across re-runs. *Alternatives:* manual bumps (easy to forget); keep `1.0/1` (collides on the second upload).

### D6 — Separate workflow; gate untouched; double framework link accepted
A new `.github/workflows/ios-release.yml`, independent of `ios.yml`. On a push to `main` both run — the gate links the framework for the simulator, the release links it for `iosArm64` — i.e. two Kotlin/Native links per merge. Accepted as the cost of decoupling: the gate stays simple and unsigned, the release owns all signing. `~/.konan` caching softens the cost.

### D7 — Export compliance pre-declared
`ITSAppUsesNonExemptEncryption = NO` in `Info.plist` (the app ships no non-exempt crypto — the SigV4 work is later and uses standard exempt crypto) + an `ExportOptions.plist` with `method: app-store-connect`, so uploads don't stall on a manual compliance prompt each build.

### D8 — Team ID committed, signing style CI-managed
`TEAM_ID` goes into the committed `Config.xcconfig` — a Team ID is visible in any shipped IPA and is not a secret. The app target's device/release build uses CI-managed signing (`-allowProvisioningUpdates`) while the simulator gate stays unsigned (`CODE_SIGNING_ALLOWED=NO`), so the two never conflict.

## Risks / Trade-offs

- **First signed upload is the real signing test** → the shell is the ideal canary; a failure is unambiguously pipeline, not app logic. Iterate on the workflow against a trivial binary.
- **`app.snapsync` may not be globally unique in App Store Connect** → if the portal rejects it, fall back to a different bundle id and update `Config.xcconfig` (the different-bundle-id branch).
- **`-allowProvisioningUpdates` needs the ASC API key at *build* time, not just upload** → minor; the key is already a loaded secret. Well-trodden (it is what Xcode's "automatically manage signing" does headlessly).
- **Every-push uploads burn TestFlight build slots (90-day expiry) on docs/openspec-only merges** → accepted; solo tool, far from quotas; revisit a `paths-ignore` list only if noise becomes real.
- **Double Kotlin/Native link per merge (gate + release)** → accepted; `~/.konan` cache mitigates; revisit if minutes become a constraint.
- **iOS-27 background-upload entitlement may later defeat auto-provisioning** → deferred with the extension; at that point hand-make a profile for the extension target (D3). Does not affect the shell.
- **Manual one-time Apple-account prerequisites** (cert, ASC key, bundle id, app record, internal tester) are outside CI and must be done by the developer → captured as explicit, ordered tasks.
