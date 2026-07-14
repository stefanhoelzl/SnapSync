# ios-testflight-delivery Specification

## Purpose
Makes **`main` the public alpha channel**: every commit merged to `main` reaches public TestFlight testers automatically, with no human step. Two jobs carry it. **`ios-deliver`** signs, exports and uploads the archive `ios-build` already produced (never recompiling it), and **depends on both merge gates** (`ios-build` and `ios-test`, capability `ios-ci`) so **a red test suite stops the release**. **`ios-promote`** then puts that build into the **`alpha` external group**, whose public link anyone may tap — because **uploading is not distributing**: a build that is merely uploaded reaches the internal group and no public tester, which is how builds came to pile up unseen in App Store Connect while the alpha group held a single hand-placed build.

Promotion is **silent and unfiltered**. Testers are **never notified** (`autoNotifyEnabled=false`); they ride `main` via TestFlight auto-update. **Every** `main` build is promoted, even one binary-identical to its predecessor, because every filter considered fails toward *"a real fix silently never reaches testers"* — the worst thing an alpha channel can do — while promoting everything fails only toward noise.

Beta App Review is **in the loop but is not a gate**: a build on an already-approved `MARKETING_VERSION` auto-approves instantly, and a build may join the group while still `WAITING_FOR_REVIEW`. The exception is a **`MARKETING_VERSION` bump**, which forces a real first-of-version review — and stalls the channel while staying green.

Both delivery and promotion are decoupled from merges **structurally** — separate `main`-only jobs posting no required status check — rather than by `continue-on-error`, so a failure is visibly red yet blocks nothing. Signing combines **two imported persistent certificates** (Apple Distribution + Apple Development, from GitHub Secrets) with **cloud-managed provisioning profiles** (App Store Connect Admin API key, no fastlane/`match`). Per-branch installability before merge is served out of band by the ssh-mac build loop (dev infrastructure), not TestFlight. Also covers build numbering, export options, and the required signing credentials.

Decision record: `changes/archive/2026-07-14-gate-testflight-on-tests` (splitting delivery out of the build gate),
`changes/archive/2026-07-14-promote-main-builds-to-alpha` (promotion into the public alpha channel).

## Requirements

### Requirement: Delivery gates on the test suite

TestFlight delivery SHALL be performed by a dedicated `ios-deliver` job in `.github/workflows/ios.yml` that declares `needs: [ios-build, ios-test]`. The job SHALL run **only** when **both** merge gates conclude successfully on that commit; if either the device build or the simulator test suite fails, `ios-deliver` SHALL NOT run and **nothing SHALL be uploaded to TestFlight**.

This closes a hole in the previous shape, where export and upload lived inside `ios-build` — a job with no dependency on `ios-test`. A commit whose test suite was red on `main` was still delivered to testers, because the build job neither knew nor cared about the test job's result.

#### Scenario: A red test suite stops the release
- **WHEN** a commit on `refs/heads/main` compiles (so `ios-build` is green) but the `ios-test` simulator suite fails
- **THEN** the `ios-deliver` job does not run and no build is uploaded to TestFlight

#### Scenario: A red build stops the release
- **WHEN** a commit on `refs/heads/main` fails to compile
- **THEN** the `ios-deliver` job does not run and no build is uploaded to TestFlight

#### Scenario: Both gates green delivers
- **WHEN** a commit on `refs/heads/main` has both `ios-build` and `ios-test` green
- **THEN** `ios-deliver` exports an `app-store-connect` signed IPA from `ios-build`'s archive and uploads it to TestFlight via App Store Connect

### Requirement: Signed device build delivered to TestFlight on main only

The system SHALL deliver a signed iOS build to **TestFlight** only on pushes to **`refs/heads/main`** (the `ios-deliver` job is guarded by `if: github.ref == 'refs/heads/main'`); on any **other** ref no export and no upload occur. The signed **archive** itself SHALL still be produced on **every** ref (it is the `ios-build` merge gate — see capability `ios-ci`).

