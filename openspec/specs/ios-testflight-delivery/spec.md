# ios-testflight-delivery Specification

## Purpose
Uploads a **signed `main` build to TestFlight** on every merge, with no human step — but **distributes to no external tester**. One job carries it: **`ios-deliver`** signs, exports and uploads the archive `ios-build` already produced (never recompiling it), and **depends on both merge gates** (`ios-build` and `ios-test`, capability `ios-ci`) so **a red test suite stops the upload**. The uploaded build reaches only the **internal `development` group** (which has `hasAccessToAllBuilds`); nothing adds it to any external group, so these builds accumulate unseen — an accepted trade-off, because **distribution to real users is App-Store-only**: the dispatch-driven release channel (`ios-appstore-promote.yml`, capability `ios-appstore-release`) is the only path to external users.

This capability once made `main` the **public alpha channel** via a second `ios-promote` job that pushed each build into an open-enrollment `alpha` external group. That automatic public promotion was **removed** — see the decision record below.

Delivery is decoupled from merges **structurally** — a `main`-only job posting no required status check — rather than by `continue-on-error`, so a failure is visibly red yet blocks nothing. Signing combines **two imported persistent certificates** (Apple Distribution + Apple Development, from GitHub Secrets) with **cloud-managed provisioning profiles** (App Store Connect Admin API key, no fastlane/`match`). Per-branch installability before merge is served out of band by the ssh-mac build loop (dev infrastructure), not TestFlight. Also covers build numbering, export options, tag-ref exclusion, and the required signing credentials.

Decision record: `changes/archive/2026-07-14-gate-testflight-on-tests` (splitting delivery out of the build gate),
`changes/archive/2026-07-14-promote-main-builds-to-alpha` (the since-removed alpha promotion),
`changes/archive/2026-07-19-remove-alpha-testflight-promotion` (removing the public alpha promotion; App-Store-only).
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

Delivery SHALL be decoupled from the merge gates **structurally**: it lives in a separate `main`-only job (`ios-deliver`) that posts **no required status check** (capability `branch-protection` requires `build`, `ios-build` and `ios-test`, and SHALL NOT require `ios-deliver` — a job that never runs on a pull-request branch would, if required, freeze every merge). Because it can block nothing, `ios-deliver` SHALL NOT use `continue-on-error`: a failed export or a failed App Store Connect upload SHALL conclude the job as **failure (red)**, so a broken delivery is visible rather than hidden inside an otherwise-green run.

This replaces the previous `continue-on-error` convention, under which a transient delivery failure left the run green and could pass unnoticed.

#### Scenario: A delivery flake is red but blocks nothing
- **WHEN** both gates are green on `main` but the export or the TestFlight upload fails
- **THEN** the `ios-deliver` job concludes as failure (red) and the failure is plainly visible, while no merge is blocked (the commit is already merged and `ios-deliver` is not a required check)

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

The app's `CURRENT_PROJECT_VERSION` (CFBundleVersion) SHALL be injected at build time from `github.run_number`. The `MARKETING_VERSION` SHALL be **computed by `ios.yml`** as `max(floor, latest vX.Y tag with its minor incremented by one)`, where the **floor** is a two-part `X.Y` value **committed in `Config.xcconfig`** (seeded `0.1`) rather than in `project.pbxproj`, and `max` is a numeric `(major, minor)` **tuple** comparison. The minor increment SHALL be an **integer bump** (`v0.9 → 0.10`), never a decimal addition, so a **major jump** (`→ 1.0`) is reachable **only** by committing a higher floor. With no `vX.Y` tag present, the computed version is the floor. The App Store release channel no longer overrides `MARKETING_VERSION` — it builds nothing and derives the store version from the promoted build (capability `ios-appstore-release`). Because `github.run_number` is globally monotonic across all refs, each uploaded build SHALL carry a unique, strictly increasing `CFBundleVersion`, so a marketing-version train never contains a duplicate build number.

#### Scenario: Two pushes produce two increasing build numbers
- **WHEN** two commits are pushed in sequence (to the same or different branches)
- **THEN** each produces a build whose `CFBundleVersion` equals its `github.run_number`, and the second is strictly greater than the first

#### Scenario: The marketing version is computed from the floor and the last tag
- **WHEN** a `main` build is produced and the latest release tag is `v0.2` with a committed floor of `0.1`
- **THEN** its `MARKETING_VERSION` is `max(0.1, 0.3)` = `0.3`, baked into both the app and extension targets, and no `MARKETING_VERSION` is pinned in `project.pbxproj`

#### Scenario: A major jump requires a floor bump, not a decimal carry
- **WHEN** the latest release tag is `v0.9`
- **THEN** the computed minor bump is `0.10` (integer), and reaching `1.0` requires committing a floor of `1.0`; a decimal `0.9 + 0.1 = 1.0` is never performed

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

### Requirement: Tag refs fire only the release workflow

`ios.yml` triggers on a `push` that carries **no branch path filter**, so docs-only and backend-only merges to `main` produce iOS builds too and are delivered like any other. A **branch** path filter on the trigger SHALL NOT be added: `ios-build` and `ios-test` are **required** status checks (capability `ios-ci`), so a push that skips the workflow never posts them and merges would freeze. **Tag** refs, however, SHALL be excluded from the `push` triggers of both `build.yml` and `ios.yml`, so a `vX.Y` tag fires only the release workflow (capability `ios-appstore-release`) and not a redundant `main` build/delivery — excluding tags does not affect branch/PR pushes, so the required checks are still posted on every branch and no merge can freeze.

#### Scenario: The workflow trigger carries no branch path filter
- **WHEN** `ios.yml` is configured
- **THEN** its `push` trigger carries no branch path filter, so the required `ios-build` and `ios-test` checks are posted on every branch and no merge can freeze

#### Scenario: A tag fires only the release workflow
- **WHEN** a `vX.Y` tag is pushed
- **THEN** the `push` triggers of `build.yml` and `ios.yml` exclude it, so neither runs on the tag and no `main` build or delivery is produced for it

### Requirement: Distribution builds use the production APNs environment

Every CI **Release/distribution** archive SHALL be built with `APS_ENVIRONMENT=production` and `APNS_ENV=production`, so the shipped build's `aps-environment` entitlement is `production` and it can receive production APNs pushes. This holds for the `main` TestFlight build produced by `ios-build`/`ios-deliver` — which is also the build the App Store release channel promotes (capability `ios-appstore-release`), so a promoted build is production-APNs by construction. Only **dev/sideload** builds — the `ios.yml` `workflow_dispatch` dev-IPA path (Debug, `upload_host` override) and the ssh-mac local build loop — SHALL keep the `Config.xcconfig` `development`/`sandbox` default. The environment is therefore tied to the build configuration: a Release archive is production, a Debug/dev archive is sandbox.

Previously neither value was overridden in CI, so every `main` TestFlight build shipped with the `Config.xcconfig` `development`/`sandbox` default and could not receive production pushes — contradicting the intent (all TestFlight/App Store builds are production; only dev-sideload is sandbox). Injecting the override in the shared archive path makes that intent true.

#### Scenario: A main TestFlight build is production-APNs
- **WHEN** `ios-build` produces the signed Release archive on `main`
- **THEN** it is built with `APS_ENVIRONMENT=production` and `APNS_ENV=production`, and the delivered build's `aps-environment` entitlement is `production`

#### Scenario: A promoted App Store build is production-APNs
- **WHEN** the App Store release channel promotes a `main` `ios-deliver` build
- **THEN** that build already carries the `production` `aps-environment` entitlement, because it was built as a Release archive on `main`

#### Scenario: A dev-IPA build stays sandbox
- **WHEN** the `ios.yml` `workflow_dispatch` dev-IPA path builds a Debug archive with an `upload_host` override
- **THEN** it uses the `Config.xcconfig` `development`/`sandbox` default and is not overridden to production

### Requirement: Release archives bake the crash-reporting DSN; dev builds never receive it

Every CI **Release/distribution** archive SHALL be built with the crash-reporting DSN injected from the
`SENTRY_DSN` repository secret through the same shared archive seam that injects `APS_ENVIRONMENT`
(capability `crash-reporting` consumes it from the bundle at runtime). The value SHALL reach **both**
targets — the app and the background-upload extension. **Dev/sideload** builds — the `ios.yml`
`workflow_dispatch` dev-IPA path and the ssh-mac local build loop — SHALL NOT receive the DSN, leaving
it absent so the SDK never starts there: like the APNs environment, the reporting channel is tied to the
build configuration, with no separate enable flag that could disagree with it.

#### Scenario: A main TestFlight build carries the DSN

- **WHEN** `ios-build` produces the signed Release archive on `main`
- **THEN** both the app's and the extension's bundle configuration carry the DSN from the
  `SENTRY_DSN` secret

#### Scenario: A dev-IPA build carries no DSN

- **WHEN** the `ios.yml` `workflow_dispatch` dev-IPA path or the ssh-mac loop builds a Debug archive
- **THEN** no DSN is injected, the bundle value is absent, and crash reporting never initializes in
  that build

### Requirement: main delivery retains the build's dSYMs keyed by build number

On `main`, the delivery pipeline SHALL publish the Release archive's dSYMs as a workflow artifact
whose name carries the build number (`CFBundleVersion`), so an address-only crash report from any
delivered build can be symbolicated offline (the Bugsink instance ingests no dSYMs — see capability
`crash-reporting`). On other refs no dSYM artifact is published (consistent with the pure-gate posture
of `ios-build`).

#### Scenario: A main build's dSYMs are retrievable

- **WHEN** a `main` push delivers build `N` to TestFlight
- **THEN** a workflow artifact keyed by `N` contains that archive's dSYMs

#### Scenario: Non-main refs publish no dSYM artifact

- **WHEN** a push to any other ref produces its gate archive
- **THEN** no dSYM artifact is published for it