The device (`iosArm64`) app SHALL be compiled exactly **once** per push: `ios-deliver` consumes the archive `ios-build` published as a workflow artifact and **re-signs and packages** it, and SHALL NOT recompile the app. Per-branch device installability before merge is **not** served by TestFlight; it is served **out of band** by the interactive ssh-mac build loop (dev infrastructure — `.github/workflows/ssh-mac.yml`; see the runbook in `CLAUDE.md`), not by any CI artifact. Both jobs SHALL run on a `macos-26` hosted runner with the runner's GM Xcode.

#### Scenario: A push to a non-main branch does not upload to TestFlight
- **WHEN** a commit is pushed to any ref other than `refs/heads/main`
- **THEN** `ios-build` still archives the device app (the merge gate) but publishes no archive artifact, and `ios-deliver` does not run

#### Scenario: The device app is compiled only once per push
- **WHEN** a commit is pushed
- **THEN** the device (`iosArm64`) framework is compiled exactly once — as `ios-build`'s signed archive — and `ios-deliver` re-signs and packages that same archive rather than compiling a second time

### Requirement: Delivery never blocks merges, and never fails silently

Delivery **and promotion** SHALL be decoupled from the merge gates **structurally**: they live in separate `main`-only jobs (`ios-deliver` and `ios-promote`) that post **no required status check** (capability `branch-protection` requires `build`, `ios-build` and `ios-test`, and SHALL NOT require `ios-deliver` or `ios-promote` — a job that never runs on a pull-request branch would, if required, freeze every merge). Because they can block nothing, `ios-deliver` and `ios-promote` SHALL NOT use `continue-on-error`: a failed export, a failed App Store Connect upload, or a failed promotion SHALL conclude the job as **failure (red)**, so a broken delivery is visible rather than hidden inside an otherwise-green run.

This replaces the previous `continue-on-error` convention, under which a transient delivery failure left the run green and could pass unnoticed.

#### Scenario: A delivery flake is red but blocks nothing
- **WHEN** both gates are green on `main` but the export or the TestFlight upload fails
- **THEN** the `ios-deliver` job concludes as failure (red) and the failure is plainly visible, while no merge is blocked (the commit is already merged and `ios-deliver` is not a required check)

#### Scenario: A promotion flake is red but blocks nothing
- **WHEN** `ios-deliver` succeeds on `main` but the promotion to the `alpha` group fails
- **THEN** the `ios-promote` job concludes as failure (red) and the failure is plainly visible, while no merge is blocked (`ios-promote` is not a required check)

#### Scenario: A compile failure still fails the gate
- **WHEN** the signed archive fails to compile
- **THEN** the `ios-build` status check concludes as failure (red)

### Requirement: Cloud-managed code signing

**Every job that invokes `xcodebuild` with `-allowProvisioningUpdates`** — `ios-build`'s archive and `ios-deliver`'s export — SHALL sign using **two persistent certificates imported into that job's shared, ephemeral keychain** — an Apple **Distribution** certificate and an Apple **Development** certificate (sourced from GitHub Secrets) — combined with an App Store Connect API key with the **Admin** role, which **cloud-manages the App Store provisioning profile** for the TestFlight export. Both certs are imported deliberately, and in **both** jobs: an empty runner keychain makes automatic signing mint a **new** cert every run, exhausting Apple's per-account cert cap; `xcodebuild archive` provisions a **development identity in addition to the distribution one**, so persisting only Distribution still churned Development certs — the Development cert is therefore imported even though `ios.yml` no longer exports a development (sideload) IPA. The pipeline SHALL NOT use fastlane or `match`. The signed App Store IPA SHALL be uploaded to TestFlight via `Apple-Actions/upload-testflight-build`.

#### Scenario: Signing reuses imported persistent certs, mints none
- **WHEN** the device app is archived (`ios-build`) or the archive is exported (`ios-deliver`)
- **THEN** signing uses the two imported persistent certificates (Distribution and Development) and `xcodebuild -allowProvisioningUpdates` obtains the App Store provisioning profile via the Admin App Store Connect API key, without minting any new certificate

#### Scenario: Development cert import prevents cert-cap churn
- **WHEN** either the `ios-build` job archives the device app on any ref, or the `ios-deliver` job exports the archive on `main`
- **THEN** the imported Apple Development certificate satisfies the development identity that `xcodebuild -allowProvisioningUpdates` provisions, so no new Development certificate is minted in either job, even though no development IPA is exported

#### Scenario: Upload uses the official Apple action
- **WHEN** the signed App Store IPA is ready on `main`
- **THEN** it is uploaded to TestFlight via `Apple-Actions/upload-testflight-build` authenticated by the App Store Connect API key

### Requirement: Signing credentials are never stored in the Actions cache

All signing and upload credentials — the App Store Connect API key and the two certificate bundles (Distribution and Development `.p12` + passwords) — SHALL exist only as **encrypted GitHub Secrets** and SHALL NOT be written to, or restored from, the GitHub Actions cache. The signing keychain SHALL be ephemeral (created per run, dies with the runner). Only the Kotlin/Native (`~/.konan`) toolchain is cached.

#### Scenario: No credentials in cache
- **WHEN** the `ios-build` job runs on any ref, or the `ios-deliver` job runs on `main`
- **THEN** the App Store Connect API key and both certificate bundles are sourced from GitHub Secrets and are never stored in or restored from the Actions cache; only `~/.konan` is cached

### Requirement: Monotonic build numbers from the CI run

The app's `CURRENT_PROJECT_VERSION` (CFBundleVersion) SHALL be injected at build time from `github.run_number`, and `MARKETING_VERSION` SHALL be a fixed pre-release value (e.g. `0.1.0`). Because `github.run_number` is globally monotonic across all refs, each uploaded build — regardless of branch — SHALL carry a unique, strictly increasing build number for the marketing version, so TestFlight never rejects a duplicate and builds from different branches never collide.

#### Scenario: Two pushes produce two increasing build numbers
- **WHEN** two commits are pushed in sequence (to the same or different branches)
- **THEN** each produces a TestFlight build whose `CFBundleVersion` equals its `github.run_number`, and the second is strictly greater than the first

### Requirement: The build is App-Store-Connect uploadable

The app SHALL include a **1024×1024 opaque** (no alpha channel) app icon in its asset catalog, so the uploaded build is not rejected for a missing or invalid app icon.

#### Scenario: Upload is not rejected for a missing icon
- **WHEN** a build is uploaded to TestFlight
- **THEN** App Store Connect accepts it without a missing-/invalid-app-icon rejection

### Requirement: Export compliance is pre-declared

The app `Info.plist` SHALL set `ITSAppUsesNonExemptEncryption` to `NO`, and the export SHALL use an `ExportOptions.plist` with `method` `app-store-connect`, so uploads do not block on a manual export-compliance prompt.

#### Scenario: Upload does not block on export compliance
- **WHEN** a build is uploaded to TestFlight
- **THEN** it is not held for a manual export-compliance answer, because `ITSAppUsesNonExemptEncryption` is already declared `NO`

### Requirement: Signing and upload credentials are configured as secrets

The `ios-build` job (on every ref) and the `ios-deliver` job (on `main`) SHALL each source all Apple credentials from GitHub Secrets — the **Admin** App Store Connect API key (`ASC_KEY_ID`, `ASC_ISSUER_ID`, and `ASC_API_PRIVATE_KEY` holding the raw `.p8` PEM contents) and the two signing certificates (`SIGNING_CERT_P12_BASE64` / `SIGNING_CERT_PASSWORD` for Distribution and `SIGNING_DEV_CERT_P12_BASE64` / `SIGNING_DEV_CERT_PASSWORD` for Development). The Apple **Team ID** SHALL be committed in `Config.xcconfig` (it is not a secret).

#### Scenario: Credentials come from secrets, Team ID from config
- **WHEN** the `ios-build` job signs, or the `ios-deliver` job exports and uploads
- **THEN** the App Store Connect API key and both certificate bundles are read from GitHub Secrets, and the Team ID is read from the committed `Config.xcconfig`

### Requirement: Every main build is promoted to the public alpha channel

Promotion SHALL be performed by a dedicated `ios-promote` job in `.github/workflows/ios.yml` that declares `needs: ios-deliver` and is guarded by `if: github.ref == 'refs/heads/main'`. The job SHALL place the build that `ios-deliver` uploaded into the **`alpha`** App Store Connect beta group — an **external** group whose public link (`https://testflight.apple.com/join/pvqgV7Uz`) is open to anyone — so that the public alpha channel is, by construction, `main`.

The job SHALL run on `ubuntu-latest`. It compiles nothing, exports nothing, and SHALL NOT require Xcode, an IPA, a keychain, or any signing certificate: it acts purely against the App Store Connect REST API. It SHALL authenticate with the **existing** Admin App Store Connect API key (`ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_API_PRIVATE_KEY`) and SHALL NOT introduce a new secret.

Promotion SHALL NOT wait for Beta App Review. A build SHALL be added to the `alpha` group without regard to its `betaReviewState`: App Store Connect accepts a build into an external group while it is still `WAITING_FOR_REVIEW`, and a build carrying an already-approved `MARKETING_VERSION` auto-approves without human involvement.

#### Scenario: A push to main reaches the public alpha channel
- **WHEN** a commit is pushed to `refs/heads/main` and both merge gates and `ios-deliver` succeed
- **THEN** `ios-promote` submits the uploaded build to TestFlight and adds it to the `alpha` external group, so every public-link tester can install it

#### Scenario: A push to a non-main branch is never promoted
- **WHEN** a commit is pushed to any ref other than `refs/heads/main`
- **THEN** `ios-promote` does not run and no build is added to the `alpha` group

#### Scenario: A failed delivery promotes nothing
- **WHEN** `ios-deliver` fails on `refs/heads/main` (or either merge gate fails, so `ios-deliver` never runs)
- **THEN** `ios-promote` does not run and no build is added to the `alpha` group

#### Scenario: Promotion needs no Apple toolchain and no new credential
- **WHEN** `ios-promote` runs
- **THEN** it runs on `ubuntu-latest` using only the existing Admin App Store Connect API key, with no Xcode, no IPA, no keychain and no signing certificate

#### Scenario: Group assignment does not wait for review
- **WHEN** the build is still `WAITING_FOR_REVIEW` at the moment `ios-promote` assigns it
- **THEN** the assignment to the `alpha` group succeeds anyway, and the build auto-approves because its `MARKETING_VERSION` has already been approved once

### Requirement: The promoted build is identified by the CI run number

`ios-promote` SHALL locate the build to promote by its build number, which equals `github.run_number` (see *Monotonic build numbers from the CI run*), and SHALL NOT depend on an IPA artifact, a file hash, or any hand-off from `ios-deliver` beyond the job dependency. Because `ios-promote` runs in the **same** workflow run as `ios-deliver`, this number identifies exactly the build that run uploaded.

A freshly uploaded build is **not immediately discoverable** in App Store Connect. The lookup SHALL therefore be retried until the build appears, up to a bounded timeout, and the job SHALL fail (red) if it never appears.

#### Scenario: The build is found by run number
- **WHEN** `ios-promote` runs in a workflow run whose `github.run_number` is N
- **THEN** it promotes the App Store Connect build whose build number is N — the build `ios-deliver` uploaded in the same run

#### Scenario: A not-yet-discoverable build is waited for
- **WHEN** the build is not yet visible in App Store Connect immediately after upload
- **THEN** `ios-promote` retries the lookup until the build appears, and fails red if it does not appear within the timeout

### Requirement: No alpha tester is ever notified

`ios-promote` SHALL set `autoNotifyEnabled` to `false` on the promoted build's `buildBetaDetail`, so TestFlight sends **no** notification to any alpha tester. Builds arrive silently and continuously; alpha testers ride `main` via TestFlight auto-update.

Because TestFlight raises the notification at the moment a build becomes **available to the group**, the suppression SHALL be applied **before** the build is added to the `alpha` group. A failure to suppress SHALL abort the job **before** group assignment, so a build can never reach the group un-suppressed. This ordering is a **requirement**, not an implementation detail: a reordering that looks harmless would silently break the promise made to testers.

The App Store Connect CLI exposes no flag for this attribute; a direct `PATCH` of `buildBetaDetails` is the sanctioned mechanism.

#### Scenario: A promoted build notifies nobody
- **WHEN** a build is promoted to the `alpha` group
- **THEN** its `autoNotifyEnabled` is `false` and no alpha tester receives a TestFlight notification

#### Scenario: Suppression precedes group assignment
- **WHEN** `ios-promote` promotes a build
- **THEN** it sets `autoNotifyEnabled` to `false` strictly before adding the build to the `alpha` group

#### Scenario: A failed suppression promotes nothing
- **WHEN** setting `autoNotifyEnabled` to `false` fails
- **THEN** the job fails red **without** adding the build to the `alpha` group, so no un-suppressed build can reach a tester

### Requirement: Every main build is promoted, unfiltered

`ios-promote` SHALL promote **every** build produced by `main`, and SHALL NOT filter on changed paths, on commit type, or on whether the compiled binary actually differs from the previously promoted one. `ios.yml` triggers on an unfiltered `push`, so docs-only and backend-only merges produce iOS builds too; those builds SHALL be promoted like any other, even though they are binary-identical to their predecessor apart from the build number.

This is deliberate. Every filter considered — a path allowlist, a binary hash comparison, a conventional-commit-type gate — fails toward *"a real fix silently never reaches testers"*, which is the worst outcome an alpha channel can have. Promoting everything fails only toward tester-visible noise, which is merely annoying. The noisy option is therefore the correct one.

A path filter on the workflow **trigger** SHALL NOT be used to achieve this: `ios-build` and `ios-test` are **required** status checks, so a push that skips the workflow never posts them and merges would freeze.

#### Scenario: A docs-only merge is still promoted
- **WHEN** a docs-only or backend-only commit is merged to `main`, producing a build binary-identical to the last one apart from its build number
- **THEN** that build is promoted to the `alpha` group like any other

#### Scenario: The workflow trigger stays unfiltered
- **WHEN** `ios.yml` is configured
- **THEN** its `push` trigger carries no path filter, so the required `ios-build` and `ios-test` checks are posted on every ref and no merge can freeze

### Requirement: Promotion is idempotent

`ios-promote` SHALL be safe to re-run against a build it has already promoted. Before submitting, it SHALL read the build's state; if the build is already `BETA_APPROVED` **and** already in the `alpha` group, the job SHALL conclude **successfully** without re-submitting or re-assigning it.

Re-running a failed job is the first thing an operator reaches for when a promotion flakes, so a re-run must never turn red merely because the desired state was already reached.

#### Scenario: Re-running an already-promoted build is a green no-op
- **WHEN** `ios-promote` is re-run for a build that is already `BETA_APPROVED` and already in the `alpha` group
- **THEN** the job makes no further App Store Connect mutation and concludes successfully

### Requirement: The newest main build wins a review pile-up

When `ios-promote` submits a build to TestFlight it SHALL expire any build already queued in, or waiting for, Beta App Review (`--expire-build-submitted-for-review`), so the newest `main` build takes the queued build's place.

A pile-up is only reachable after a **`MARKETING_VERSION` bump**, which forces a genuine first-of-version Beta App Review taking hours to days; builds sharing an already-approved version auto-approve instantly. During such a window each merge expires its predecessor's submission — which is the desired behavior: the newest `main` build is always the one that should be in front of testers, and a build expired while still in review was never visible to a tester anyway.

Operators SHALL be able to recognise this window: while it lasts, **no build reaches the alpha channel and nothing is red**.

#### Scenario: A newer build displaces one waiting for review
- **WHEN** a build is `WAITING_FOR_REVIEW` and a newer commit is merged to `main`
- **THEN** the newer build's submission expires the waiting one and takes its place, so the newest `main` build is the one under review

#### Scenario: A version bump stalls the channel without failing
- **WHEN** `MARKETING_VERSION` is bumped and the first build of the new version enters a genuine Beta App Review
- **THEN** no build reaches the `alpha` channel until that review concludes, and `ios-promote` nevertheless concludes green — the stall is expected, not a failure

### Requirement: A cancelled promotion is benign

`ios.yml` sets `concurrency: ios-${{ github.ref }}` with `cancel-in-progress: true`, so a merge landing while an earlier run's `ios-promote` is still polling **cancels** that promotion, and the earlier build is never promoted. This SHALL be treated as **correct behavior, not a defect**: the newer run promotes the newer build, and nothing that a tester should have received is lost.

#### Scenario: A rapid second merge cancels the first promotion
- **WHEN** a commit is merged to `main` while the previous run's `ios-promote` is still waiting for its build to process
- **THEN** the previous run (including its `ios-promote`) is cancelled, the newer run promotes the newer build, and the cancelled promotion is not a failure to investigate
